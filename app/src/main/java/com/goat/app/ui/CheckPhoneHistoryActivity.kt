package com.goat.app.ui

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.goat.app.R
import com.goat.app.databinding.ActivityCheckPhoneHistoryBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors

class CheckPhoneHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCheckPhoneHistoryBinding

    private val bgExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val dateOptions = mutableListOf<DateOption>()
    private var selectedDateIndex = 0
    private var loadRequestId = 0

    // Raw (deduped-consecutive) foreground events fetched ONCE for the whole
    // visible date range. All date-chip filtering is then done in-memory
    // against this cache instead of re-querying UsageStatsManager every time.
    private var cachedRawEvents: List<Pair<String, Long>>? = null
    private var isFetchingHistory = false

    private data class DateOption(
        val label: String,
        val startMillis: Long,
        val endMillis: Long
    )

    companion object {
        private const val USAGE_ACCESS_ASSET_FOLDER = "Usage_Access_Permission"
        private const val USAGE_ACCESS_ASSET_FILE = "1.jpg"
        private const val DAYS_TO_SHOW = 7
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCheckPhoneHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnGrantPermission.setOnClickListener {
            openUsageAccessSettings()
        }

        binding.btnBack.setOnClickListener {
            goToLauncher()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                goToLauncher()
            }
        })

        binding.rvAppHistory.layoutManager = LinearLayoutManager(this)
        buildDateOptions()
    }

    override fun onResume() {
        super.onResume()
        refreshScreenForPermissionState()
    }

    override fun onDestroy() {
        bgExecutor.shutdown()
        super.onDestroy()
    }

    private fun refreshScreenForPermissionState() {
        if (hasUsageAccessPermission()) {
            showContentScreen()
        } else {
            showPermissionScreen()
        }
    }

    private fun showContentScreen() {
        binding.permissionLayer.visibility = View.GONE
        binding.contentLayer.visibility = View.VISIBLE
        renderDateChips()

        val cached = cachedRawEvents
        if (cached != null) {
            // Already fetched once before — just filter/re-render from cache.
            loadHistoryForSelectedDate()
        } else if (!isFetchingHistory) {
            fetchHistoryOnce()
        }
    }

    private fun showPermissionScreen() {
        binding.contentLayer.visibility = View.GONE
        binding.permissionLayer.visibility = View.VISIBLE
        loadUsageAccessScreenshot()
    }

    private fun loadUsageAccessScreenshot() {
        bgExecutor.execute {
            val bitmap: Bitmap? = try {
                assets.open("$USAGE_ACCESS_ASSET_FOLDER/$USAGE_ACCESS_ASSET_FILE").use { input ->
                    BitmapFactory.decodeStream(input)
                }
            } catch (e: Exception) {
                null
            }

            mainHandler.post {
                if (!isFinishing && !isDestroyed && bitmap != null) {
                    binding.ivUsageAccessScreenshot.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun hasUsageAccessPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun openUsageAccessSettings() {
        try {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } catch (e2: Exception) {
            }
        }
    }

    private fun goToLauncher() {
        val intent = Intent(this, LauncherHomeActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        startActivity(intent)
        finish()
    }

    // ---------- Date chips ----------

    private fun buildDateOptions() {
        dateOptions.clear()
        val calendar = Calendar.getInstance()

        for (i in 0 until DAYS_TO_SHOW) {
            calendar.timeInMillis = System.currentTimeMillis()
            calendar.add(Calendar.DAY_OF_YEAR, -i)

            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            val startMillis = calendar.timeInMillis

            val endMillis = when (i) {
                0 -> System.currentTimeMillis()
                else -> startMillis + (24L * 60 * 60 * 1000) - 1
            }

            val label = when (i) {
                0 -> getString(R.string.history_date_today)
                1 -> getString(R.string.history_date_yesterday)
                else -> SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(startMillis)
            }

            dateOptions.add(DateOption(label, startMillis, endMillis))
        }

        selectedDateIndex = 0
    }

    private fun renderDateChips() {
        val container = binding.dateChipContainer
        container.removeAllViews()

        val density = resources.displayMetrics.density
        val horizontalPaddingPx = (6 * density).toInt()
        val verticalPaddingPx = (9 * density).toInt()
        val marginEndPx = (4 * density).toInt()

        dateOptions.forEachIndexed { index, option ->
            val chip = TextView(this).apply {
                text = option.label
                textSize = 11.5f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(
                    if (index == selectedDateIndex)
                        resources.getColor(R.color.date_chip_selected_text, theme)
                    else
                        resources.getColor(R.color.date_chip_unselected_text, theme)
                )
                setTypeface(
                    typeface,
                    if (index == selectedDateIndex) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
                )
                setBackgroundResource(
                    if (index == selectedDateIndex) R.drawable.bg_date_chip_selected
                    else R.drawable.bg_date_chip_unselected
                )
                gravity = Gravity.CENTER
                setPadding(horizontalPaddingPx, verticalPaddingPx, horizontalPaddingPx, verticalPaddingPx)
                isClickable = true
                isFocusable = true
            }

            // Equal-weight width so all DAYS_TO_SHOW chips always fit the
            // screen in one row without needing horizontal scrolling.
            val params = android.widget.LinearLayout.LayoutParams(
                0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
            if (index != dateOptions.lastIndex) {
                params.marginEnd = marginEndPx
            }
            chip.layoutParams = params

            chip.setOnClickListener {
                if (selectedDateIndex != index) {
                    selectedDateIndex = index
                    renderDateChips()
                    loadHistoryForSelectedDate()
                }
            }

            container.addView(chip)
        }
    }

    // ---------- History list ----------

    /**
     * Fetches raw usage events ONCE for the entire visible date range
     * (oldest date option -> now), shows a loading spinner while this is
     * happening, and caches the result. After this, switching date chips
     * never re-queries UsageStatsManager — it only re-filters the cache.
     */
    private fun fetchHistoryOnce() {
        if (dateOptions.isEmpty() || isFetchingHistory) return
        isFetchingHistory = true

        val requestId = ++loadRequestId

        binding.rvAppHistory.adapter = AppHistoryAdapter(emptyList())
        binding.tvHistoryEmpty.visibility = View.GONE
        binding.tvHistorySummary.text = ""
        binding.pbHistoryLoading.visibility = View.VISIBLE
        updateSelectedDateHeader()

        val rangeStart = dateOptions.last().startMillis
        val rangeEnd = dateOptions.first().endMillis

        bgExecutor.execute {
            val rawEvents = queryRawForegroundEvents(rangeStart, rangeEnd)

            mainHandler.post {
                isFetchingHistory = false
                if (isFinishing || isDestroyed || requestId != loadRequestId) return@post

                cachedRawEvents = rawEvents
                binding.pbHistoryLoading.visibility = View.GONE
                loadHistoryForSelectedDate()
            }
        }
    }

    private fun updateSelectedDateHeader() {
        val option = dateOptions.getOrNull(selectedDateIndex) ?: return
        binding.tvSelectedDateHeader.text =
            SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(option.startMillis)
    }

    /** Filters the already-fetched cache for the selected date — no I/O, so no spinner needed. */
    private fun loadHistoryForSelectedDate() {
        val option = dateOptions.getOrNull(selectedDateIndex) ?: return
        val rawEvents = cachedRawEvents ?: return

        updateSelectedDateHeader()

        val entries = buildGroupedEntriesForRange(rawEvents, option.startMillis, option.endMillis)

        binding.rvAppHistory.adapter = AppHistoryAdapter(entries)
        binding.tvHistoryEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        binding.tvHistorySummary.text = if (entries.isEmpty()) {
            ""
        } else if (entries.size == 1) {
            getString(R.string.history_summary_format_singular, entries.size)
        } else {
            getString(R.string.history_summary_format_plural, entries.size)
        }
    }

    /**
     * Reads raw foreground-app events for the given time range and collapses
     * immediate consecutive repeats of the same app (e.g. quick focus
     * flicker) into single entries. Returns (packageName, timestamp) pairs
     * in chronological order.
     */
    private fun queryRawForegroundEvents(startMillis: Long, endMillis: Long): List<Pair<String, Long>> {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyList()

        val rawEvents = mutableListOf<Pair<String, Long>>()
        try {
            val events = usageStatsManager.queryEvents(startMillis, endMillis)
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val isForegroundEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
                } else {
                    @Suppress("DEPRECATION")
                    event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
                }
                if (isForegroundEvent && event.packageName != packageName) {
                    rawEvents.add(event.packageName to event.timeStamp)
                }
            }
        } catch (e: Exception) {
            return emptyList()
        }

        // Collapse immediate consecutive repeats of the same package.
        val collapsed = mutableListOf<Pair<String, Long>>()
        for (item in rawEvents) {
            val last = collapsed.lastOrNull()
            if (last == null || last.first != item.first) {
                collapsed.add(item)
            }
        }
        return collapsed
    }

    /**
     * Groups events for the given day by app (not just consecutive repeats)
     * so each app shows as a single row with its total open count for that
     * day, e.g. "Telegram - 3" instead of three separate rows. Rows are
     * ordered by the app's most recent open time (latest first). Resolves
     * each package into a display label + icon (no package names shown).
     */
    private fun buildGroupedEntriesForRange(
        events: List<Pair<String, Long>>,
        startMillis: Long,
        endMillis: Long
    ): List<AppHistoryEntry> {
        val inRange = events.filter { it.second in startMillis..endMillis }

        // pkg -> (openCount, mostRecentTimestamp)
        val grouped = LinkedHashMap<String, Pair<Int, Long>>()
        for ((pkg, timestamp) in inRange) {
            val existing = grouped[pkg]
            grouped[pkg] = if (existing == null) {
                1 to timestamp
            } else {
                (existing.first + 1) to maxOf(existing.second, timestamp)
            }
        }

        val pm = packageManager
        val timeFormatter = SimpleDateFormat("h:mm a", Locale.getDefault())

        return grouped.entries
            .sortedByDescending { it.value.second }
            .mapNotNull { (pkg, countAndTime) ->
                val (count, lastTimestamp) = countAndTime
                try {
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    val label = pm.getApplicationLabel(appInfo).toString()
                    val icon: Drawable = pm.getApplicationIcon(appInfo)
                    AppHistoryEntry(
                        label = label,
                        icon = icon,
                        timeText = timeFormatter.format(lastTimestamp),
                        openCount = count
                    )
                } catch (e: PackageManager.NameNotFoundException) {
                    null
                } catch (e: Exception) {
                    null
                }
            }
    }
}
