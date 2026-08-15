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
    // Optimization #8: stable ID ek hi baar (object banate waqt) calculate ho
    // jaati hai, taaki RecyclerView ke har getItemId() call par baar baar
    // String concatenation + hashCode() na karna pade.
    val stableId: Long = (packageName + className).hashCode().toLong()
}

// Modification: app drawer ke icon aur label ka size — icon-ke-beech-ka-gap
// (column ke andar left/right white space) ko target % tak laane ke liye
// LauncherHomeActivity dono values ek sath (proportionally) badhata hai.
// Ye sirf ek baar (per screen-width+column-count combination) calculate
// hoke cache ho jaata hai, isliye baar baar recompute nahi karna padta.
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
        // Modification: icon/label ka size sirf ek baar, jab holder create
        // hota hai, apply hota hai (har bind pe nahi) — kyunki poore drawer
        // mein sabhi items ka size same hota hai, isliye per-bind repeat
        // karne ki zaroorat nahi (extra measure/layout cost bachta hai).
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
        // Bug fix: clearAnimation() sirf legacy Animation cancel karta hai,
        // hamari fade-in animate() (ViewPropertyAnimator) use karti hai jo
        // isse cancel nahi hoti — isi wajah se kabhi kabhi ek purani animation
        // baad mein chal ke item ko wapas invisible (alpha 0) kar deti thi,
        // jisse row mein "khaali gap" dikhta tha.
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
            // Optimization #7: sirf alpha fade (translationY hataya) — ek
            // property animate karna do properties se halka padta hai, isliye
            // bind hote waqt overhead kam ho jaata hai aur scroll smooth rehta hai.
            itemView.alpha = 0f
            itemView.animate()
                .alpha(1f)
                .setDuration(140L)
                .setStartDelay((position % 20) * 6L)
                .start()
        } else {
            // Bug fix: yahan bhi clearAnimation() ki jagah animate().cancel()
            // use karna zaroori hai — warna koi purani in-flight animation
            // baad mein alpha ko wapas 0 kar sakti hai.
            itemView.animate().cancel()
            itemView.alpha = 1f
        }
    }
}
