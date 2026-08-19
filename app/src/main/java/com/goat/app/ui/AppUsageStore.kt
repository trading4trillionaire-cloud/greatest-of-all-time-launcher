package com.goat.app.ui

import android.content.Context

/**
 * Tracks how "important" each app currently is to the user, so the app drawer can be
 * sorted by usage instead of plain alphabetical order.
 *
 * Score model: score = usage_count x recency_weight, implemented efficiently as a
 * running score per app that:
 *   - goes up by +1 every time the app is opened
 *   - decays by a small daily factor (10%) so old, no-longer-used activity fades out
 *
 * This gives the same effect as weighting older opens less than recent ones, without
 * having to store a full open-history per app - just one float per package.
 *
 * Apps that were never opened simply have no entry (score = 0) and are left to fall
 * back to alphabetical order among themselves, so the list stays predictable for them.
 */
object AppUsageStore {

    private const val PREFS_NAME = "app_usage_scores"
    private const val KEY_LAST_DECAY_DAY = "__last_decay_day"

    // Multiplier applied once per elapsed day. 0.9 means a score loses ~10% per day
    // if the app isn't opened again - recent habits stay on top, old ones fade.
    private const val DAILY_DECAY_FACTOR = 0.9f

    // Below this, a score is considered noise and dropped to keep prefs small.
    private const val MIN_TRACKED_SCORE = 0.01f

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun currentEpochDay(): Long = System.currentTimeMillis() / (24L * 60 * 60 * 1000)

    /**
     * Applies any decay owed since the last time we touched the store (once per real
     * calendar day, not on every call). Cheap no-op the vast majority of the time.
     */
    @Synchronized
    private fun applyPendingDecay(context: Context) {
        val p = prefs(context)
        val today = currentEpochDay()
        val lastDay = p.getLong(KEY_LAST_DECAY_DAY, today)
        val daysElapsed = today - lastDay
        if (daysElapsed <= 0) return

        val factor = Math.pow(DAILY_DECAY_FACTOR.toDouble(), daysElapsed.toDouble()).toFloat()
        val editor = p.edit()

        for ((key, value) in p.all) {
            if (key == KEY_LAST_DECAY_DAY) continue
            val score = value as? Float ?: continue
            val decayed = score * factor
            if (decayed < MIN_TRACKED_SCORE) {
                editor.remove(key)
            } else {
                editor.putFloat(key, decayed)
            }
        }

        editor.putLong(KEY_LAST_DECAY_DAY, today)
        editor.apply()
    }

    /** Call this whenever the user actually opens an app from the drawer. */
    @Synchronized
    fun recordAppOpened(context: Context, packageName: String) {
        applyPendingDecay(context)
        val p = prefs(context)
        val current = p.getFloat(packageName, 0f)
        p.edit().putFloat(packageName, current + 1f).apply()
    }

    /** Current usage score for one package (0 if never opened). */
    @Synchronized
    fun getScore(context: Context, packageName: String): Float {
        applyPendingDecay(context)
        return prefs(context).getFloat(packageName, 0f)
    }

    /** Snapshot of all tracked scores, keyed by package name. */
    @Synchronized
    fun getAllScores(context: Context): Map<String, Float> {
        applyPendingDecay(context)
        val p = prefs(context)
        val result = HashMap<String, Float>()
        for ((key, value) in p.all) {
            if (key == KEY_LAST_DECAY_DAY) continue
            val score = value as? Float ?: continue
            result[key] = score
        }
        return result
    }
}
