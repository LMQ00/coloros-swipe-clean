package io.github.lmq00.swipeclean

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.MaterialColors

class AppListAdapter(
    private val modeLabel: (Int) -> String,
    private val onClick: (AppEntry, Int) -> Unit,
    private val onLongClick: (AppEntry) -> Unit,
) : RecyclerView.Adapter<AppListAdapter.Holder>() {

    private var items: List<AppEntry> = emptyList()
    private var modes: Map<String, Int> = emptyMap()
    private var selecting: Boolean = false
    private var selected: Set<String> = emptySet()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(
        items: List<AppEntry>,
        modes: Map<String, Int>,
        selecting: Boolean,
        selected: Set<String>,
    ) {
        this.items = items
        this.modes = modes
        this.selecting = selecting
        this.selected = selected
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = items[position]
        holder.bind(
            entry = entry,
            current = modes[entry.packageName] ?: Config.MODE_DEFAULT,
            selecting = selecting,
            checked = entry.packageName in selected,
        )
    }

    inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val icon: ImageView = itemView.findViewById(R.id.icon)
        private val label: TextView = itemView.findViewById(R.id.label)
        private val pkg: TextView = itemView.findViewById(R.id.pkg)
        private val mode: TextView = itemView.findViewById(R.id.mode)
        private val chevron: ImageView = itemView.findViewById(R.id.chevron)
        private val check: MaterialCheckBox = itemView.findViewById(R.id.check)

        private val placeholder = itemView.context.packageManager.defaultActivityIcon

        fun bind(entry: AppEntry, current: Int, selecting: Boolean, checked: Boolean) {
            label.text = entry.label
            pkg.text = entry.packageName
            mode.text = modeLabel(current)
            // 已设置过的应用用主色标出，未设置的保持弱化。
            mode.setTextColor(
                MaterialColors.getColor(
                    itemView,
                    if (current == Config.MODE_DEFAULT) {
                        com.google.android.material.R.attr.colorOnSurfaceVariant
                    } else {
                        com.google.android.material.R.attr.colorPrimary
                    },
                ),
            )

            // 选择模式：箭头换成勾选框，选中的行铺一层底色。
            chevron.visibility = if (selecting) View.GONE else View.VISIBLE
            check.visibility = if (selecting) View.VISIBLE else View.GONE
            check.isChecked = checked
            itemView.setBackgroundColor(
                if (checked) {
                    MaterialColors.getColor(
                        itemView,
                        com.google.android.material.R.attr.colorSecondaryContainer,
                    )
                } else {
                    Color.TRANSPARENT
                },
            )

            icon.tag = entry.packageName
            icon.setImageDrawable(placeholder)
            IconCache.load(itemView.context, entry.packageName) { drawable ->
                // 行可能已被回收给别的应用，落图前再确认一次。
                if (icon.tag == entry.packageName) icon.setImageDrawable(drawable)
            }

            itemView.setOnClickListener { onClick(entry, current) }
            itemView.setOnLongClickListener {
                onLongClick(entry)
                true
            }
        }
    }
}