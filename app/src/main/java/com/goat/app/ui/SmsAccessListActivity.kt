package com.goat.app.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
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

    override fun onDestroy() {
        loadExecutor.shutdown()
        super.onDestroy()
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
                binding.rvPermissionApps.adapter = RiskyPermissionAppAdapter(entries) { app ->
                    openAppSettings(app.packageName)
                }
            }
        }
    }

    private fun openAppSettings(packageName: String) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        intent.data = Uri.parse("package:$packageName")
        try {
            startActivity(intent)
        } catch (e: Exception) {

        }
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
