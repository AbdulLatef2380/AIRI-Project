package com.airi.assistant.domain.release

/**
 * Defines the release capabilities backed by this Android build.
 *
 * Automatic update discovery remains disabled until AIRI has a verified,
 * authenticated release catalog and an approved installer hand-off.
 */
object UpdateAvailabilityPolicy {
    const val automaticChecksAvailable: Boolean = false
}
