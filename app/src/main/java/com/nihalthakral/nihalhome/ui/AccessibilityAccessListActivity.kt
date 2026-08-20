package com.nihalthakral.nihalhome.ui

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.nihalthakral.nihalhome.R
import com.nihalthakral.nihalhome.databinding.ActivityAccessibilityAccessListBinding
import java.util.concurrent.Executors

/**
 * Lists every installed app that declares an Accessibility Service, and shows in real
 * time whether the user currently has that service switched on ("Allowed") or off
 * ("Not Allowed") from Settings > Accessibility.
 *
 * Mirrors SmsAccessListActivity's structure: same adapter, same guidance dialog pattern,
 * same lifecycle-aware polling (only runs while this screen is visible).
 */
class AccessibilityAccessListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAccessibilityAccessListBinding
    private val loadExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    // packageName -> set of "packageName/serviceClassName" component keys declared by that app.
    // Used to re-check granted status quickly on every poll without a full package rescan.
    private val serviceKeysByPackage = HashMap<String, List<String>>()

    private var currentEntries: MutableList<PermissionAppEntry> = mutableListOf()
    private var adapter: RiskyPermissionAppAdapter? = null
    private var isPolling = false
    private val pollIntervalMs = 1500L

    private val pollRunnable = object : Runnable {
        override fun run() {
            refreshPermissionStatuses()
            if (isPolling) {
                mainHandler.postDelayed(this, pollIntervalMs)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAccessibilityAccessListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        })

        binding.rvPermissionApps.layoutManager = LinearLayoutManager(this)

        loadAccessibilityAppsAsync()
    }

    override fun onResume() {
        super.onResume()
        startPolling()
    }

    override fun onPause() {
        stopPolling()
        super.onPause()
    }

    override fun onDestroy() {
        stopPolling()
        loadExecutor.shutdown()
        super.onDestroy()
    }

    private fun startPolling() {
        if (isPolling) return
        isPolling = true
        mainHandler.postDelayed(pollRunnable, pollIntervalMs)
    }

    private fun stopPolling() {
        isPolling = false
        mainHandler.removeCallbacks(pollRunnable)
    }

    private fun loadAccessibilityAppsAsync() {
        binding.permissionListLoader.visibility = View.VISIBLE
        binding.rvPermissionApps.visibility = View.INVISIBLE

        loadExecutor.execute {
            val pm = packageManager
            val reusableCanvas = Canvas()

            // Every app that ships an Accessibility Service declares a <service> that
            // responds to this intent action and requires BIND_ACCESSIBILITY_SERVICE.
            val serviceIntent = Intent("android.accessibilityservice.AccessibilityService")
            val resolvedServices = try {
                pm.queryIntentServices(serviceIntent, PackageManager.GET_META_DATA)
            } catch (e: Exception) {
                emptyList()
            }

            val keysByPackage = HashMap<String, MutableList<String>>()
            for (resolveInfo in resolvedServices) {
                val serviceInfo = resolveInfo.serviceInfo ?: continue
                if (serviceInfo.packageName == packageName) continue
                if (serviceInfo.permission != android.Manifest.permission.BIND_ACCESSIBILITY_SERVICE) continue

                val key = "${serviceInfo.packageName}/${serviceInfo.name}"
                keysByPackage.getOrPut(serviceInfo.packageName) { mutableListOf() }.add(key)
            }

            val enabledKeys = readEnabledAccessibilityServiceKeys()

            val entries = keysByPackage.keys.mapNotNull { pkg ->
                try {
                    val serviceKeys = keysByPackage[pkg].orEmpty()
                    val isGranted = serviceKeys.any { it in enabledKeys }

                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    val label = pm.getApplicationLabel(appInfo).toString()
                    val icon = toFixedSizeDrawable(pm.getApplicationIcon(appInfo), 96, reusableCanvas)

                    PermissionAppEntry(
                        label = label,
                        packageName = pkg,
                        icon = icon,
                        isGranted = isGranted
                    )
                } catch (e: Exception) {
                    null
                }
            }.sortedWith(compareByDescending<PermissionAppEntry> { it.isGranted }.thenBy { it.label.lowercase() })

            mainHandler.post {
                if (isFinishing || isDestroyed) return@post

                serviceKeysByPackage.clear()
                serviceKeysByPackage.putAll(keysByPackage)

                binding.permissionListLoader.visibility = View.GONE
                binding.rvPermissionApps.visibility = View.VISIBLE

                currentEntries = entries.toMutableList()
                val newAdapter = RiskyPermissionAppAdapter(currentEntries) { app ->
                    openAccessibilitySettings()
                }
                adapter = newAdapter
                binding.rvPermissionApps.adapter = newAdapter
            }
        }
    }

    private fun refreshPermissionStatuses() {
        if (currentEntries.isEmpty()) return
        if (isFinishing || isDestroyed) return

        loadExecutor.execute {
            val enabledKeys = readEnabledAccessibilityServiceKeys()
            val updates = mutableListOf<Pair<Int, Boolean>>()

            currentEntries.forEachIndexed { index, entry ->
                val serviceKeys = serviceKeysByPackage[entry.packageName].orEmpty()
                val isGranted = serviceKeys.any { it in enabledKeys }
                if (isGranted != entry.isGranted) {
                    updates.add(index to isGranted)
                }
            }

            if (updates.isEmpty()) return@execute

            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                updates.forEach { (index, isGranted) ->
                    if (index < currentEntries.size) {
                        val entry = currentEntries[index]
                        currentEntries[index] = entry.copy(isGranted = isGranted)
                        adapter?.notifyItemChanged(index)
                    }
                }
            }
        }
    }

    /** Reads Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES as a set of "pkg/service" keys. */
    private fun readEnabledAccessibilityServiceKeys(): Set<String> {
        val raw = try {
            Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        } catch (e: Exception) {
            null
        }
        if (raw.isNullOrBlank()) return emptySet()
        return raw.split(':').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    private fun openAccessibilitySettings() {
        showGuidanceDialog {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            try {
                startActivity(intent)
            } catch (e: Exception) {

            }
        }
    }

    private fun showGuidanceDialog(onProceed: () -> Unit) {
        val dialogView = LayoutInflater.from(this)
            .inflate(R.layout.dialog_permission_guidance, null)
        val btnClose = dialogView.findViewById<TextView>(R.id.btnGuidanceClose)
        val tvPermissionType = dialogView.findViewById<TextView>(R.id.tvGuidanceSms)
        val tvHint = dialogView.findViewById<TextView>(R.id.tvGuidanceHint)

        tvPermissionType.text = getString(R.string.accessibility_permission_name)
        tvHint.text = getString(R.string.accessibility_guidance_hint)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val countdownTimer = object : CountDownTimer(5000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsLeft = (millisUntilFinished / 1000) + 1
                btnClose.text = getString(R.string.permission_guidance_close_countdown, secondsLeft)
            }

            override fun onFinish() {
                btnClose.text = getString(R.string.permission_guidance_close)
                btnClose.isEnabled = true
                btnClose.alpha = 1.0f
            }
        }
        countdownTimer.start()

        btnClose.setOnClickListener {
            if (btnClose.isEnabled) {
                countdownTimer.cancel()
                dialog.dismiss()
                onProceed()
            }
        }

        dialog.setOnDismissListener {
            countdownTimer.cancel()
        }

        dialog.show()
    }

    private fun toFixedSizeDrawable(source: Drawable, targetSizePx: Int, reusableCanvas: Canvas): Drawable {
        return try {
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
}
