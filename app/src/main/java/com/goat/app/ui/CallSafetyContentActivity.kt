package com.goat.app.ui

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.goat.app.databinding.ActivityCallSafetyContentBinding

/**
 * Placeholder screen opened from the "Calls - Safe or Not" home card.
 * Content for this screen will be added later — for now it only provides
 * the back button so the flow is fully navigable.
 */
class CallSafetyContentActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCallSafetyContentBinding

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
    }
}
