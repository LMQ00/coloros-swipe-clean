package io.github.lmq00.swipeclean

import android.os.Bundle
import android.widget.CheckBox
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var adapter: AppListAdapter
    private var all: List<AppEntry> = emptyList()
    private var query: String = ""
    private var showSystem: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        adapter = AppListAdapter(
            modeLabel = { mode ->
                when (mode) {
                    Config.MODE_KEEP -> getString(R.string.mode_keep)
                    Config.MODE_KILL -> getString(R.string.mode_kill)
                    else -> getString(R.string.mode_default)
                }
            },
            onModeChanged = { entry, mode ->
                ConfigStore.setMode(this, entry.packageName, mode)
            },
        )

        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        findViewById<EditText>(R.id.search).addTextChangedListener(
            onText = { query = it },
        )

        findViewById<CheckBox>(R.id.show_system).setOnCheckedChangeListener { _, checked ->
            showSystem = checked
            render()
        }

        load()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun load() {
        worker.execute {
            val apps = AppRepository.load(this)
            runOnUiThread {
                all = apps
                render()
            }
        }
    }

    private fun render() {
        val prefs = ConfigStore.prefs(this)
        val modes = HashMap<String, Int>(all.size)
        for (entry in all) {
            modes[entry.packageName] = ConfigStore.modeOf(prefs, entry.packageName)
        }
        val visible = all.filter { entry ->
            (showSystem || !entry.system) &&
                (query.isBlank() ||
                    entry.label.contains(query, ignoreCase = true) ||
                    entry.packageName.contains(query, ignoreCase = true))
        }
        adapter.submit(visible, modes)
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