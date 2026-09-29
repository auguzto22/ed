#!/usr/bin/env python3
"""
sync_font_catalog.py — Generate font_catalog.json from verified sources.

This script fetches metadata from the Google Fonts API, validates licenses
against strict rules (SIL OFL 1.1, Apache 2.0, Ubuntu Font License), locates
TTF download URLs, computes SHA-256 digests, categorizes families, generates
thematic tags, and outputs the catalog JSON used by the Recly font system.

Usage:
    python3 tools/fonts/sync_font_catalog.py [--api-key KEY] [--max FAMILIES]

Output:
    app/src/main/assets/fonts/font_catalog.json
    build/font_catalog_report.json
"""

import argparse
import hashlib
import json
import os
import re
import sys
import urllib.request
from pathlib import Path

# ── Configuration ────────────────────────────────────────────────────────────

APPROVED_LICENSES = {"OFL-1.1", "SIL OFL 1.1", "Apache-2.0", "Apache License 2.0", "UFL-1.0"}

# Builtin IDs that must not appear in the remote catalog.
BUILTIN_IDS = {
    "system_sans", "system_serif", "system_sans_black", "system_sans_condensed",
    "system_sans_light", "system_monospace", "system_serif_monospace", "system_cursive",
    "system_casual", "outfit_regular", "montserrat_regular", "montserrat_semibold",
    "bebas_neue_regular", "playfair_display_regular", "caveat_regular", "space_mono_regular",
    "oswald_light", "raleway_regular", "anton_regular", "poppins_regular", "poppins_semibold",
    "im_fell_dw_pica_regular", "recly_sans_regular", "recly_wide_bold", "recly_poster_bold",
    "recly_pixel_regular", "recly_arcade_regular", "recly_mono_regular",
    "recly_signature_regular", "recly_brush_regular", "recly_gothic_regular",
    "recly_editorial_regular", "recly_urban_regular",
}

# Families to include in the initial pilot catalog (subset of Google Fonts).
PILOT_FAMILIES = {
    "Inter", "Roboto", "Lato", "Open Sans", "Nunito", "Source Sans 3",
    "Work Sans", "DM Sans", "Rubik", "Fira Code", "JetBrains Mono",
    "Merriweather", "Lora", "Noto Serif", "Crimson Text", "Abril Fatface",
    "Righteous", "Permanent Marker", "Fredoka", "Pacifico", "Dancing Script",
    "Great Vibes", "Shadows Into Light", "Press Start 2P", "Silkscreen",
    "VT323", "Josefin Sans", "Barlow", "Ubuntu", "Archivo",
}

CATEGORY_MAP = {
    "sans-serif": "SANS",
    "serif": "SERIF",
    "display": "DISPLAY",
    "handwriting": "SCRIPT",
    "monospace": "MONO",
}

TAG_KEYWORDS = {
    "Gaming": ["pixel", "arcade", "press start", "silkscreen", "vt323", "mono"],
    "Retro": ["pixel", "press start", "silkscreen", "vt323", "shadows"],
    "Elegante": ["playfair", "lora", "merriweather", "crimson", "abril", "great vibes", "dancing script", "noto serif"],
    "Moderno": ["inter", "roboto", "dm sans", "work sans", "source sans", "barlow", "rubik", "archivo", "nunito", "ubuntu", "jetbrains"],
    "Minimal": ["inter", "lato", "work sans", "josefin", "dm sans", "nunito", "outfit"],
    "Bold": ["abril", "righteous", "permanent", "fredoka", "rubik", "archivo", "anton", "bebas"],
    "Escrita": ["pacifico", "dancing", "great vibes", "shadows", "caveat", "permanent marker"],
    "Em alta": ["inter", "dm sans", "poppins", "abril", "pacifico", "bebas"],
}


def family_to_id(family: str) -> str:
    """Convert a family name to a stable lowercase ID."""
    return re.sub(r"[^a-z0-9]+", "_", family.lower()).strip("_")


def classify_license(license_str: str) -> str | None:
    """Return the SPDX-like identifier if approved, else None."""
    normalized = license_str.strip()
    for approved in APPROVED_LICENSES:
        if approved.lower() == normalized.lower():
            return approved
    return None


def infer_tags(family: str) -> list[str]:
    """Assign thematic tags based on family name keywords."""
    tags = []
    lower = family.lower()
    for tag, keywords in TAG_KEYWORDS.items():
        if any(kw in lower for kw in keywords):
            tags.append(tag)
    return tags


def sha256_url(url: str) -> str:
    """Download a file and compute its SHA-256 (returns empty on failure)."""
    try:
        data = urllib.request.urlopen(url, timeout=30).read()
        return hashlib.sha256(data).hexdigest()
    except Exception as e:
        print(f"  ⚠ SHA-256 failed for {url}: {e}", file=sys.stderr)
        return ""


