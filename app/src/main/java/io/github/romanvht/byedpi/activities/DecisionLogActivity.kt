package io.github.romanvht.byedpi.activities

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.adapters.DecisionAdapter
import io.github.romanvht.byedpi.utility.DecisionLogUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shows why the tester tried, skipped or switched to each strategy, newest at the bottom. */
class DecisionLogActivity : BaseActivity() {

    private val adapter = DecisionAdapter()
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyTextView: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_decision_log)
        setupToolbar()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        recyclerView = findViewById(R.id.decisionRecyclerView)
        emptyTextView = findViewById(R.id.emptyTextView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        lifecycleScope.launch {
            withContext(Dispatchers.IO) { DecisionLogUtils.load(applicationContext) }
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DecisionLogUtils.entries.collect { items ->
                    val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                    val followTail = adapter.itemCount == 0 ||
                        layoutManager.findLastCompletelyVisibleItemPosition() >= adapter.itemCount - 1
                    adapter.submit(items)
                    emptyTextView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                    if (followTail && items.isNotEmpty()) recyclerView.scrollToPosition(items.lastIndex)
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_decision_log, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_copy_decisions -> {
            copy()
            true
        }
        R.id.action_clear_decisions -> {
            DecisionLogUtils.clear(this)
            true
        }
        android.R.id.home -> {
            finish()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun copy() {
        val text = DecisionLogUtils.entries.value.joinToString("\n\n") {
            "${DateFormat.format("HH:mm:ss", it.time)} [${it.kind}] ${it.title}" +
                if (it.detail.isEmpty()) "" else "\n${it.detail}"
        }
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("decision_log", text))
        Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show()
    }
}
