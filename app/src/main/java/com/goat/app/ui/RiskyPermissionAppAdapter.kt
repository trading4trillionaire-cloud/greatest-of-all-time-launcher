package com.goat.app.ui

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.goat.app.R

data class PermissionAppEntry(
    val label: String,
    val packageName: String,
    val icon: Drawable,
    val isGranted: Boolean
)

class RiskyPermissionAppAdapter(
    private val apps: List<PermissionAppEntry>,
    private val onToggleClick: (PermissionAppEntry) -> Unit
) : RecyclerView.Adapter<RiskyPermissionAppAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.ivPermissionAppIcon)
        val label: TextView = view.findViewById(R.id.tvPermissionAppLabel)
        val toggle: TextView = view.findViewById(R.id.btnPermissionToggle)
        val status: TextView = view.findViewById(R.id.tvPermissionAppStatus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_risky_permission_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = apps[position]
        holder.icon.setImageDrawable(app.icon)
        holder.label.text = app.label

        if (app.isGranted) {
            holder.status.text = holder.itemView.context.getString(R.string.permission_status_allowed)
            holder.status.setTextColor(holder.itemView.context.getColor(R.color.label_safe_color))
        } else {
            holder.status.text = holder.itemView.context.getString(R.string.permission_status_not_allowed)
            holder.status.setTextColor(holder.itemView.context.getColor(R.color.label_risk_color))
        }

        holder.toggle.text = holder.itemView.context.getString(R.string.permission_toggle_on)
        holder.toggle.setTextColor(holder.itemView.context.getColor(R.color.guide_card_title_text))

        holder.toggle.setOnClickListener { onToggleClick(app) }
    }

    override fun getItemCount(): Int = apps.size
}
