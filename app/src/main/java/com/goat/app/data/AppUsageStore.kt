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
object AppUsageStore {

    /** Name of the SharedPreferences file backing this store. */
    const val PREFS_FILE_NAME = "goat_app_usage_stats"

    private const val KEY_OPEN_COUNT_PREFIX = "open_count_"
    private const val KEY_LAST_OPENED_PREFIX = "last_opened_"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)

    /** Call whenever an app is launched from the drawer. */
    fun recordAppOpened(context: Context, packageName: String) {
        val p = prefs(context)
        val newCount = p.getInt(KEY_OPEN_COUNT_PREFIX + packageName, 0) + 1
        p.edit()
            .putInt(KEY_OPEN_COUNT_PREFIX + packageName, newCount)
            .putLong(KEY_LAST_OPENED_PREFIX + packageName, System.currentTimeMillis())
            .apply()
    }

    /** packageName -> number of times opened via the drawer. */
    fun getOpenCounts(context: Context): Map<String, Int> {
        val p = prefs(context)
        val result = mutableMapOf<String, Int>()
        for ((key, value) in p.all) {
            if (key.startsWith(KEY_OPEN_COUNT_PREFIX) && value is Int) {
                result[key.removePrefix(KEY_OPEN_COUNT_PREFIX)] = value
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
