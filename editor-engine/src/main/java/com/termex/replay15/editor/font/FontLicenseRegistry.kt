package com.termex.replay15.editor.font

/**
 * Central registry that validates and classifies font licenses.
 *
 * Only fonts with licenses in the approved set may be downloaded and used in projects.
 * Unknown or proprietary licenses are classified as [LicenseStatus.NEEDS_REVIEW] or
 * [LicenseStatus.REJECTED].
 */
object FontLicenseRegistry {

    enum class LicenseStatus { APPROVED, NEEDS_REVIEW, REJECTED }

    private val approved = setOf(
        "OFL-1.1",
        "SIL OFL 1.1",
        "Apache-2.0",
        "Apache License 2.0",
        "UFL-1.0",
        "Ubuntu Font License",
        "Recly-Original-1.0",
    )

    private val rejected = setOf(
        "proprietary",
        "commercial",
        "all-rights-reserved",
    )

    /**
     * Classify a license identifier.
     *
     * @param license SPDX-style identifier or human-readable license name.
     * @return The classification status.
     */
    fun classify(license: String): LicenseStatus = when {
        license.isBlank() -> LicenseStatus.NEEDS_REVIEW
        approved.any { it.equals(license, ignoreCase = true) } -> LicenseStatus.APPROVED
        rejected.any { it.equals(license, ignoreCase = true) } -> LicenseStatus.REJECTED
        else -> LicenseStatus.NEEDS_REVIEW
    }

    /** Quick check: is this license approved for redistribution? */
    fun isApproved(license: String): Boolean = classify(license) == LicenseStatus.APPROVED

    /** All recognized approved license identifiers. */
    fun approvedLicenses(): Set<String> = approved.toSet()
}
