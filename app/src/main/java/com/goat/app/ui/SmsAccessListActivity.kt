package com.goat.app.ui

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
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
import com.goat.app.R
import com.goat.app.databinding.ActivitySmsAccessListBinding
import java.util.concurrent.Executors

class SmsAccessListActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySmsAccessListBinding
    private val loadExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val smsPermissions = arrayOf(
        android.Manifest.permission.READ_SMS,
        android.Manifest.permission.RECEIVE_SMS
    )

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
        binding = ActivitySmsAccessListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        })

        binding.rvPermissionApps.layoutManager = LinearLayoutManager(this)

        loadSmsPermissionAppsAsync()
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
        // Skip the immediate first tick right after the initial load already ran;
        // the loop schedules itself, so just kick it off with the standard delay.
        mainHandler.postDelayed(pollRunnable, pollIntervalMs)
    }

    private fun stopPolling() {
        isPolling = false
        mainHandler.removeCallbacks(pollRunnable)
    }

    private fun refreshPermissionStatuses() {
        // Only poll once we actually have a loaded list on screen.
        if (currentEntries.isEmpty()) return
        if (isFinishing || isDestroyed) return

        loadExecutor.execute {
            val pm = packageManager
            val updates = mutableListOf<Pair<Int, Boolean>>()

            currentEntries.forEachIndexed { index, entry ->
                val isGranted = smsPermissions.any { perm ->
                    pm.checkPermission(perm, entry.packageName) == PackageManager.PERMISSION_GRANTED
                }
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

    private fun loadSmsPermissionAppsAsync() {
        binding.permissionListLoader.visibility = View.VISIBLE
        binding.rvPermissionApps.visibility = View.INVISIBLE

        loadExecutor.execute {
            val pm = packageManager
            val reusableCanvas = Canvas()

            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val launcherPackages = try {
                pm.queryIntentActivities(launcherIntent, 0)
                    .map { it.activityInfo.packageName }
                    .filter { it != packageName }
                    .distinct()
            } catch (e: Exception) {
                emptyList()
            }

            val entries = launcherPackages.mapNotNull { pkg ->
                try {
                    val packageInfo = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
                    val requestedPermissions = packageInfo.requestedPermissions ?: return@mapNotNull null

                    val requestsSms = requestedPermissions.any { it in smsPermissions }
                    if (!requestsSms) return@mapNotNull null

                    val isGranted = smsPermissions.any { perm ->
                        pm.checkPermission(perm, pkg) == PackageManager.PERMISSION_GRANTED
                    }

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

                binding.permissionListLoader.visibility = View.GONE
                binding.rvPermissionApps.visibility = View.VISIBLE

                currentEntries = entries.toMutableList()
                val newAdapter = RiskyPermissionAppAdapter(currentEntries) { app ->
                    openAppSettings(app.packageName)
                }
                adapter = newAdapter
                binding.rvPermissionApps.adapter = newAdapter
            }
        }
    }

    private fun openAppSettings(packageName: String) {
        showGuidanceDialog {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.parse("package:$packageName")
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
