package com.goat.app.ui

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.goat.app.R

data class AppEntry(
    val label: String,
    val packageName: String,
    val className: String,
    val icon: Drawable
) {

    val stableId: Long = (packageName + className).hashCode().toLong()
}

data class DrawerIconSizing(
    val iconSizePx: Int,
    val textSizeSp: Float
)

class AppListAdapter(
    private val apps: List<AppEntry>,
    private val sizing: DrawerIconSizing,
    private val onAppClick: (AppEntry) -> Unit
) : RecyclerView.Adapter<AppListAdapter.ViewHolder>() {

    private var lastAnimatedPosition = -1

    init {
        setHasStableIds(true)
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.ivIcon)
        val label: TextView = view.findViewById(R.id.tvLabel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        val holder = ViewHolder(view)

        val iconParams = holder.icon.layoutParams
        iconParams.width = sizing.iconSizePx
        iconParams.height = sizing.iconSizePx
        holder.icon.layoutParams = iconParams
        holder.label.textSize = sizing.textSizeSp
        return holder
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = apps[position]
        holder.icon.setImageDrawable(app.icon)
        holder.label.text = app.label
        holder.itemView.setOnClickListener { onAppClick(app) }
        animateEntranceIfNeeded(holder.itemView, position)
    }

    override fun onViewRecycled(holder: ViewHolder) {

        holder.itemView.animate().cancel()
        holder.itemView.alpha = 1f
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = apps.size

    override fun getItemId(position: Int): Long {
        return apps[position].stableId
    }

    private fun animateEntranceIfNeeded(itemView: View, position: Int) {
        if (position > lastAnimatedPosition) {
            lastAnimatedPosition = position

            itemView.alpha = 0f
            itemView.animate()
                .alpha(1f)
                .setDuration(140L)
                .setStartDelay((position % 20) * 6L)
                .start()
        } else {

            itemView.animate().cancel()
            itemView.alpha = 1f
        }
    }
}
