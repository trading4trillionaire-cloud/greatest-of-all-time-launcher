package com.goat.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.goat.app.databinding.ActivityWatchAdUnlockBinding
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback

class WatchAdUnlockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWatchAdUnlockBinding

    private var rewardedAd: RewardedAd? = null
    private var isLoadingAd = false
    private var isAdInitialized = false

    // True when the user tapped Continue before the ad had finished loading —
    // once the ad loads we show it automatically instead of leaving them stuck.
    private var showAdOnceLoaded = false

    companion object {

        // Google's official TEST rewarded ad unit ID — always returns a test ad, never a real one.
        private const val REWARDED_AD_UNIT_ID = "ca-app-pub-3940256099942544/5224354917"

        const val PREFS_NAME = "goat_unlock_prefs"
        const val KEY_LAST_UNLOCK_TIME = "last_unlock_time"

        const val UNLOCK_WINDOW_MS = 3L * 60 * 1000
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWatchAdUnlockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        MobileAds.initialize(this) {
            isAdInitialized = true
            loadRewardedAd()
        }

        binding.btnContinue.setOnClickListener {
            onContinueClicked()
        }

        binding.btnNoThanks.setOnClickListener {
            goToLauncher()
        }
    }

    private fun loadRewardedAd() {
        if (isLoadingAd || rewardedAd != null) return
        isLoadingAd = true

        RewardedAd.load(
            this,
            REWARDED_AD_UNIT_ID,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    isLoadingAd = false
                    rewardedAd = ad

                    if (showAdOnceLoaded) {
                        showAdOnceLoaded = false
                        presentAd(ad)
                    }
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    isLoadingAd = false
                    rewardedAd = null

                    if (showAdOnceLoaded) {
                        showAdOnceLoaded = false
                        Toast.makeText(
                            this@WatchAdUnlockActivity,
                            "Ad load nahi ho paya, dobara try karo",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )
    }

    private fun onContinueClicked() {
        val ad = rewardedAd

        if (ad != null) {
            presentAd(ad)
            return
        }

        // Ad isn't ready yet — mark intent to show it as soon as it loads, and (re)trigger a load.
        showAdOnceLoaded = true
        Toast.makeText(this, "Ad load ho raha hai...", Toast.LENGTH_SHORT).show()

        if (isAdInitialized) {
            loadRewardedAd()
        }
    }

    private fun presentAd(ad: RewardedAd) {
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                rewardedAd = null
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                rewardedAd = null
                Toast.makeText(
                    this@WatchAdUnlockActivity,
                    "Ad show nahi ho paya, dobara try karo",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        ad.show(this) {
            unlockAndProceed()
        }
    }

    private fun unlockAndProceed() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_UNLOCK_TIME, System.currentTimeMillis())
            .apply()

        startActivity(Intent(this, UnlockedContentActivity::class.java))
        finish()
    }

    private fun goToLauncher() {
        finish()
    }
}
