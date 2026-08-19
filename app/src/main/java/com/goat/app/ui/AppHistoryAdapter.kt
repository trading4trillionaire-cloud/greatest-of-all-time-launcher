package com.goat.app.ui

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.goat.app.R

data class AppHistoryEntry(
    val label: String,
    val icon: Drawable,
    val timeText: String
)

class AppHistoryAdapter(
    private val entries: List<AppHistoryEntry>
) : RecyclerView.Adapter<AppHistoryAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.ivHistoryAppIcon)
        val label: TextView = view.findViewById(R.id.tvHistoryAppLabel)
        val time: TextView = view.findViewById(R.id.tvHistoryAppTime)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = entries[position]
        holder.icon.setImageDrawable(entry.icon)
        holder.label.text = entry.label
        holder.time.text = entry.timeText
    }

    override fun getItemCount(): Int = entries.size
}
