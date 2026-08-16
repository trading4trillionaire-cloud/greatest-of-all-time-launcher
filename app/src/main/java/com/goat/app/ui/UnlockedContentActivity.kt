package com.goat.app.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.goat.app.R
import com.goat.app.databinding.ActivityUnlockedContentBinding
import java.util.concurrent.Executors

class UnlockedContentActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUnlockedContentBinding

    private val imageLoadExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentStep = 0

    companion object {
        private const val ASSET_FOLDER = "whatsapp-1-images"
        private const val TOTAL_STEPS = 5 // indices 0..4
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUnlockedContentBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { goToLauncher() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                goToLauncher()
            }
        })

        binding.btnPrimaryAction.setOnClickListener {
            if (currentStep >= TOTAL_STEPS - 1) {
                currentStep = 0
            } else {
                currentStep += 1
            }
            renderStep()
        }

        renderStep()
    }

    override fun onDestroy() {
        imageLoadExecutor.shutdown()
        super.onDestroy()
    }

    private fun renderStep() {
        binding.btnPrimaryAction.setBackgroundResource(R.drawable.bg_primary_action_button)

        when (currentStep) {
            0 -> {
                showSingleImageStep(title = getString(R.string.step_title_format, 1), asset = "1.jpg")
                binding.btnPrimaryAction.text = getString(R.string.btn_next_step)
            }
            1 -> {
                showSingleImageStep(title = getString(R.string.step_title_format, 2), asset = "2.jpg")
                binding.btnPrimaryAction.text = getString(R.string.btn_next_step)
            }
            2 -> {
                showCompareStep(
                    title = getString(R.string.step_title_format, 3),
                    safeAsset = "3.jpg",
                    riskAsset = "4.jpg"
                )
                binding.btnPrimaryAction.text = getString(R.string.btn_next_step_how_to_fix)
            }
            3 -> {
                showSingleImageStep(title = getString(R.string.how_to_fix_title), asset = "5.jpg")
                binding.btnPrimaryAction.text = getString(R.string.btn_next)
            }
            4 -> {
                showSingleImageStep(
                    title = getString(R.string.how_to_fix_title),
                    asset = "6.jpg",
                    showCompleted = true
                )
                binding.btnPrimaryAction.text = getString(R.string.btn_show_again)
                binding.btnPrimaryAction.setBackgroundResource(R.drawable.bg_show_again_button)
            }
        }
    }

    private fun showSingleImageStep(title: String, asset: String, showCompleted: Boolean = false) {
        binding.tvStepTitle.text = title
        binding.ivStepImage.visibility = View.VISIBLE
        binding.compareContainer.visibility = View.GONE
        binding.tvCompletedLabel.visibility = if (showCompleted) View.VISIBLE else View.GONE

        loadAssetInto(binding.ivStepImage, asset)
    }

    private fun showCompareStep(title: String, safeAsset: String, riskAsset: String) {
        binding.tvStepTitle.text = title
        binding.ivStepImage.visibility = View.GONE
        binding.compareContainer.visibility = View.VISIBLE
        binding.tvCompletedLabel.visibility = View.GONE

        loadAssetInto(binding.ivSafeImage, safeAsset)
        loadAssetInto(binding.ivRiskImage, riskAsset)
    }

    private fun loadAssetInto(imageView: ImageView, fileName: String) {
        val requestedStep = currentStep
        imageLoadExecutor.execute {
            val bitmap: Bitmap? = try {
                assets.open("$ASSET_FOLDER/$fileName").use { input ->
                    BitmapFactory.decodeStream(input)
                }
            } catch (e: Exception) {
                null
            }

            mainHandler.post {
                if (!isFinishing && !isDestroyed && currentStep == requestedStep && bitmap != null) {
                    imageView.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun goToLauncher() {
        val intent = Intent(this, LauncherHomeActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        startActivity(intent)
        finish()
    }
}