def fetch_google_fonts(api_key: str | None) -> list[dict]:
    """Fetch metadata from the Google Fonts Developer API."""
    base_url = "https://www.googleapis.com/webfonts/v1/webfonts?sort=popularity"
    if api_key:
        base_url += f"&key={api_key}"
    try:
        data = urllib.request.urlopen(base_url, timeout=30).read()
        return json.loads(data).get("items", [])
    except Exception as e:
        print(f"✗ Failed to fetch Google Fonts API: {e}", file=sys.stderr)
        return []


def process_family(item: dict, compute_sha: bool = False) -> dict | None:
    """Process a single Google Fonts API item into a catalog entry."""
    family = item.get("family", "")
    fid = family_to_id(family)

    if fid in BUILTIN_IDS:
        return None

    license_id = classify_license(item.get("license", ""))
    if not license_id:
        return None

    category = CATEGORY_MAP.get(item.get("category", "").lower(), "SANS")

    # Extract weights from variants
    weights = set()
    styles = set()
    for variant in item.get("variants", []):
        if variant == "regular":
            weights.add(400)
            styles.add("normal")
        elif variant == "italic":
            weights.add(400)
            styles.add("italic")
        elif variant.endswith("italic"):
            w = variant.replace("italic", "").strip()
            weights.add(int(w) if w else 400)
            styles.add("italic")
        else:
            try:
                weights.add(int(variant))
                styles.add("normal")
            except ValueError:
                pass

    if not weights:
        weights = {400}
    if not styles:
        styles = {"normal"}

    # Get TTF URL for regular weight
    files = item.get("files", {})
    ttf_url = files.get("regular", files.get("400", ""))
    if not ttf_url and files:
        ttf_url = next(iter(files.values()))

    # Force HTTPS
    ttf_url = ttf_url.replace("http://", "https://")

    sha = sha256_url(ttf_url) if compute_sha and ttf_url else ""

    tags = infer_tags(family)
    subsets = item.get("subsets", ["latin"])

    entry = {
        "id": fid,
        "family": family,
        "displayName": family,
        "weights": sorted(weights),
        "styles": sorted(styles),
        "variable": len(weights) > 3,  # heuristic
        "subsets": subsets,
        "category": category,
        "tags": tags,
        "source": "google-fonts",
        "license": license_id,
        "licenseUrl": f"https://fonts.google.com/specimen/{family.replace(' ', '+')}",
        "remoteUrl": ttf_url,
        "version": item.get("version", ""),
        "sha256": sha,
        "fileSize": 0,
    }
    return entry


def main():
    parser = argparse.ArgumentParser(description="Sync Recly font catalog from Google Fonts.")
    parser.add_argument("--api-key", help="Google Fonts API key (optional, but rate-limited without).")
    parser.add_argument("--max", type=int, default=30, help="Maximum number of families to include.")
    parser.add_argument("--sha", action="store_true", help="Compute SHA-256 for each font (slow).")
    parser.add_argument("--all", action="store_true", help="Include all approved fonts, not just the pilot set.")
    args = parser.parse_args()

    root = Path(__file__).resolve().parent.parent.parent
    output_path = root / "app" / "src" / "main" / "assets" / "fonts" / "font_catalog.json"
    report_path = root / "build" / "font_catalog_report.json"

    print("Fetching Google Fonts metadata...")
    items = fetch_google_fonts(args.api_key)
    if not items:
        print("✗ No fonts fetched. Exiting.", file=sys.stderr)
        sys.exit(1)

    catalog = []
    rejected = []
    approved_count = 0

    for item in items:
        family = item.get("family", "")

        if not args.all and family not in PILOT_FAMILIES:
            continue

        entry = process_family(item, compute_sha=args.sha)
        if entry is None:
            rejected.append({"family": family, "reason": "license_or_builtin"})
            continue

        approved_count += 1
        catalog.append(entry)
        print(f"  ✓ {family} ({entry['license']}, {len(entry['weights'])} weights)")

        if len(catalog) >= args.max:
            break

    # Write catalog JSON
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(catalog, f, indent=2, ensure_ascii=False)
    print(f"\n✓ Wrote {len(catalog)} families to {output_path}")
    print(f"  Catalog size: {output_path.stat().st_size / 1024:.1f} KB")

    # Write report
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report = {
        "total_fetched": len(items),
        "approved": approved_count,
        "rejected": len(rejected),
        "catalog_families": len(catalog),
        "families": [e["family"] for e in catalog],
        "categories": {},
        "licenses": {},
        "rejections": rejected[:50],
    }
    for entry in catalog:
        cat = entry["category"]
        lic = entry["license"]
        report["categories"][cat] = report["categories"].get(cat, 0) + 1
        report["licenses"][lic] = report["licenses"].get(lic, 0) + 1

    with open(report_path, "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2, ensure_ascii=False)
    print(f"✓ Wrote report to {report_path}")


if __name__ == "__main__":
    main()
