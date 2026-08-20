package com.goat.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists per-app usage stats (open counts + last-opened timestamps) used to power
 * the "Recommended apps" and "Recent Apps" sections of the app drawer.
 *
 * IMPORTANT: This data intentionally lives in its own SharedPreferences file
 * (PREFS_FILE_NAME), kept separate from every other pref file in the app. The
 * "Fix Issues" flow (see LauncherHomeActivity.performFreshRestart) wipes all other
 * SharedPreferences files but explicitly skips this one, so recommended/recent
 * data stays intact across that action.
 */
/**
 * Persists per-app usage stats (open counts + last-opened timestamps) used to power
 * the "Recommended apps" and "Recent Apps" sections of the app drawer.
 *
 * IMPORTANT: This data intentionally lives in its own SharedPreferences file
 * (PREFS_FILE_NAME), kept separate from every other pref file in the app. The
 * "Fix Issues" flow (see LauncherHomeActivity.performFreshRestart) wipes all other
 * SharedPreferences files but explicitly skips this one, so recommended/recent
 * data stays intact across that action -- including the daily decay below, which
 * must keep running on its own schedule regardless of how many times "Fix Issues"
 * is pressed.
 *
 * Daily decay: every calendar day, each app's open-count score is multiplied by
 * DECAY_FACTOR (0.9, i.e. -10%). This means an app the user stops using will drop
 * out of "Recommended" within a handful of days instead of staying stuck at the
 * top forever just because it was used a lot in the past.
 */
object AppUsageStore {

    /** Name of the SharedPreferences file backing this store. */
    const val PREFS_FILE_NAME = "goat_app_usage_stats"

    private const val KEY_OPEN_COUNT_PREFIX = "open_count_"
    private const val KEY_LAST_OPENED_PREFIX = "last_opened_"
    private const val KEY_LAST_DECAY_EPOCH_DAY = "last_decay_epoch_day"

    /** Multiply every app's score by this once per calendar day (-10%/day). */
    private const val DECAY_FACTOR = 0.9f

    /** Scores below this are treated as zero and dropped, to avoid endless tiny floats. */
    private const val MIN_SCORE_THRESHOLD = 0.01f

    private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)

    private fun currentEpochDay(): Long = System.currentTimeMillis() / MILLIS_PER_DAY

    /**
     * Applies daily decay if one or more calendar days have passed since it last ran.
     * Safe to call as often as needed (e.g. every time the drawer is opened) -- it's a
     * no-op if today's decay has already been applied. Runs independently of "Fix
     * Issues" since it lives in this un-wiped prefs file.
     */
    private fun applyDecayIfNeeded(context: Context) {
        val p = prefs(context)
        val today = currentEpochDay()
        val lastDecayDay = p.getLong(KEY_LAST_DECAY_EPOCH_DAY, today)

        val daysElapsed = today - lastDecayDay
        if (daysElapsed <= 0) return

        val editor = p.edit()
        val decayMultiplier = Math.pow(DECAY_FACTOR.toDouble(), daysElapsed.toDouble()).toFloat()

        for ((key, value) in p.all) {
            if (key.startsWith(KEY_OPEN_COUNT_PREFIX) && value is Number) {
                val decayed = value.toFloat() * decayMultiplier
                if (decayed < MIN_SCORE_THRESHOLD) {
                    editor.remove(key)
                } else {
                    editor.putFloat(key, decayed)
                }
            }
        }
        editor.putLong(KEY_LAST_DECAY_EPOCH_DAY, today)
        editor.apply()
    }

    /** Reads a stored score whether it was saved as a Float (current) or an older Int. */
    private fun readScore(p: SharedPreferences, key: String): Float {
        return when (val raw = p.all[key]) {
            is Float -> raw
            is Int -> raw.toFloat()
            else -> 0f
        }
    }

    /** Call whenever an app is launched from the drawer. */
    fun recordAppOpened(context: Context, packageName: String) {
        applyDecayIfNeeded(context)
        val p = prefs(context)
        val newScore = readScore(p, KEY_OPEN_COUNT_PREFIX + packageName) + 1f
        p.edit()
            .putFloat(KEY_OPEN_COUNT_PREFIX + packageName, newScore)
            .putLong(KEY_LAST_OPENED_PREFIX + packageName, System.currentTimeMillis())
            .apply()
    }

    /**
     * packageName -> usage score (starts as an open count, but decays -10%/day so it
     * naturally falls if the app stops being used). Applies any pending daily decay
     * first, so callers always see up-to-date scores.
     */
    fun getOpenCounts(context: Context): Map<String, Float> {
        applyDecayIfNeeded(context)
        val p = prefs(context)
        val result = mutableMapOf<String, Float>()
        for ((key, value) in p.all) {
            if (key.startsWith(KEY_OPEN_COUNT_PREFIX) && value is Number) {
                result[key.removePrefix(KEY_OPEN_COUNT_PREFIX)] = value.toFloat()
            }
        }
        return result
    }

    /** packageName -> last-opened timestamp in millis. */
    fun getLastOpenedTimestamps(context: Context): Map<String, Long> {
        val p = prefs(context)
        val result = mutableMapOf<String, Long>()
        for ((key, value) in p.all) {
            if (key.startsWith(KEY_LAST_OPENED_PREFIX) && value is Long) {
                result[key.removePrefix(KEY_LAST_OPENED_PREFIX)] = value
            }
        }
        return result
    }
}
