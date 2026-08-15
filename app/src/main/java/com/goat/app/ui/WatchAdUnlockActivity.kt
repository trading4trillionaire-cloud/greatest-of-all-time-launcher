package com.goat.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
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

    companion object {

        // TEST ad unit — replace with the real rewarded ad unit ID before going live.
        private const val REWARDED_AD_UNIT_ID = "ca-app-pub-3940256099942544/5224354917"

        const val PREFS_NAME = "goat_unlock_prefs"
        const val KEY_LAST_UNLOCK_TIME = "last_unlock_time"

        // TESTING VALUE: 1 minute. Change back to 3 hours (3L * 60 * 60 * 1000) once testing is done.
        const val UNLOCK_WINDOW_MS = 60_000L
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
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    isLoadingAd = false
                    rewardedAd = null
                }
            }
        )
    }

    private fun onContinueClicked() {
        val ad = rewardedAd

        if (ad == null) {
            // Ad not ready yet — try to (re)load it and let the user tap again shortly.
            if (isAdInitialized) loadRewardedAd()
            return
        }

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                rewardedAd = null
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                rewardedAd = null
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
}
