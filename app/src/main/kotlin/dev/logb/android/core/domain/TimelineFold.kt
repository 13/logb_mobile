package dev.logb.android.core.domain

import dev.logb.android.core.db.entity.ActivityEntity

/** One row of the timeline: an entry as it is, or a run of readings folded into one line. */
sealed interface TimelineRow {
    data class Entry(val activity: ActivityEntity) : TimelineRow

    /** `key` is the run's newest uuid, so its open/closed state survives a reload. */
    data class Readings(val key: String, val readings: List<ActivityEntity>) : TimelineRow
}

/** Port of `frontend/src/lib/timeline-fold.ts`. */
object TimelineFold {
    /** Folds every run of two or more consecutive readings into one row; a single reading stays a line of its own. */
    fun fold(items: List<ActivityEntity>): List<TimelineRow> {
        val rows = mutableListOf<TimelineRow>()
        var run = mutableListOf<ActivityEntity>()
        fun flush() {
            if (run.size >= 2) rows += TimelineRow.Readings("r${run[0].uuid}", run.toList())
            else run.forEach { rows += TimelineRow.Entry(it) }
            run = mutableListOf()
        }
        for (a in items) {
            if (a.category == "reading") run += a
            else { flush(); rows += TimelineRow.Entry(a) }
        }
        flush()
        return rows
    }

    /** The lowest and highest reading in a folded run, or null when none carries a value. */
    fun readingSpan(readings: List<ActivityEntity>): Pair<Long, Long>? {
        val values = readings.mapNotNull { it.counterValue }
        if (values.isEmpty()) return null
        return values.min() to values.max()
    }
}
