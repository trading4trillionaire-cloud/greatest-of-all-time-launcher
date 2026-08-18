package com.goat.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.goat.app.databinding.ActivityRiskyPermissionsBinding

class RiskyPermissionsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRiskyPermissionsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRiskyPermissionsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        })

        binding.btnSmsAccessPermission.setOnClickListener {
            startActivity(Intent(this, SmsAccessListActivity::class.java))
        }

        // More permission buttons will be wired up here later.
    }
}
