package com.nihalthakral.nihalhome.ui

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
import com.nihalthakral.nihalhome.R
import com.nihalthakral.nihalhome.databinding.ActivityUnlockedContentBinding
import java.util.concurrent.Executors

class UnlockedContentActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUnlockedContentBinding

    private val imageLoadExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentStep = 0

    companion object {
        private const val ASSET_FOLDER = "whatsapp-1-images"
        private const val TOTAL_STEPS = 5
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
        updateProgressDots()
        binding.tvStepEyebrow.text = getString(R.string.guide_step_of_format, currentStep + 1, TOTAL_STEPS)

        when (currentStep) {
            0 -> {
                showSingleImageStep(
                    title = getString(R.string.guide_step1_title),
                    body = getString(R.string.guide_step1_body),
                    asset = "1.jpg"
                )
                binding.btnPrimaryAction.text = getString(R.string.btn_next_step)
            }
            1 -> {
                showSingleImageStep(
                    title = getString(R.string.guide_step2_title),
                    body = getString(R.string.guide_step2_body),
                    asset = "2.jpg"
                )
                binding.btnPrimaryAction.text = getString(R.string.btn_next_step)
            }
            2 -> {
                showCompareStep(
                    title = getString(R.string.guide_step3_title),
                    body = getString(R.string.guide_step3_body),
                    safeAsset = "3.jpg",
                    riskAsset = "4.jpg"
                )
                binding.btnPrimaryAction.text = getString(R.string.btn_next_step_how_to_fix)
            }
            3 -> {
                showSingleImageStep(
                    title = getString(R.string.guide_step4_title),
                    body = getString(R.string.guide_step4_body),
                    asset = "5.jpg"
                )
                binding.btnPrimaryAction.text = getString(R.string.btn_next)
            }
            4 -> {
                showSingleImageStep(
                    title = getString(R.string.guide_step5_title),
                    body = getString(R.string.guide_step5_body),
                    asset = "6.jpg",
                    showCompleted = true
                )
                binding.btnPrimaryAction.text = getString(R.string.btn_show_again)
                binding.btnPrimaryAction.setBackgroundResource(R.drawable.bg_show_again_button)
            }
        }
    }

    private fun updateProgressDots() {
        val dots = listOf(
            binding.dot0,
            binding.dot1,
            binding.dot2,
            binding.dot3,
            binding.dot4
        )
        dots.forEachIndexed { index, dot ->
            dot.setBackgroundResource(
                if (index <= currentStep) R.drawable.bg_progress_dot_active
                else R.drawable.bg_progress_dot_inactive
            )
        }
    }

    private fun showSingleImageStep(title: String, body: String, asset: String, showCompleted: Boolean = false) {
        binding.tvStepTitle.text = title
        binding.tvStepBody.text = body
        binding.singleMockupFrame.visibility = View.VISIBLE
        binding.compareContainer.visibility = View.GONE
        binding.tvCompletedLabel.visibility = if (showCompleted) View.VISIBLE else View.GONE

        loadAssetInto(binding.ivStepImage, asset)
    }

    private fun showCompareStep(title: String, body: String, safeAsset: String, riskAsset: String) {
        binding.tvStepTitle.text = title
        binding.tvStepBody.text = body
        binding.singleMockupFrame.visibility = View.GONE
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
