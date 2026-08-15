package com.goat.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.goat.app.databinding.ActivityUnlockedContentBinding

class UnlockedContentActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUnlockedContentBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUnlockedContentBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener {
            goToLauncher()
        }

        // System back button should behave the same as the on-screen back button.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                goToLauncher()
            }
        })
    }

    private fun goToLauncher() {
        val intent = Intent(this, LauncherHomeActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        startActivity(intent)
        finish()
    }
}
