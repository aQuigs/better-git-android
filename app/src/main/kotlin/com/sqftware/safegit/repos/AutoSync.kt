package com.sqftware.safegit.repos

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** How often a repo syncs on its own; [period] is null when only the user starts its syncs. */
enum class AutoSync(val period: Duration?) {
    OFF(null),

    // Android's shortest period for periodic work
    EVERY_15_MINUTES(15.minutes),
    HOURLY(1.hours),
    EVERY_6_HOURS(6.hours),
    DAILY(1.days),
    WEEKLY(7.days),
}
