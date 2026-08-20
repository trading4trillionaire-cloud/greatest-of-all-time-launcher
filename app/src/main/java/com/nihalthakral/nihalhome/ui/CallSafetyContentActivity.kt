package com.nihalthakral.nihalhome.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.nihalthakral.nihalhome.databinding.ActivityCallSafetyContentBinding
import java.util.concurrent.Executors

class CallSafetyContentActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCallSafetyContentBinding

    private val imageLoadExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val ASSET_FOLDER = "call-forwarding"
        private const val ASSET_IMAGE = "1.jpg"
        private const val CALL_FORWARDING_OFF_CODE = "##002#"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCallSafetyContentBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        })

        binding.btnPrimaryAction.setOnClickListener { openDialerWithCode() }

        loadAssetInto(ASSET_IMAGE)
    }

    override fun onDestroy() {
        imageLoadExecutor.shutdown()
        super.onDestroy()
    }

    private fun openDialerWithCode() {
        val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(CALL_FORWARDING_OFF_CODE)))
        startActivity(dialIntent)
    }

    private fun loadAssetInto(fileName: String) {
        imageLoadExecutor.execute {
            val bitmap: Bitmap? = try {
                assets.open("$ASSET_FOLDER/$fileName").use { input ->
                    BitmapFactory.decodeStream(input)
                }
            } catch (e: Exception) {
                null
            }

            mainHandler.post {
                if (!isFinishing && !isDestroyed && bitmap != null) {
                    binding.ivStepImage.setImageBitmap(bitmap)
                }
            }
        }
    }
}
