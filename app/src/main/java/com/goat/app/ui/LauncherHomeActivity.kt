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

    private var cachedAd: NativeAd? = null

    private var loadTime: Long = 0L

    private var lastImpressionTime: Long = 0L

    private var displayedAd: NativeAd? = null
    private var isAdFetchInFlight = false

    private val appLoadExecutor = Executors.newSingleThreadExecutor()

    private var packageChangeReceiver: BroadcastReceiver? = null

    companion object {

        private var cachedExpandPanelMethod: java.lang.reflect.Method? = null

        private var cachedApps: List<AppEntry>? = null
        private const val SWIPE_DOWN_MIN_DISTANCE_PX = 40
        private const val DRAWER_SNAP_DURATION_MS = 220L
        private const val HINT_BOUNCE_DISTANCE_PX = 10f
        private const val HINT_BOUNCE_DURATION_MS = 650L

        private const val MIN_COLUMN_COUNT = 4
        private const val MAX_COLUMN_COUNT = 5
        private const val VISIBLE_ROW_COUNT = 5
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

        val cached = cachedApps
        if (cached != null) {
            binding.rvApps.adapter = AppListAdapter(cached, drawerIconSizing) { app -> launchApp(app) }
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
        cachedAd?.destroy()
        displayedAd?.destroy()
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
            val spanCount = (parent.layoutManager as? GridLayoutManager)?.spanCount ?: 1
            val lastRowItemCount = itemCount % spanCount
            val lastRowStartPosition = if (lastRowItemCount == 0) itemCount - spanCount else itemCount - lastRowItemCount
            val isLastRow = position != RecyclerView.NO_POSITION && position >= lastRowStartPosition
            outRect.bottom = if (isLastRow) 0 else rowSpacingPx
        }
    }

    private fun setupDrawer() {

        val spanCount = calculateSpanCount()
        val gridLayoutManager = GridLayoutManager(this, spanCount).apply {
            isItemPrefetchEnabled = true
            initialPrefetchItemCount = spanCount * 2
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
                    val naturalRowHeightPx = rv.getChildAt(0).height
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

    private fun closeDrawer() {
        if (!isDrawerOpen) return
        animateToProgress(0f, false)
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

            Handler(Looper.getMainLooper()).post {
                if (!isFinishing) {
                    binding.rvApps.adapter = AppListAdapter(apps, drawerIconSizing) { app -> launchApp(app) }
                }
            }
        }
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
            closeDrawer()
        } catch (e: Exception) {
            Toast.makeText(this, "${app.label} nahi khul paya", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onDrawerOpened() {
        val now = System.currentTimeMillis()

        if (now - lastImpressionTime < IMPRESSION_COOLDOWN_MS) {

            hideAdSlot()
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

    private fun hideAdSlot() {

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
