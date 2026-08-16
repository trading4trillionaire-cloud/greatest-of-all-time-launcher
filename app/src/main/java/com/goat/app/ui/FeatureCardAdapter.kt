package com.goat.app.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.goat.app.databinding.ItemFeatureCardBinding
import com.goat.app.model.FeatureCardItem

class FeatureCardAdapter(
    private val items: List<FeatureCardItem>
) : RecyclerView.Adapter<FeatureCardAdapter.FeatureCardViewHolder>() {

    inner class FeatureCardViewHolder(val binding: ItemFeatureCardBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FeatureCardViewHolder {
        val binding = ItemFeatureCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return FeatureCardViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FeatureCardViewHolder, position: Int) {
        val item = items[position]
        val binding = holder.binding

        binding.ivFeatureIcon.setImageResource(item.iconRes)
        binding.tvFeatureTitle.text = item.title
        binding.tvFeatureStatus.text = item.statusText
        binding.tvFeatureStatus.backgroundTintList = ColorStateList.valueOf(item.statusBgColor)
        binding.tvFeatureCta.text = item.ctaText
        binding.tvFeatureCta.setOnClickListener { item.onCtaClick() }
    }

    override fun getItemCount(): Int = items.size
}
