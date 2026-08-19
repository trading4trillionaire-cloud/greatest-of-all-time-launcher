package com.goat.app.ui

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
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

/**
 * A single row in the app-drawer RecyclerView: either a section header
 * ("Recommended apps" / "Recent Apps" / "All Apps") or an app icon.
 */
sealed class DrawerListItem {
    data class Header(val title: String) : DrawerListItem() {
        val stableId: Long = ("header_" + title).hashCode().toLong()
    }
    data class AppItem(val app: AppEntry) : DrawerListItem()
}

class AppListAdapter(
    private var items: List<DrawerListItem>,
    private val sizing: DrawerIconSizing,
    private val onAppClick: (AppEntry) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val VIEW_TYPE_HEADER = 0
        const val VIEW_TYPE_APP = 1
    }

    private var lastAnimatedPosition = -1

    init {
        setHasStableIds(true)
    }

    class AppViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.ivIcon)
        val label: TextView = view.findViewById(R.id.tvLabel)
    }

    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.tvSectionTitle)
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is DrawerListItem.Header -> VIEW_TYPE_HEADER
            is DrawerListItem.AppItem -> VIEW_TYPE_APP
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_TYPE_HEADER) {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_drawer_header, parent, false)
            HeaderViewHolder(view)
        } else {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
            val holder = AppViewHolder(view)

            val iconParams = holder.icon.layoutParams
            iconParams.width = sizing.iconSizePx
            iconParams.height = sizing.iconSizePx
            holder.icon.layoutParams = iconParams
            holder.label.textSize = sizing.textSizeSp
            holder
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is DrawerListItem.Header -> {
                (holder as HeaderViewHolder).title.text = item.title
            }
            is DrawerListItem.AppItem -> {
                val appHolder = holder as AppViewHolder
                val app = item.app
                appHolder.icon.setImageDrawable(app.icon)
                appHolder.label.text = app.label
                appHolder.itemView.setOnClickListener { onAppClick(app) }
                animateEntranceIfNeeded(appHolder.itemView, position)
            }
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        holder.itemView.animate().cancel()
        holder.itemView.alpha = 1f
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = items.size

    /**
     * Replaces the current list with [newItems] using DiffUtil, so only the rows that
     * actually changed (added / removed / moved) get updated in the RecyclerView.
     * Unlike swapping the whole adapter, this avoids the "icons disappear and redraw"
     * flicker when the drawer refreshes Recommended/Recent apps on open.
     */
    fun updateItems(newItems: List<DrawerListItem>) {
        val oldItems = items
        val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldItems.size
            override fun getNewListSize(): Int = newItems.size

            private fun idOf(item: DrawerListItem): Long = when (item) {
                is DrawerListItem.Header -> item.stableId
                is DrawerListItem.AppItem -> item.app.stableId
            }

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return idOf(oldItems[oldItemPosition]) == idOf(newItems[newItemPosition])
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val oldItem = oldItems[oldItemPosition]
                val newItem = newItems[newItemPosition]
                return when {
                    oldItem is DrawerListItem.Header && newItem is DrawerListItem.Header ->
                        oldItem.title == newItem.title
                    oldItem is DrawerListItem.AppItem && newItem is DrawerListItem.AppItem ->
                        oldItem.app.packageName == newItem.app.packageName &&
                            oldItem.app.label == newItem.app.label
                    else -> false
                }
            }
        })
        items = newItems
        diffResult.dispatchUpdatesTo(this)
    }

    override fun getItemId(position: Int): Long {
        return when (val item = items[position]) {
            is DrawerListItem.Header -> item.stableId
            is DrawerListItem.AppItem -> item.app.stableId
        }
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
