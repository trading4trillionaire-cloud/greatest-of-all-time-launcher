package com.goat.app.ui

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.goat.app.databinding.ActivityCheckPhoneHistoryBinding
import java.util.concurrent.Executors

class CheckPhoneHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCheckPhoneHistoryBinding

    private val imageLoadExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val USAGE_ACCESS_ASSET_FOLDER = "Usage_Access_Permission"
        private const val USAGE_ACCESS_ASSET_FILE = "1.jpg"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCheckPhoneHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnGrantPermission.setOnClickListener {
            openUsageAccessSettings()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshScreenForPermissionState()
    }

    override fun onDestroy() {
        imageLoadExecutor.shutdown()
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
    }

    private fun showPermissionScreen() {
        binding.contentLayer.visibility = View.GONE
        binding.permissionLayer.visibility = View.VISIBLE
        loadUsageAccessScreenshot()
    }

    private fun loadUsageAccessScreenshot() {
        imageLoadExecutor.execute {
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
            // Fallback for devices where the direct-to-app deep link isn't supported.
            try {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } catch (e2: Exception) {
            }
        }
    }
}
