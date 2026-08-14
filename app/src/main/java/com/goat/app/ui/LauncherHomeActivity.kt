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
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
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

    private var hintAnimator: ObjectAnimator? = null
    private var snapAnimator: ValueAnimator? = null

    private var drawerHeightPx = 0f
    private var currentProgress = 0f

    private var isDraggingOpen = false
    private var dragStartRawY = 0f

    // ---- New drawer ad algorithm state ----
    // The ad that's pre-fetched and sitting ready, waiting for the next drawer open.
    private var cachedAd: NativeAd? = null
    // When cachedAd was fetched (used to check the 45-min cache expiry).
    private var loadTime: Long = 0L
    // When an ad was last actually shown to the user (used for the 15s impression cooldown).
    private var lastImpressionTime: Long = 0L
    // The ad currently visible on screen (kept separately so we can destroy() it on swap).
    private var displayedAd: NativeAd? = null
    private var isAdFetchInFlight = false

    // Optimization #1: ek hi reusable background worker (thread pool), taaki
    // har app-list load pe naya Thread{} na banana pade.
    private val appLoadExecutor = Executors.newSingleThreadExecutor()

    // Optimization #3: naya app install/uninstall hone par list ko automatic
    // refresh karne ke liye receiver.
    private var packageChangeReceiver: BroadcastReceiver? = null

    companion object {
        // Optimization #17: reflected Method ek baar resolve hone ke baad
        // yahan cache rehta hai (activity instances ke beech bhi reuse hota hai).
        private var cachedExpandPanelMethod: java.lang.reflect.Method? = null

        // Optimization #2: app list ko process-level memory mein cache karke
        // rakhte hain, taaki drawer/activity dobara bane to bhi PackageManager
        // ko dobara se poori list scan na karni pade jab tak kuch install/
        // uninstall na hua ho.
        private var cachedApps: List<AppEntry>? = null
        private const val SWIPE_DOWN_MIN_DISTANCE_PX = 40
        private const val DRAWER_SNAP_DURATION_MS = 220L
        private const val HINT_BOUNCE_DISTANCE_PX = 10f
        private const val HINT_BOUNCE_DURATION_MS = 650L
        // Modification: columns ab screen width ke hisaab se dynamic hain —
        // sirf 4 ya 5 mein se koi ek, kabhi 5 se zyada nahi. Screen par ek
        // time pe sirf 5 rows hi dikhengi — baaki rows ke beech gap add karke.
        private const val MIN_COLUMN_COUNT = 4
        private const val MAX_COLUMN_COUNT = 5
        private const val VISIBLE_ROW_COUNT = 5
        private const val OPEN_PROGRESS_THRESHOLD = 0.08f
        private const val CLOSE_PROGRESS_THRESHOLD = 0.08f
        private const val HOME_MIN_ALPHA = 0.1f
        private const val DRAG_TOUCH_SLOP_PX = 8f
        private const val ICON_TARGET_SIZE_DP = 48

        // TEST native ad unit ID (Google official). Replace with your real Native Ad Unit ID before release.
        private const val NATIVE_AD_UNIT_ID = "ca-app-pub-3940256099942544/2247696110"

        // Rule 2 gate: if the drawer is opened again within this window of the
        // last impression, we don't show/fetch an ad at all (drawer opens plain).
        private const val IMPRESSION_COOLDOWN_MS = 15_000L

        // Rule 2 / Rule 4: a cached ad older than this is considered expired.
        private const val CACHE_EXPIRY_MS = 45 * 60 * 1000L

        // "Fix Issue" button: lets the user manually restart (force-stop +
        // fresh cold start) the launcher if something ever glitches, without
        // digging into Settings > App Info > Force Stop. Throttled so it
        // can't be spammed.
        private const val FIX_ISSUE_PREFS_NAME = "goat_launcher_prefs"
        private const val KEY_LAST_FIX_ISSUE_TIME = "last_fix_issue_time"
        private const val FIX_ISSUE_COOLDOWN_MS = 15 * 60 * 1000L
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

        setupGestures()
        setupDrawer()
        setupSwipeUpHint()
        setupFixIssueButton()

        // Optimization #2: agar list pehle se cache mein hai (memory mein),
        // to seedha usi se turant dikha do — koi fresh scan nahi.
        val cached = cachedApps
        if (cached != null) {
            binding.rvApps.adapter = AppListAdapter(cached) { app -> launchApp(app) }
        } else {
            loadAppsAsync()
        }

        registerPackageChangeReceiver()

        MobileAds.initialize(this) {
            // Rule 1: Cold Boot - fetch an ad in the background and cache it,
            // ready for whenever the drawer is first opened.
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
        // Optimization #22: app wapas foreground mein aate hi hint animation
        // resume kar do (jahan se pause hui thi, wahin se continue).
        hintAnimator?.let { if (it.isPaused) it.resume() }

        // Fix Issue button: cooldown khatam ho chuka ho sakta hai jab tak app
        // background mein thi (ya just launched hui thi) — har resume par
        // dobara check karke visibility update kar do.
        updateFixIssueButtonVisibility()

        // Rule 4: Returning to Launcher - if the cached ad has gone stale
        // (older than 45 min), throw it away and fetch a fresh one in the
        // background. This does NOT render anything by itself.
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
        // Rule 3: Drawer Close / App Launch - no periodic timers run in this
        // algorithm (everything is event-driven off drawer-open/onResume), so
        // there's nothing to stop here beyond letting any in-flight network
        // fetch finish naturally in the background.

        // Bug fix (safety net): agar kisi wajah se drag event system se miss
        // ho jaaye (jaise phone call, screen-off, ya koi rare OS interrupt),
        // to app background jaate hi drawer ko turant sahi final position
        // (poora khula ya poora band) pe snap kar do — taaki wo kabhi
        // "adhbeech mein atka hua" state mein na reh jaaye.
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

        // Optimization #22: jab app background mein chala jaaye (user dusra
        // app khol le), to infinite chalne wali swipe-up hint animation ko
        // pause kar do — warna wo battery/CPU background mein bhi waste karti rahegi.
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

    /**
     * Optimization #3: jab bhi koi app install, uninstall, ya update ho, tabhi
     * list ko refresh karo — poore drawer-open pe baar baar scan karne ke
     * bajaye sirf zaroorat padne par.
     */
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

    // Optimization #17: reflection se resolve kiya hua Method ek baar cache
    // ho jaata hai (companion object mein) — har swipe-down gesture par
    // dobara Class.forName()/getMethod() call karne ki zaroorat nahi.
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

    // Bug fix: pehle yahan ek "batch next frame" (postOnAnimation) wala
    // mechanism tha jo drag/animation cancel-restart hone par kabhi-kabhi
    // desync ho jaata tha — currentProgress (logic) ek jagah hota tha, par
    // screen par dikh raha actual position kahin aur reh jaata tha, jisse
    // drawer aadha khula "stuck" reh jaata tha. Ab seedha, direct (synchronous)
    // update karte hain — koi delay/queueing nahi, isliye desync possible nahi.
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
                        // Optimization #20: drawer band ho gaya, home layer
                        // wapas dikhao.
                        binding.homeLayer.visibility = View.VISIBLE
                    } else {
                        // Optimization #20: drawer poori tarah khul chuka hai —
                        // ab home layer (jo neeche chhupa hai) ko draw hi mat
                        // karo. Pehle sirf iska alpha kam hota tha (10% tak),
                        // isliye har frame drawerLayer ke background ke sath
                        // wo bhi overlap hoke draw hota tha (GPU overdraw).
                        binding.homeLayer.visibility = View.INVISIBLE
                        if (!wasOpen) {
                            // Drawer just transitioned from closed -> open.
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

    // Modification: rows ke beech extra vertical gap — sirf ek baar calculate
    // hota hai jab RecyclerView pehli baar layout hoti hai (heavy calculation
    // nahi, scroll ke waqt kuch bhi recalculate nahi hota).
    private var rowSpacingPx = 0
    private val rowSpacingDecoration = object : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: android.graphics.Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            // Fix: last row ke items ko ye extra bottom gap nahi dena —
            // pehle ye har row (last row samet) ke neeche add hota tha,
            // jiski wajah se list scroll khatam hone ke baad bhi ek bada
            // khaali gap tak scroll ho jaata tha.
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
        // Modification: columns screen width ke hisaab se decide hote hain —
        // sirf 4 ya 5 (kabhi 5 se zyada nahi). Ye calculation halki hai, ek
        // simple division hai, koi heavy computation nahi.
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
        // Optimization #6: fixed 30 ke bajaye, screen par kitni rows dikhengi
        // uske hisaab se recycle pool size tay hoti hai — chhote screen par
        // kam memory hold hogi, bade screen par utni jitni zaroorat hai.
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

        // Modification: ek baar (jab pehli row render ho chuki ho) row ki
        // natural height nikal ke, RecyclerView ki visible height ko 5 hisso
        // mein baant ke, farak ko row-spacing ke roop mein set karte hain.
        // Isse screen par hamesha sirf ~5 rows dikhengi, icon size wahi
        // (48dp, normal system UI jaisa) rehta hai.
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

    /**
     * Modification: screen width ke hisaab se decide karta hai ki 4 columns
     * fit honge ya 5 — kabhi bhi 4 se kam ya 5 se zyada nahi jaayega.
     * Icon+label ke liye ek comfortable target column-width (~80dp) ke
     * hisaab se calculate hota hai, phir 4..5 ke beech clamp kar diya jaata hai.
     */
    private fun calculateSpanCount(): Int {
        val density = resources.displayMetrics.density
        val screenWidthDp = resources.displayMetrics.widthPixels / density
        val idealColumnWidthDp = 80f
        val computed = (screenWidthDp / idealColumnWidthDp).toInt()
        return computed.coerceIn(MIN_COLUMN_COUNT, MAX_COLUMN_COUNT)
    }

    /**
     * Optimization #6: kitne "extra" recycled item views yaad rakhne hain,
     * ye screen par ek saath dikhne wali rows ke hisaab se decide karte hain
     * (visible rows + thoda buffer), fixed hardcoded number ke bajaye.
     * Modification: ab visible rows hamesha VISIBLE_ROW_COUNT (5) hain.
     */
    private fun calculateRecyclePoolSize(spanCount: Int): Int {
        return spanCount * (VISIBLE_ROW_COUNT + 2)
    }

    private fun fixIssuePrefs(): SharedPreferences =
        getSharedPreferences(FIX_ISSUE_PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Fix Issue button: chota, low-opacity restart button jo hamesha top-right
     * corner mein (home ho ya drawer) upar hi rehta hai, taaki user ko kabhi
     * bhi zaroorat pade to turant mil jaaye, lekin normally dhyaan na khaínche.
     */
    private fun setupFixIssueButton() {
        updateFixIssueButtonVisibility()
        binding.btnFixIssue.setOnClickListener {
            performFixIssueRestart()
        }
    }

    /**
     * Button sirf tabhi dikhta hai jab last force-restart ko 15 minute se
     * zyada ho chuke hon (ya kabhi restart hua hi na ho) — isse user isko
     * baar-baar spam nahi kar sakta.
     */
    private fun updateFixIssueButtonVisibility() {
        val lastFixTime = fixIssuePrefs().getLong(KEY_LAST_FIX_ISSUE_TIME, 0L)
        val elapsed = System.currentTimeMillis() - lastFixTime
        binding.btnFixIssue.visibility =
            if (elapsed >= FIX_ISSUE_COOLDOWN_MS) View.VISIBLE else View.GONE
    }

    /**
     * User ne "Fix Issue" tap kiya: timestamp save karo (15-min cooldown ke
     * liye, taaki dobara turant na dikhe), aur launcher ke apne process ko
     * seedha kill kar do — koi Settings screen nahi khulti, koi confirmation
     * dialog nahi. Process khatam hote hi Android launcher ko fresh cold
     * start karega jaise manual "Force Stop" ke baad hota hai, aur us waqt
     * tak ka koi bhi stuck/glitched state saath saath clear ho jaata hai.
     */
    private fun performFixIssueRestart() {
        fixIssuePrefs().edit().putLong(KEY_LAST_FIX_ISSUE_TIME, System.currentTimeMillis()).commit()
        binding.btnFixIssue.visibility = View.GONE
        Process.killProcess(Process.myPid())
    }

    private fun closeDrawer() {
        if (!isDrawerOpen) return
        animateToProgress(0f, false)
    }

    private fun loadAppsAsync() {
        // Optimization #1: naya Thread{} banane ke bajaye ek hi reusable
        // background worker (executor) ka use, taaki thread creation ka
        // baar-baar overhead na ho.
        appLoadExecutor.execute {
            val pm = packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved: List<ResolveInfo> = try {
                pm.queryIntentActivities(intent, 0)
            } catch (e: Exception) {
                emptyList()
            }

            val iconSizePx = (ICON_TARGET_SIZE_DP * resources.displayMetrics.density).toInt()
            // Ek hi Canvas object poori list ke saare icons ke liye reuse hoga.
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

            // Optimization #2: naye scan ka result cache mein save karo taaki
            // agli baar (naya install/uninstall hone tak) dobara scan na karna pade.
            cachedApps = apps

            Handler(Looper.getMainLooper()).post {
                if (!isFinishing) {
                    binding.rvApps.adapter = AppListAdapter(apps) { app -> launchApp(app) }
                }
            }
        }
    }

    private fun toFixedSizeDrawable(source: Drawable, targetSizePx: Int, reusableCanvas: Canvas): Drawable {
        return try {
            // Optimization #5: agar icon already target size ka hai, to resize
            // step hi skip kar do — extra Bitmap/Canvas draw ki zaroorat nahi.
            if (source is BitmapDrawable &&
                source.bitmap.width == targetSizePx &&
                source.bitmap.height == targetSizePx
            ) {
                return source
            }
            // Optimization #4: naya Canvas object har baar banane ke bajaye,
            // ek hi reusable Canvas ko naye Bitmap par point kar dete hain.
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

    /**
     * Rule 2: Drawer Open - the main impression engine. Called exactly once,
     * right when the drawer finishes animating from closed to open.
     */
    private fun onDrawerOpened() {
        val now = System.currentTimeMillis()

        if (now - lastImpressionTime < IMPRESSION_COOLDOWN_MS) {
            // Too soon since the last impression: open the drawer plain, no ad
            // render, no fetch request at all.
            hideAdSlot()
            return
        }

        val cached = cachedAd
        val cacheAge = now - loadTime

        if (cached != null && cacheAge < CACHE_EXPIRY_MS) {
            // Instantly render the already-cached ad (+1 impression).
            showAd(cached)
            lastImpressionTime = now
            cachedAd = null
            // Background: line up the next ad for the following open.
            fetchNativeAd { ad ->
                if (ad != null) {
                    cachedAd = ad
                    loadTime = System.currentTimeMillis()
                }
            }
        } else {
            // Cache empty or expired: show a shimmer loader, fetch fresh, then render.
            showShimmerLoader()
            fetchNativeAd { ad ->
                hideShimmerLoader()
                if (ad != null) {
                    showAd(ad)
                    lastImpressionTime = System.currentTimeMillis()
                    // Render hote hi agla ad cache ke liye bhej do.
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
        // Within the 15s impression cooldown: leave whatever was already
        // showing as-is (no re-render), don't fetch, don't touch state.
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
