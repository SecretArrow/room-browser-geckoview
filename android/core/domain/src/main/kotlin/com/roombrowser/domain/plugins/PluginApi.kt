package com.roombrowser.domain.plugins

/**
 * The plugin API version this build speaks.
 *
 * A manifest that declares any other number is refused at install with this
 * number in the message, so a plugin written for a future or older API fails
 * loudly at the door rather than half-working behind it — the manifest
 * contract is strict on purpose (unknown fields are refused too) because a
 * silently degraded plugin is strictly worse to debug than a rejected one.
 */
const val API_VERSION: Int = 1
