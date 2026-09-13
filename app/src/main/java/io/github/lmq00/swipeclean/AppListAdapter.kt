package io.github.lmq00.swipeclean

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup

class AppListAdapter(
    private val onModeChanged: (AppEntry, Int) -> Unit,
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
        private val group: MaterialButtonToggleGroup = itemView.findViewById(R.id.mode)

        private val buttons: Map<Int, MaterialButton> = mapOf(
            Config.MODE_DEFAULT to itemView.findViewById(R.id.mode_default),
            Config.MODE_KEEP to itemView.findViewById(R.id.mode_keep),
            Config.MODE_KILL to itemView.findViewById(R.id.mode_kill),
        )

        private val placeholder = itemView.context.packageManager.defaultActivityIcon

        fun bind(entry: AppEntry, mode: Int) {
            label.text = entry.label
            pkg.text = entry.packageName

            icon.tag = entry.packageName
            icon.setImageDrawable(placeholder)
            IconCache.load(itemView.context, entry.packageName) { drawable ->
                // 行可能已被回收给别的应用，落图前再确认一次。
                if (icon.tag == entry.packageName) icon.setImageDrawable(drawable)
            }

            group.clearOnButtonCheckedListeners()
            buttons[mode]?.let { group.check(it.id) }
            group.addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (!isChecked) return@addOnButtonCheckedListener
                val selected = buttons.entries.firstOrNull { it.value.id == checkedId }?.key
                    ?: return@addOnButtonCheckedListener
                onModeChanged(entry, selected)
            }
        }
    }
}