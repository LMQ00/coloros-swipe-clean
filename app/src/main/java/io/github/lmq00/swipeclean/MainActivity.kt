package io.github.lmq00.swipeclean

import android.os.Bundle
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var adapter: AppListAdapter
    private lateinit var loading: View
    private lateinit var empty: View

    private var all: List<AppEntry> = emptyList()
    private var query: String = ""
    private var showSystem: Boolean = false
    private var loaded: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        adapter = AppListAdapter { entry, mode ->
            ConfigStore.setMode(this, entry.packageName, mode)
        }

        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        findViewById<EditText>(R.id.search).addTextChangedListener(
            onText = {
                query = it
                render()
            },
        )

        findViewById<MaterialSwitch>(R.id.show_system).setOnCheckedChangeListener { _, checked ->
            showSystem = checked
            render()
        }

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
            val apps = AppRepository.load(this)
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
        val visible = all.filter { entry ->
            (showSystem || !entry.system) &&
                (query.isBlank() ||
                    entry.label.contains(query, ignoreCase = true) ||
                    entry.packageName.contains(query, ignoreCase = true))
        }
        adapter.submit(visible, modes)
        empty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
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