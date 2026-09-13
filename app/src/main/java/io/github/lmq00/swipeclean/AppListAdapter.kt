package io.github.lmq00.swipeclean

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AppListAdapter(
    private val modeLabel: (Int) -> String,
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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position], modes[items[position].packageName] ?: Config.MODE_DEFAULT)
    }

    inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.icon)
        private val label: TextView = itemView.findViewById(R.id.label)
        private val pkg: TextView = itemView.findViewById(R.id.pkg)
        private val spinner: Spinner = itemView.findViewById(R.id.mode)

        private val options = listOf(Config.MODE_DEFAULT, Config.MODE_KEEP, Config.MODE_KILL)

        init {
            spinner.adapter = ArrayAdapter(
                itemView.context,
                android.R.layout.simple_spinner_dropdown_item,
                options.map(modeLabel),
            )
        }

        fun bind(entry: AppEntry, mode: Int) {
            icon.setImageDrawable(entry.icon)
            label.text = entry.label
            pkg.text = entry.packageName
            spinner.onItemSelectedListener = null
            spinner.setSelection(options.indexOf(mode).coerceAtLeast(0), false)
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    onModeChanged(entry, options[position])
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
    }
}