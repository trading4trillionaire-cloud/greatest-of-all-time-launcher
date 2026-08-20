package com.goat.app.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ResolveInfo
import android.os.BatteryManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.goat.app.R
import com.goat.app.data.AppUsageStore
import com.goat.app.databinding.ActivityLauncherHomeBinding
import java.util.concurrent.Executors
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions

class LauncherHomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLauncherHomeBinding
    private var isDrawerOpen = false

    private lateinit var drawerIconSizing: DrawerIconSizing

    private var hintAnimator: ObjectAnimator? = null
    private var snapAnimator: ValueAnimator? = null

    private var drawerHeightPx = 0f
    private var currentProgress = 0f

    private var isDraggingOpen = false
    private var dragStartRawY = 0f

    private val appLoadExecutor = Executors.newSingleThreadExecutor()
    private val revealHandler = Handler(Looper.getMainLooper())
    private var pendingReveal: Runnable? = null

    private var packageChangeReceiver: BroadcastReceiver? = null

    private var chargingReceiver: BroadcastReceiver? = null
    private var isChargingUiVisible = false

    companion object {

        private var cachedExpandPanelMethod: java.lang.reflect.Method? = null

        private var cachedApps: List<AppEntry>? = null

        private var cachedAd: NativeAd? = null
        private var loadTime: Long = 0L
        private var lastImpressionTime: Long = 0L
        private var displayedAd: NativeAd? = null
        private var isAdFetchInFlight = false

        private const val SWIPE_DOWN_MIN_DISTANCE_PX = 40
        private const val DRAWER_SNAP_DURATION_MS = 220L
        private const val HINT_BOUNCE_DISTANCE_PX = 10f
        private const val HINT_BOUNCE_DURATION_MS = 650L

        private const val MIN_COLUMN_COUNT = 4
        private const val MAX_COLUMN_COUNT = 5
        private const val VISIBLE_ROW_COUNT = 5

        // Max number of grid rows to show in each app-drawer section. Change these
        // freely to tweak how many apps show up under each heading — the actual item
        // count per section is (current column/span count * these row limits).
        private const val RECOMMENDED_MAX_ROWS = 2
        private const val RECENT_MAX_ROWS = 2
        private const val RECOMMENDED_REVEAL_DELAY_MS = 150L
        private const val OPEN_PROGRESS_THRESHOLD = 0.08f
        private const val CLOSE_PROGRESS_THRESHOLD = 0.08f
        private const val HOME_MIN_ALPHA = 0.1f
        private const val DRAG_TOUCH_SLOP_PX = 8f
        private const val ICON_TARGET_SIZE_DP = 48
        private const val BASE_LABEL_TEXT_SIZE_SP = 12f

        private const val TARGET_GAP_FRACTION = 0.20f

        private const val MAX_ICON_SIZE_DP = 88
        private const val MAX_LABEL_TEXT_SIZE_SP = 20f

        private const val ITEM_HORIZONTAL_PADDING_DP = 8f

        private val drawerIconSizingCache = mutableMapOf<Int, DrawerIconSizing>()

        private const val NATIVE_AD_UNIT_ID = "ca-app-pub-3940256099942544/2247696110"

        private const val IMPRESSION_COOLDOWN_MS = 15_000L

        private const val CACHE_EXPIRY_MS = 45 * 60 * 1000L

        private fun clearAllCachedState() {
            cachedExpandPanelMethod = null
            cachedApps = null
            drawerIconSizingCache.clear()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLauncherHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isDrawerOpen) closeDrawer()
            }
        })

        drawerIconSizing = resolveDrawerIconSizing()

        setupGestures()
        setupDrawer()
        setupSwipeUpHint()
        setupFixIssueButton()
        setupCheckWhatsappButton()
        setupCheckCallButton()
        setupCheckRiskyPermissionsButton()
        setupCheckPhoneHistoryButton()
        setupFeaturesNav()
        setupSwipeThroughForClickableCards()
        refreshWhatsappGuideStatus()

        val cached = cachedApps
        if (cached != null) {
            binding.rvApps.adapter =
                AppListAdapter(buildDrawerListItems(cached), drawerIconSizing) { app -> launchApp(app) }
        } else {
            loadAppsAsync()
        }

        registerPackageChangeReceiver()

        MobileAds.initialize(this) {

            fetchNativeAd { ad ->
                if (ad != null) {
                    cachedAd = ad
                    loadTime = System.currentTimeMillis()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()

        hintAnimator?.let { if (it.isPaused) it.resume() }

        refreshWhatsappGuideStatus()
        registerChargingReceiver()
        if (isChargingUiVisible) {
            binding.chargingRing.startGlowAnimation()
        }

        val now = System.currentTimeMillis()
        if (cachedAd != null && (now - loadTime) > CACHE_EXPIRY_MS) {
            cachedAd?.destroy()
            cachedAd = null
            fetchNativeAd { ad ->
                if (ad != null) {
                    cachedAd = ad
                    loadTime = System.currentTimeMillis()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()

        unregisterChargingReceiver()
        binding.chargingRing.pauseGlowAnimation()

        pendingReveal?.let { revealHandler.removeCallbacks(it) }

        if (currentProgress > 0f && currentProgress < 1f) {
            snapAnimator?.cancel()
            val finalProgress = if (isDrawerOpen) 1f else 0f
            applyProgress(finalProgress)
            if (isDrawerOpen) {
                binding.homeLayer.visibility = View.INVISIBLE
            } else {
                binding.drawerLayer.visibility = View.INVISIBLE
                binding.homeLayer.visibility = View.VISIBLE
            }
        }

        hintAnimator?.pause()
    }

    private fun registerChargingReceiver() {
        if (chargingReceiver != null) return

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                applyBatteryState(intent)
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }

        // registerReceiver with ACTION_BATTERY_CHANGED in the filter returns the last
        // sticky broadcast immediately, so we get the current charging state/level
        // right away without polling anything.
        val stickyIntent = registerReceiver(receiver, filter)
        chargingReceiver = receiver

        if (stickyIntent != null) {
            applyBatteryState(stickyIntent)
        }
    }

    private fun unregisterChargingReceiver() {
        chargingReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                // already unregistered, ignore
            }
        }
        chargingReceiver = null
    }

    private fun applyBatteryState(intent: Intent) {
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val isCharging = plugged != 0

        if (isCharging) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val percent = if (level >= 0 && scale > 0) (level * 100) / scale else -1

            if (percent >= 0) {
                binding.tvChargingPercent.text = getString(R.string.charging_percent_format, percent)
                binding.chargingRing.setPercent(percent)
            }
            showChargingUi()
        } else {
            hideChargingUi()
        }
    }

    private fun showChargingUi() {
        if (isChargingUiVisible) return
        isChargingUiVisible = true
        binding.whatsappCheckBlock.visibility = View.GONE
        binding.chargingBlock.visibility = View.VISIBLE
        binding.chargingRing.startGlowAnimation()
    }

    private fun hideChargingUi() {
        if (!isChargingUiVisible) return
        isChargingUiVisible = false
        binding.chargingRing.stopGlowAnimation()
        binding.chargingBlock.visibility = View.GONE
        binding.whatsappCheckBlock.visibility = View.VISIBLE
    }

    private fun setupSwipeUpHint() {
        hintAnimator = ObjectAnimator.ofFloat(
            binding.swipeUpHint,
            View.TRANSLATION_Y,
            0f, -HINT_BOUNCE_DISTANCE_PX, 0f
        ).apply {
            duration = HINT_BOUNCE_DURATION_MS
            repeatCount = ValueAnimator.INFINITE
            startDelay = 400L
            start()
        }
    }

    override fun onDestroy() {
        hintAnimator?.cancel()
        snapAnimator?.cancel()
        pendingReveal?.let { revealHandler.removeCallbacks(it) }
        packageChangeReceiver?.let { unregisterReceiver(it) }
        packageChangeReceiver = null
        appLoadExecutor.shutdown()
        super.onDestroy()
    }

    private fun registerPackageChangeReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                loadAppsAsync()
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        registerReceiver(receiver, filter)
        packageChangeReceiver = receiver
    }

    private fun setupGestures() {
        binding.homeLayer.setOnTouchListener { _, event -> handleOpenDrag(event) }
        setupListSwipeDownToClose()
    }

    private fun handleOpenDrag(event: MotionEvent): Boolean {
        if (isDrawerOpen) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartRawY = event.rawY
                isDraggingOpen = false
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaY = dragStartRawY - event.rawY
                if (!isDraggingOpen && deltaY > DRAG_TOUCH_SLOP_PX) {
                    isDraggingOpen = true
                    snapAnimator?.cancel()
                    binding.drawerLayer.visibility = View.VISIBLE
                }
                if (isDraggingOpen) {
                    val progress = if (drawerHeightPx > 0f) (deltaY / drawerHeightPx) else 0f
                    applyProgress(progress.coerceIn(0f, 1f))
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDraggingOpen) {
                    isDraggingOpen = false
                    snapToNearest()
                    return true
                } else {
                    val downDeltaY = event.rawY - dragStartRawY
                    if (downDeltaY > SWIPE_DOWN_MIN_DISTANCE_PX) {
                        expandNotificationPanel()
                    }
                }
            }
        }
        return true
    }

    @SuppressLint("WrongConstant", "PrivateApi")
    private fun expandNotificationPanel() {
        try {
            val statusBarService = getSystemService("statusbar")
            val method = cachedExpandPanelMethod ?: Class.forName("android.app.StatusBarManager")
                .getMethod("expandNotificationsPanel")
                .also { cachedExpandPanelMethod = it }
            method.invoke(statusBarService)
        } catch (e: Exception) {
        }
    }

    private fun applyProgress(progress: Float) {
        currentProgress = progress
        if (progress > 0f) {
            binding.drawerLayer.visibility = View.VISIBLE
        }
        binding.drawerLayer.translationY = drawerHeightPx * (1f - progress)
        binding.drawerLayer.alpha = progress
        binding.homeLayer.alpha = 1f - progress * (1f - HOME_MIN_ALPHA)
        binding.swipeUpHint.alpha = 1f - progress
    }

    private fun snapToNearest() {
        val targetOpen = if (isDrawerOpen) {
            currentProgress >= (1f - CLOSE_PROGRESS_THRESHOLD)
        } else {
            currentProgress >= OPEN_PROGRESS_THRESHOLD
        }
        animateToProgress(if (targetOpen) 1f else 0f, targetOpen)
    }

    private fun animateToProgress(target: Float, endOpen: Boolean) {
        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofFloat(currentProgress, target).apply {
            duration = DRAWER_SNAP_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { applyProgress(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    val wasOpen = isDrawerOpen
                    isDrawerOpen = endOpen
                    if (!endOpen) {
                        binding.drawerLayer.visibility = View.INVISIBLE

                        binding.homeLayer.visibility = View.VISIBLE
                    } else {

                        binding.homeLayer.visibility = View.INVISIBLE
                        if (!wasOpen) {

                            onDrawerOpened()
                        }
                    }
                }
            })
            start()
        }
    }

    private fun setupListSwipeDownToClose() {
        var dragStartRawY = 0f
        var dragging = false

        binding.rvApps.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                if (!isDrawerOpen) return false
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        dragStartRawY = e.rawY
                        dragging = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val listAtTop = !rv.canScrollVertically(-1)
                        val deltaY = e.rawY - dragStartRawY
                        if (listAtTop && !dragging && deltaY > DRAG_TOUCH_SLOP_PX) {
                            dragging = true
                            snapAnimator?.cancel()
                            rv.stopScroll()
                            rv.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        if (dragging) return true
                    }
                }
                return false
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                when (e.actionMasked) {
                    MotionEvent.ACTION_MOVE -> {
                        if (dragging) {
                            val deltaY = e.rawY - dragStartRawY
                            val progress = if (drawerHeightPx > 0f) 1f - (deltaY / drawerHeightPx) else 1f
                            applyProgress(progress.coerceIn(0f, 1f))
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (dragging) {
                            dragging = false
                            snapToNearest()
                        }
                    }
                }
            }

            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
        })
    }

    private var rowSpacingPx = 0
    private val rowSpacingDecoration = object : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: android.graphics.Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {

            val position = parent.getChildAdapterPosition(view)
            val itemCount = parent.adapter?.itemCount ?: 0
            val adapter = parent.adapter as? AppListAdapter

            if (position == RecyclerView.NO_POSITION || adapter == null) {
                outRect.bottom = 0
                return
            }

            // Only app rows get spacing; headers carry their own margins, and we never
            // want a gap right before the next section's header or after the last item.
            val isApp = adapter.getItemViewType(position) == AppListAdapter.VIEW_TYPE_APP
            val isLastItem = position == itemCount - 1
            val nextIsHeader = !isLastItem &&
                adapter.getItemViewType(position + 1) == AppListAdapter.VIEW_TYPE_HEADER

            outRect.bottom = if (isApp && !isLastItem && !nextIsHeader) rowSpacingPx else 0
        }
    }

    private fun setupDrawer() {

        val spanCount = calculateSpanCount()
        val gridLayoutManager = GridLayoutManager(this, spanCount).apply {
            isItemPrefetchEnabled = true
            initialPrefetchItemCount = spanCount * 2
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    val adapter = binding.rvApps.adapter as? AppListAdapter ?: return 1
                    return if (adapter.getItemViewType(position) == AppListAdapter.VIEW_TYPE_HEADER) {
                        spanCount
                    } else {
                        1
                    }
                }
            }
        }
        binding.rvApps.layoutManager = gridLayoutManager
        binding.rvApps.overScrollMode = View.OVER_SCROLL_NEVER
        binding.rvApps.setHasFixedSize(true)
        binding.rvApps.setItemViewCacheSize(24)
        binding.rvApps.addItemDecoration(rowSpacingDecoration)

        binding.rvApps.recycledViewPool.setMaxRecycledViews(0, calculateRecyclePoolSize(spanCount))
        (binding.rvApps.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        binding.rvApps.itemAnimator?.apply {
            addDuration = 120L
            removeDuration = 0L
            changeDuration = 0L
            moveDuration = 0L
        }
        binding.rvApps.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        binding.drawerLayer.visibility = View.INVISIBLE

        binding.rvApps.viewTreeObserver.addOnGlobalLayoutListener(object :
            ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val rv = binding.rvApps
                if (rv.childCount > 0 && rv.height > 0) {
                    var naturalRowHeightPx = 0
                    for (i in 0 until rv.childCount) {
                        val child = rv.getChildAt(i)
                        val pos = rv.getChildAdapterPosition(child)
                        val adapter = rv.adapter as? AppListAdapter
                        if (pos != RecyclerView.NO_POSITION &&
                            adapter?.getItemViewType(pos) == AppListAdapter.VIEW_TYPE_APP
                        ) {
                            naturalRowHeightPx = child.height
                            break
                        }
                    }
                    if (naturalRowHeightPx > 0) {
                        val targetRowHeightPx = rv.height / VISIBLE_ROW_COUNT
                        val newSpacing = maxOf(0, targetRowHeightPx - naturalRowHeightPx)
                        if (newSpacing != rowSpacingPx) {
                            rowSpacingPx = newSpacing
                            rv.invalidateItemDecorations()
                        }
                        rv.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    }
                }
            }
        })

        binding.drawerLayer.post {
            drawerHeightPx = binding.drawerLayer.height.toFloat()
            binding.drawerLayer.translationY = drawerHeightPx
            binding.drawerLayer.alpha = 0f
        }
    }

    private fun calculateSpanCount(): Int {
        val density = resources.displayMetrics.density
        val screenWidthDp = resources.displayMetrics.widthPixels / density
        val idealColumnWidthDp = 80f
        val computed = (screenWidthDp / idealColumnWidthDp).toInt()
        return computed.coerceIn(MIN_COLUMN_COUNT, MAX_COLUMN_COUNT)
    }

    private fun resolveDrawerIconSizing(): DrawerIconSizing {
        val spanCount = calculateSpanCount()
        val screenWidthPx = resources.displayMetrics.widthPixels
        val columnWidthPx = screenWidthPx / spanCount

        drawerIconSizingCache[columnWidthPx]?.let { return it }

        val density = resources.displayMetrics.density
        val baseIconSizePx = (ICON_TARGET_SIZE_DP * density).toInt()
        val itemPaddingPx = (ITEM_HORIZONTAL_PADDING_DP * density)

        val availableWidthPx = (columnWidthPx - itemPaddingPx)
            .coerceAtLeast(baseIconSizePx.toFloat())

        val idealIconSizePx = (availableWidthPx * (1f - TARGET_GAP_FRACTION)).toInt()
        val maxIconSizePx = (MAX_ICON_SIZE_DP * density).toInt()
        val resolvedIconSizePx = idealIconSizePx.coerceIn(baseIconSizePx, maxIconSizePx)

        val scale = resolvedIconSizePx.toFloat() / baseIconSizePx.toFloat()
        val resolvedTextSizeSp = (BASE_LABEL_TEXT_SIZE_SP * scale)
            .coerceIn(BASE_LABEL_TEXT_SIZE_SP, MAX_LABEL_TEXT_SIZE_SP)

        val sizing = DrawerIconSizing(resolvedIconSizePx, resolvedTextSizeSp)
        drawerIconSizingCache[columnWidthPx] = sizing
        return sizing
    }

    private fun calculateRecyclePoolSize(spanCount: Int): Int {
        return spanCount * (VISIBLE_ROW_COUNT + 2)
    }

    private fun setupFixIssueButton() {
        binding.btnFixIssue.setOnClickListener {
            performFreshRestart()
        }
    }

    private fun setupCheckPhoneHistoryButton() {
        binding.btnCheckPhoneHistory.setOnClickListener {
            startActivity(Intent(this, CheckPhoneHistoryActivity::class.java))
        }
    }

    private fun setupSwipeThroughForClickableCards() {
        val swipeThroughViews = listOf(
            binding.whatsappGuideCard,
            binding.btnCheckWhatsappNow,
            binding.callGuideCard,
            binding.btnCheckCallNow,
            binding.riskyPermissionsGuideCard,
            binding.btnCheckRiskyPermissionsNow,
            binding.btnCheckPhoneHistory,
            binding.btnFeaturesLeft,
            binding.btnFeaturesRight
        )
        swipeThroughViews.forEach { view ->
            view.setOnTouchListener { v, event -> handleSwipeThroughTouch(v, event) }
        }
    }

    private fun handleSwipeThroughTouch(view: View, event: MotionEvent): Boolean {
        if (isDrawerOpen) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartRawY = event.rawY
                isDraggingOpen = false

                return false
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaY = dragStartRawY - event.rawY
                if (!isDraggingOpen && deltaY > DRAG_TOUCH_SLOP_PX) {
                    isDraggingOpen = true
                    snapAnimator?.cancel()
                    binding.drawerLayer.visibility = View.VISIBLE

                    view.isPressed = false
                    val cancelEvent = MotionEvent.obtain(event)
                    cancelEvent.action = MotionEvent.ACTION_CANCEL
                    view.onTouchEvent(cancelEvent)
                    cancelEvent.recycle()
                }
                if (isDraggingOpen) {
                    val progress = if (drawerHeightPx > 0f) (deltaY / drawerHeightPx) else 0f
                    applyProgress(progress.coerceIn(0f, 1f))
                    return true
                }
                return false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDraggingOpen) {
                    isDraggingOpen = false
                    snapToNearest()
                    return true
                }
                return false
            }
        }
        return false
    }

    private fun setupCheckWhatsappButton() {
        val openGuide = View.OnClickListener {

            startActivity(Intent(this, UnlockedContentActivity::class.java))
        }

        binding.btnCheckWhatsappNow.setOnClickListener(openGuide)
        binding.whatsappGuideCard.setOnClickListener(openGuide)
    }

    private fun setupCheckCallButton() {
        val openCallGuide = View.OnClickListener {
            startActivity(Intent(this, CallSafetyContentActivity::class.java))
        }

        binding.btnCheckCallNow.setOnClickListener(openCallGuide)
        binding.callGuideCard.setOnClickListener(openCallGuide)
    }

    private fun setupCheckRiskyPermissionsButton() {
        val openRiskyPermissionsGuide = View.OnClickListener {
            startActivity(Intent(this, RiskyPermissionsActivity::class.java))
        }

        binding.btnCheckRiskyPermissionsNow.setOnClickListener(openRiskyPermissionsGuide)
        binding.riskyPermissionsGuideCard.setOnClickListener(openRiskyPermissionsGuide)
    }

    private var currentFeatureCardIndex = 0

    private fun setupFeaturesNav() {
        val featureCards = listOf(binding.whatsappGuideCard, binding.callGuideCard, binding.riskyPermissionsGuideCard)

        fun showCard(index: Int) {
            currentFeatureCardIndex = index
            featureCards.forEachIndexed { i, card ->
                card.visibility = if (i == index) View.VISIBLE else View.GONE
            }
        }

        binding.btnFeaturesLeft.setOnClickListener {
            val newIndex = if (currentFeatureCardIndex == 0) featureCards.size - 1 else currentFeatureCardIndex - 1
            showCard(newIndex)
        }

        binding.btnFeaturesRight.setOnClickListener {
            val newIndex = (currentFeatureCardIndex + 1) % featureCards.size
            showCard(newIndex)
        }

        showCard(currentFeatureCardIndex)
    }

    private fun refreshWhatsappGuideStatus() {
        binding.tvWhatsappCheckSubtitle.text = getString(R.string.whatsapp_guide_status_free_access)
        binding.btnCheckWhatsappNow.text = getString(R.string.whatsapp_guide_button_view)
    }

    private fun performFreshRestart() {
        clearAllCachedState()

        val allPrefsDir = java.io.File(applicationInfo.dataDir, "shared_prefs")
        allPrefsDir.listFiles()?.forEach { prefFile ->
            val prefName = prefFile.name.removeSuffix(".xml")
            // Recommended/Recent app-drawer usage data must survive "Fix Issues".
            if (prefName == AppUsageStore.PREFS_FILE_NAME) return@forEach
            getSharedPreferences(prefName, Context.MODE_PRIVATE).edit().clear().commit()
        }

        val intent = Intent(this, LauncherHomeActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TOP
        startActivity(intent)
        finish()
    }

    private fun closeDrawer() {
        if (!isDrawerOpen) return
        animateToProgress(0f, false)
        // Reset scroll so the drawer always reopens at the top -- this keeps the
        // Recommended/Recent shimmer placeholder (which is sized/positioned assuming
        // the list starts at position 0) perfectly aligned with the real content.
        binding.rvApps.scrollToPosition(0)
    }

    private fun loadAppsAsync() {

        appLoadExecutor.execute {
            val pm = packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved: List<ResolveInfo> = try {
                pm.queryIntentActivities(intent, 0)
            } catch (e: Exception) {
                emptyList()
            }

            val iconSizePx = drawerIconSizing.iconSizePx

            val reusableCanvas = Canvas()

            val apps = resolved
                .filter { it.activityInfo.packageName != packageName }
                .map {
                    AppEntry(
                        label = it.loadLabel(pm).toString(),
                        packageName = it.activityInfo.packageName,
                        className = it.activityInfo.name,
                        icon = toFixedSizeDrawable(it.loadIcon(pm), iconSizePx, reusableCanvas)
                    )
                }
                .sortedBy { it.label.lowercase() }

            cachedApps = apps

            val displayItems = buildDrawerListItems(apps)

            Handler(Looper.getMainLooper()).post {
                if (!isFinishing) {
                    binding.rvApps.adapter =
                        AppListAdapter(displayItems, drawerIconSizing) { app -> launchApp(app) }
                }
            }
        }
    }

    /**
     * Called every time the drawer opens. Immediately collapses (hides) the Recommended
     * and Recent sections -- only "All Apps" shows right away -- then rebuilds those two
     * sections with fresh usage data in the background and reveals them the moment
     * they're stable. This is what prevents an accidental tap: since Recommended/Recent
     * simply aren't on screen yet while they'd otherwise be reordering, there's nothing
     * there for a tap to land on by mistake. They pop back in only once settled.
     */
    private fun showDrawerRefreshLoading() {
        pendingReveal?.let { revealHandler.removeCallbacks(it) }

        val apps = cachedApps ?: return
        val existingAdapter = binding.rvApps.adapter as? AppListAdapter

        if (existingAdapter == null) {
            // First-ever load: nothing on screen yet to collapse, so just show the
            // freshly built list directly.
            refreshDrawerUsageSections()
            binding.rvApps.scrollToPosition(0)
            return
        }

        // Step 1: collapse Recommended/Recent right away (All Apps stays visible).
        existingAdapter.updateItems(buildAllAppsOnlyItems(apps))

        // Step 2: rebuild Recommended/Recent with up-to-date usage data and reveal them
        // once they've had a moment to settle. Also auto-scroll back to the top so the
        // user always lands right on the newly revealed Recommended/Recent apps instead
        // of having to scroll up manually every time.
        val revealRunnable = Runnable {
            refreshDrawerUsageSections()
            binding.rvApps.scrollToPosition(0)
        }
        pendingReveal = revealRunnable
        revealHandler.postDelayed(revealRunnable, RECOMMENDED_REVEAL_DELAY_MS)
    }

    /** Just the "All Apps" section -- used to collapse Recommended/Recent while they refresh. */
    private fun buildAllAppsOnlyItems(allApps: List<AppEntry>): List<DrawerListItem> {
        val items = mutableListOf<DrawerListItem>()
        items += DrawerListItem.Header(getString(R.string.drawer_section_all_apps))
        items += allApps.map { DrawerListItem.AppItem(it) }
        return items
    }

    /**
     * Rebuilds just the Recommended/Recent/All-apps sections from the currently cached
     * app list and the latest usage stats, and swaps the adapter in. Cheap (no package
     * manager re-query), so this is safe to call every time the drawer is opened, which
     * keeps Recommended/Recent apps in sync with usage immediately -- without requiring
     * the user to hit "Fix Issues" to see updated counts.
     */
    private fun refreshDrawerUsageSections() {
        val apps = cachedApps ?: return
        val displayItems = buildDrawerListItems(apps)

        val existingAdapter = binding.rvApps.adapter as? AppListAdapter
        if (existingAdapter != null) {
            // Diff against the existing list instead of swapping the adapter, so only
            // rows that actually changed get redrawn -- no more flicker on drawer open.
            existingAdapter.updateItems(displayItems)
        } else {
            binding.rvApps.adapter =
                AppListAdapter(displayItems, drawerIconSizing) { app -> launchApp(app) }
        }
    }

    /**
     * Builds the sectioned app-drawer list: Recommended apps (most opened, capped at
     * RECOMMENDED_MAX_ROWS rows) -> Recent Apps (recently opened apps not already shown
     * under Recommended, capped at RECENT_MAX_ROWS rows) -> All Apps (full alphabetical
     * list, unlimited, same as before). Apps that don't fit within a section's row limit
     * are simply dropped from that section, never overflowed.
     *
     * Usage data (open counts / last-opened timestamps) comes from AppUsageStore, which
     * is persisted separately from other app prefs so it survives the "Fix Issues" reset.
     */
    private fun buildDrawerListItems(allApps: List<AppEntry>): List<DrawerListItem> {
        val spanCount = calculateSpanCount()

        val openCounts = AppUsageStore.getOpenCounts(this)
        val lastOpened = AppUsageStore.getLastOpenedTimestamps(this)
        val appsByPackage = allApps.associateBy { it.packageName }

        val recommendedLimit = spanCount * RECOMMENDED_MAX_ROWS
        val recommended = openCounts.entries
            .filter { it.value > 0 }
            .sortedWith(
                compareByDescending<Map.Entry<String, Float>> { it.value }
                    .thenBy { appsByPackage[it.key]?.label?.lowercase() ?: "" }
            )
            .mapNotNull { appsByPackage[it.key] }
            .take(recommendedLimit)

        val recommendedPackages = recommended.map { it.packageName }.toSet()

        val recentLimit = spanCount * RECENT_MAX_ROWS
        val recent = lastOpened.entries
            .filter { it.key !in recommendedPackages }
            .sortedByDescending { it.value }
            .mapNotNull { appsByPackage[it.key] }
            .take(recentLimit)

        val items = mutableListOf<DrawerListItem>()

        if (recommended.isNotEmpty()) {
            items += DrawerListItem.Header(getString(R.string.drawer_section_recommended))
            items += recommended.map { DrawerListItem.AppItem(it) }
        }

        if (recent.isNotEmpty()) {
            items += DrawerListItem.Header(getString(R.string.drawer_section_recent))
            items += recent.map { DrawerListItem.AppItem(it) }
        }

        items += DrawerListItem.Header(getString(R.string.drawer_section_all_apps))
        items += allApps.map { DrawerListItem.AppItem(it) }

        return items
    }

    private fun toFixedSizeDrawable(source: Drawable, targetSizePx: Int, reusableCanvas: Canvas): Drawable {
        return try {

            if (source is BitmapDrawable &&
                source.bitmap.width == targetSizePx &&
                source.bitmap.height == targetSizePx
            ) {
                return source
            }

            val bitmap = Bitmap.createBitmap(targetSizePx, targetSizePx, Bitmap.Config.ARGB_8888)
            reusableCanvas.setBitmap(bitmap)
            source.setBounds(0, 0, targetSizePx, targetSizePx)
            source.draw(reusableCanvas)
            reusableCanvas.setBitmap(null)
            BitmapDrawable(resources, bitmap)
        } catch (e: Exception) {
            source
        }
    }

    private fun launchApp(app: AppEntry) {
        val launchIntent = Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(app.packageName, app.className)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            startActivity(launchIntent)
            AppUsageStore.recordAppOpened(this, app.packageName)
            closeDrawer()
        } catch (e: Exception) {
            Toast.makeText(this, "${app.label} nahi khul paya", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onDrawerOpened() {
        showDrawerRefreshLoading()

        val now = System.currentTimeMillis()

        if (now - lastImpressionTime < IMPRESSION_COOLDOWN_MS) {
            return
        }

        val cached = cachedAd
        val cacheAge = now - loadTime

        if (cached != null && cacheAge < CACHE_EXPIRY_MS) {

            showAd(cached)
            lastImpressionTime = now
            cachedAd = null

            fetchNativeAd { ad ->
                if (ad != null) {
                    cachedAd = ad
                    loadTime = System.currentTimeMillis()
                }
            }
        } else {

            showShimmerLoader()
            fetchNativeAd { ad ->
                hideShimmerLoader()
                if (ad != null) {
                    showAd(ad)
                    lastImpressionTime = System.currentTimeMillis()

                    fetchNativeAd { next ->
                        if (next != null) {
                            cachedAd = next
                            loadTime = System.currentTimeMillis()
                        }
                    }
                } else {
                    showNoSponsorship()
                }
            }
        }
    }

    private fun fetchNativeAd(onResult: (NativeAd?) -> Unit) {
        if (isAdFetchInFlight) {
            onResult(null)
            return
        }
        isAdFetchInFlight = true
        val adLoader = AdLoader.Builder(this, NATIVE_AD_UNIT_ID)
            .forNativeAd { nativeAd ->
                isAdFetchInFlight = false
                if (isFinishing || isDestroyed) {
                    nativeAd.destroy()
                    onResult(null)
                    return@forNativeAd
                }
                onResult(nativeAd)
            }
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(error: LoadAdError) {
                    isAdFetchInFlight = false
                    onResult(null)
                }
            })
            .withNativeAdOptions(
                NativeAdOptions.Builder()
                    .setAdChoicesPlacement(NativeAdOptions.ADCHOICES_TOP_RIGHT)
                    .build()
            )
            .build()
        adLoader.loadAd(AdRequest.Builder().build())
    }

    private fun showAd(nativeAd: NativeAd) {
        displayedAd?.destroy()
        displayedAd = nativeAd
        populateNativeAdView(nativeAd)
        binding.adShimmerLoader.visibility = View.GONE
        binding.tvNoSponsorship.visibility = View.GONE
        binding.nativeAdContainer.root.visibility = View.VISIBLE
    }

    private fun showShimmerLoader() {
        binding.nativeAdContainer.root.visibility = View.INVISIBLE
        binding.tvNoSponsorship.visibility = View.GONE
        binding.adShimmerLoader.visibility = View.VISIBLE
    }

    private fun hideShimmerLoader() {
        binding.adShimmerLoader.visibility = View.GONE
    }

    private fun showNoSponsorship() {
        binding.nativeAdContainer.root.visibility = View.INVISIBLE
        binding.adShimmerLoader.visibility = View.GONE
        binding.tvNoSponsorship.visibility = View.VISIBLE
    }

    private fun populateNativeAdView(nativeAd: NativeAd) {
        val adBinding = binding.nativeAdContainer
        val adView = adBinding.root

        adBinding.adHeadline.text = nativeAd.headline
        adView.headlineView = adBinding.adHeadline

        val bodyText = nativeAd.body
        if (bodyText.isNullOrBlank()) {
            adBinding.adBody.visibility = View.GONE
        } else {
            adBinding.adBody.visibility = View.VISIBLE
            adBinding.adBody.text = bodyText
            adView.bodyView = adBinding.adBody
        }

        val icon = nativeAd.icon
        if (icon != null) {
            adBinding.adIcon.setImageDrawable(icon.drawable)
            adBinding.adIcon.visibility = View.VISIBLE
        } else {
            adBinding.adIcon.visibility = View.GONE
        }
        adView.iconView = adBinding.adIcon

        val cta = nativeAd.callToAction
        if (cta.isNullOrBlank()) {
            adBinding.adCallToAction.visibility = View.GONE
        } else {
            adBinding.adCallToAction.visibility = View.VISIBLE
            adBinding.adCallToAction.text = cta
            adView.callToActionView = adBinding.adCallToAction
        }

        adView.setNativeAd(nativeAd)
    }
}
