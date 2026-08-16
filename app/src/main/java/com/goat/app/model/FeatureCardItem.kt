package com.goat.app.model

import androidx.annotation.DrawableRes

data class FeatureCardItem(
    val id: String,
    @DrawableRes val iconRes: Int,
    val title: String,
    val statusText: String,
    val statusBgColor: Int,
    val ctaText: String,
    val onCtaClick: () -> Unit
)
