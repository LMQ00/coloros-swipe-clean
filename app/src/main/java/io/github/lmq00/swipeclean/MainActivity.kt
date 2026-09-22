package io.github.lmq00.swipeclean

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.radiobutton.MaterialRadioButton
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var adapter: AppListAdapter
    private lateinit var loading: View
    private lateinit var empty: View

    private lateinit var titleBar: View
    private lateinit var selectBar: View
    private lateinit var batchBar: View
    private lateinit var selectCount: TextView
    private lateinit var selectAll: TextView
    private lateinit var batchButtons: List<Pair<MaterialButton, Int>>

    private var all: List<AppEntry> = emptyList()
    private var visible: List<AppEntry> = emptyList()
    private var query: String = ""
    private var showSystem: Boolean = false
    private var loaded: Boolean = false

    /** 批量选择模式：列表改多选，底部出现批量操作栏。长按任意行进入。 */
    private var selecting: Boolean = false
    private val selected = mutableSetOf<String>()

    /** 选择模式下返回键先退出选择，而不是退出界面。 */
    private val backToExitSelection = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = exitSelection()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        adapter = AppListAdapter(
            modeLabel = { mode -> getString(modeLabelRes(mode)) },
            onClick = ::onRowClick,
            onLongClick = ::onRowLongClick,
        )

        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
            ContextCompat.getDrawable(this@MainActivity, R.drawable.divider_inset)?.let { divider ->
                addItemDecoration(
                    DividerItemDecoration(this@MainActivity, DividerItemDecoration.VERTICAL)
                        .apply { setDrawable(divider) },
                )
            }
        }

        findViewById<EditText>(R.id.search).addTextChangedListener(
            onText = {
                query = it
                render()
            },
        )

        findViewById<Chip>(R.id.show_system).setOnCheckedChangeListener { _, checked ->
            showSystem = checked
            render()
        }

        titleBar = findViewById(R.id.title_bar)
        selectBar = findViewById(R.id.select_bar)
        batchBar = findViewById(R.id.batch_bar)
        selectCount = findViewById(R.id.select_count)
        selectAll = findViewById(R.id.select_all)

        batchButtons = listOf(
            findViewById<MaterialButton>(R.id.batch_default) to Config.MODE_DEFAULT,
            findViewById<MaterialButton>(R.id.batch_keep) to Config.MODE_KEEP,
            findViewById<MaterialButton>(R.id.batch_kill) to Config.MODE_KILL,
        )
        for ((button, mode) in batchButtons) button.setOnClickListener { applyBatch(mode) }

        findViewById<View>(R.id.select_exit).setOnClickListener { exitSelection() }
        selectAll.setOnClickListener { toggleSelectAll() }

        onBackPressedDispatcher.addCallback(this, backToExitSelection)

        loading = findViewById(R.id.loading)
        empty = findViewById(R.id.empty)

        load()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun load() {
        worker.execute {
            // 先枚举分身：迁移需要知道每个包实际存在哪些分身 user。
            val dual = AppRepository.loadDualApps(this)
            ConfigStore.migrate(this, dual)
            val apps = AppRepository.load(this, dual)
            runOnUiThread {
                all = apps
                loaded = true
                loading.visibility = View.GONE
                render()
            }
        }
    }

    private fun render() {
        if (!loaded) return
        val modes = ConfigStore.modeMap(ConfigStore.prefs(this))
        visible = all.filter { entry ->
            (showSystem || !entry.system) &&
                (query.isBlank() ||
                    entry.label.contains(query, ignoreCase = true) ||
                    entry.packageName.contains(query, ignoreCase = true))
        }
        adapter.submit(visible, modes, selecting, selected.toSet())
        empty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE

        titleBar.visibility = if (selecting) View.GONE else View.VISIBLE
        selectBar.visibility = if (selecting) View.VISIBLE else View.GONE
        batchBar.visibility = if (selecting) View.VISIBLE else View.GONE
        backToExitSelection.isEnabled = selecting

        if (selecting) {
            selectCount.text = getString(R.string.select_count, selected.size)
            // 「全选」作用于当前筛选结果，因此可以先用搜索/系统应用筛选再一次性勾选。
            val allSelected = visible.isNotEmpty() && visible.all { it.key in selected }
            selectAll.setText(if (allSelected) R.string.select_none else R.string.select_all)
            for ((button, _) in batchButtons) button.isEnabled = selected.isNotEmpty()
        }
    }

    private fun onRowClick(entry: AppEntry, current: Int) {
        if (selecting) {
            if (!selected.remove(entry.key)) selected.add(entry.key)
            render()
        } else {
            showModePicker(entry, current)
        }
    }

    private fun onRowLongClick(entry: AppEntry) {
        if (!selecting) {
            selecting = true
            selected.clear()
        }
        selected.add(entry.key)
        render()
    }

    private fun exitSelection() {
        selecting = false
        selected.clear()
        render()
    }

    private fun toggleSelectAll() {
        val allSelected = visible.isNotEmpty() && visible.all { it.key in selected }
        for (entry in visible) {
            if (allSelected) selected.remove(entry.key) else selected.add(entry.key)
        }
        render()
    }

    private fun applyBatch(mode: Int) {
        val keys = selected.toList()
        if (keys.isEmpty()) return
        ConfigStore.setModes(this, keys, mode)
        Toast.makeText(
            this,
            getString(R.string.batch_applied, keys.size, getString(modeLabelRes(mode))),
            Toast.LENGTH_SHORT,
        ).show()
        exitSelection()
    }

    private fun showModePicker(entry: AppEntry, current: Int) {
        val sheet = layoutInflater.inflate(R.layout.sheet_mode, null)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(sheet)
        sheet.findViewById<TextView>(R.id.sheet_title).text = entry.title(this)

        val options = listOf(
            R.id.opt_default to Config.MODE_DEFAULT,
            R.id.opt_keep to Config.MODE_KEEP,
            R.id.opt_kill to Config.MODE_KILL,
        )
        for ((id, mode) in options) {
            val option = sheet.findViewById<MaterialRadioButton>(id)
            option.isChecked = mode == current
            option.setOnClickListener {
                ConfigStore.setMode(this, entry.key, mode)
                render()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun modeLabelRes(mode: Int): Int = when (mode) {
        Config.MODE_KEEP -> R.string.mode_keep
        Config.MODE_KILL -> R.string.mode_kill
        else -> R.string.mode_default
    }
}

/** 只关心文本变化，不引入额外依赖。 */
private fun EditText.addTextChangedListener(onText: (String) -> Unit) {
    addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: android.text.Editable?) {
            onText(s?.toString().orEmpty())
        }
    })
}