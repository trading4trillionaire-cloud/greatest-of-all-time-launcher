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

    private var allSevenDayEntries: List<RawHistoryEntry>? = null
    private var isFetchInProgress = false

    private data class DateOption(
        val label: String,
        val startMillis: Long,
        val endMillis: Long
    )

    private data class RawHistoryEntry(
        val label: String,
        val icon: Drawable,
        val timestamp: Long,
        val timeText: String
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

        val cached = allSevenDayEntries
        if (cached == null) {
            fetchAllHistoryOnce()
        } else {
            applyFilterForSelectedDate(cached)
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

    private val dateChipViews = mutableListOf<TextView>()

    private fun renderDateChips() {
        val container = binding.dateChipContainer

        if (dateChipViews.isNotEmpty()) {

            dateChipViews.forEachIndexed { index, chip ->
                styleChip(chip, isSelected = index == selectedDateIndex)
            }
            return
        }

        container.removeAllViews()
        dateChipViews.clear()

        val density = resources.displayMetrics.density
        val horizontalPaddingPx = (16 * density).toInt()
        val verticalPaddingPx = (9 * density).toInt()
        val marginEndPx = (8 * density).toInt()

        dateOptions.forEachIndexed { index, option ->
            val chip = TextView(this).apply {
                text = option.label
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(horizontalPaddingPx, verticalPaddingPx, horizontalPaddingPx, verticalPaddingPx)
                isClickable = true
                isFocusable = true
            }
            styleChip(chip, isSelected = index == selectedDateIndex)

            val params = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.marginEnd = marginEndPx
            chip.layoutParams = params

            chip.setOnClickListener {
                if (selectedDateIndex != index) {
                    val previousIndex = selectedDateIndex
                    selectedDateIndex = index
                    styleChip(dateChipViews[previousIndex], isSelected = false)
                    styleChip(dateChipViews[index], isSelected = true)

                    val cached = allSevenDayEntries
                    if (cached != null) {
                        applyFilterForSelectedDate(cached)
                    }

                }
            }

            dateChipViews.add(chip)
            container.addView(chip)
        }
    }

    private fun styleChip(chip: TextView, isSelected: Boolean) {
        chip.setTextColor(
            resources.getColor(
                if (isSelected) R.color.date_chip_selected_text else R.color.date_chip_unselected_text,
                theme
            )
        )
        chip.setTypeface(
            chip.typeface,
            if (isSelected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
        )
        chip.setBackgroundResource(
            if (isSelected) R.drawable.bg_date_chip_selected else R.drawable.bg_date_chip_unselected
        )
    }

    private fun fetchAllHistoryOnce() {
        if (isFetchInProgress) return
        val oldestOption = dateOptions.lastOrNull() ?: return
        val newestOption = dateOptions.firstOrNull() ?: return

        isFetchInProgress = true
        val requestId = ++loadRequestId

        binding.progressHistoryLoading.visibility = View.VISIBLE
        binding.rvAppHistory.adapter = AppHistoryAdapter(emptyList())
        binding.tvHistoryEmpty.visibility = View.GONE
        binding.tvHistorySummary.text = ""
        binding.tvSelectedDateHeader.text = ""

        bgExecutor.execute {
            val entries = queryCollapsedHistory(oldestOption.startMillis, newestOption.endMillis)

            mainHandler.post {
                isFetchInProgress = false
                if (isFinishing || isDestroyed || requestId != loadRequestId) return@post

                binding.progressHistoryLoading.visibility = View.GONE
                allSevenDayEntries = entries
                applyFilterForSelectedDate(entries)
            }
        }
    }

    private fun applyFilterForSelectedDate(cached: List<RawHistoryEntry>) {
        val option = dateOptions.getOrNull(selectedDateIndex) ?: return

        binding.tvSelectedDateHeader.text =
            SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(option.startMillis)

        val entries = cached
            .filter { it.timestamp in option.startMillis..option.endMillis }
            .sortedByDescending { it.timestamp }
            .map { AppHistoryEntry(label = it.label, icon = it.icon, timeText = it.timeText) }

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

    private fun queryCollapsedHistory(startMillis: Long, endMillis: Long): List<RawHistoryEntry> {
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

        val pm = packageManager
        val timeFormatter = SimpleDateFormat("h:mm a", Locale.getDefault())

        data class Resolved(val pkg: String, val label: String, val icon: Drawable, val timestamp: Long)

        val resolved = rawEvents.mapNotNull { (pkg, timestamp) ->
            try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                Resolved(
                    pkg = pkg,
                    label = pm.getApplicationLabel(appInfo).toString(),
                    icon = pm.getApplicationIcon(appInfo),
                    timestamp = timestamp
                )
            } catch (e: PackageManager.NameNotFoundException) {
                null
            } catch (e: Exception) {
                null
            }
        }

        val collapsed = mutableListOf<Resolved>()
        for (item in resolved) {
            val last = collapsed.lastOrNull()
            if (last == null || last.pkg != item.pkg) {
                collapsed.add(item)
            }

        }

        return collapsed.map { entry ->
            RawHistoryEntry(
                label = entry.label,
                icon = entry.icon,
                timestamp = entry.timestamp,
                timeText = timeFormatter.format(entry.timestamp)
            )
        }
    }
}
