package io.github.lmq00.swipeclean

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip

class AppListAdapter(
    private val modeLabel: (Int) -> String,
    private val onModePick: (AppEntry, Int) -> Unit,
) : RecyclerView.Adapter<AppListAdapter.Holder>() {

    private var items: List<AppEntry> = emptyList()
    private var modes: Map<String, Int> = emptyMap()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(items: List<AppEntry>, modes: Map<String, Int>) {
        this.items = items
        this.modes = modes
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = items[position]
        holder.bind(entry, modes[entry.packageName] ?: Config.MODE_DEFAULT)
    }

    inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val icon: ImageView = itemView.findViewById(R.id.icon)
        private val label: TextView = itemView.findViewById(R.id.label)
        private val pkg: TextView = itemView.findViewById(R.id.pkg)
        private val chip: Chip = itemView.findViewById(R.id.mode)

        private val placeholder = itemView.context.packageManager.defaultActivityIcon

        fun bind(entry: AppEntry, mode: Int) {
            label.text = entry.label
            pkg.text = entry.packageName
            chip.text = modeLabel(mode)

            icon.tag = entry.packageName
            icon.setImageDrawable(placeholder)
            IconCache.load(itemView.context, entry.packageName) { drawable ->
                // 行可能已被回收给别的应用，落图前再确认一次。
                if (icon.tag == entry.packageName) icon.setImageDrawable(drawable)
            }

            itemView.setOnClickListener { onModePick(entry, mode) }
        }
    }
}