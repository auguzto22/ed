# Assets

Implemented classes: AssetRepository, AssetDownloadManager, AssetValidator,
EffectDefinition, EffectInstance and BuiltInEffects. EffectTools is the UI adapter;
PackageEffect is the GPU adapter. Repository and worker jobs are separate from the
Activity. AssetType reserves future categories; only EFFECT packages are currently
installable. This is not a complete font, sound, template or AI-model platform.

Sources are bundled assets, locally imported ZIP packages, and user-configured
HTTPS catalogs. No public server is configured and no catalog URL is invented.
Offline built-in effects and already installed packages work without a network.
No user video is uploaded by this subsystem.

Catalog root: `engineVersion: 1` and `assets: [...]`. Each entry contains `id`,
`version`, `engineVersion: 1`, `type: "EFFECT"`, `name`, `downloadUrl`, `size`
(exact byte count), `sha256` (64 hexadecimal characters) and optional `premium`.
Only HTTPS, HTTP 200, and no redirects/URL credentials are allowed. Catalogs are
bounded to 1 MB and 500 entries; downloads are bounded to 4 MiB and their declared
length. Paid packages are rejected until an entitlement implementation exists.

Flow: download on one worker -> SHA-256 and size -> bounded ZIP extraction ->
manifest and shader validation -> match catalog ID/version -> atomic installation.
Expanded content is bounded, duplicate entries are rejected, paths cannot leave
the installation staging directory, and only the three documented root filenames
are accepted. Interrupted downloads clean their own temporary files. Cancellation
is checked while streaming; network connection/read timeouts are 15 seconds.

Installation: `files/editor-assets/<id>/<version>/`. These are persistent project
dependencies, not disposable caches. Never remove them with a general Clear Cache
action. Favorites and the last 20 used IDs are stored in SharedPreferences; the
library exposes favorites and search, but not a separate recents screen yet.

Waveforms use a separate 4 MiB RAM LRU and bounded queued decoding, with disk data
under `cache/editor-waveforms`. Existing ThumbnailCache remains intact. A unified
cache settings UI, disk eviction policy, proxies and frame/render caches are still
pending. Never remove source media, user exports or project files during cleanup.
