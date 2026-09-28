package io.github.romanvht.byedpi.utility

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.github.romanvht.byedpi.strategy.Decision
import io.github.romanvht.byedpi.strategy.DecisionKind
import io.github.romanvht.byedpi.strategy.DecisionLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Shared, persisted trail of why the tester tried or switched to each strategy. */
object DecisionLogUtils {
    private const val TAG = "DecisionLog"
    private const val FILE = "decision_log.json"
    private val lock = Any()
    private val scope = CoroutineScope(Dispatchers.IO)
    private var log: DecisionLog? = null
    private val mutableEntries = MutableStateFlow<List<Decision>>(emptyList())
    val entries: StateFlow<List<Decision>> = mutableEntries.asStateFlow()

    fun load(context: Context) {
        synchronized(lock) {
            if (log != null) return
            val saved = try {
                AtomicFile(File(context.filesDir, FILE)).openRead().bufferedReader().use {
                    Gson().fromJson<List<Decision>>(it, object : TypeToken<List<Decision>>() {}.type)
                }
            } catch (_: Exception) {
                null
            }
            log = DecisionLog(initial = saved.orEmpty())
            mutableEntries.value = log!!.snapshot()
        }
    }

    fun add(context: Context, kind: DecisionKind, title: String, detail: String = "") {
        Log.i(TAG, "[$kind] $title${if (detail.isEmpty()) "" else " | $detail"}")
        val app = context.applicationContext
        load(app)
        synchronized(lock) {
            log!!.add(Decision(System.currentTimeMillis(), kind, title, detail))
            mutableEntries.value = log!!.snapshot()
        }
        save(app)
    }

    fun clear(context: Context) {
        val app = context.applicationContext
        load(app)
        synchronized(lock) {
            log!!.clear()
            mutableEntries.value = emptyList()
        }
        save(app)
    }

    private fun save(context: Context) {
        scope.launch {
            synchronized(lock) {
                val file = AtomicFile(File(context.filesDir, FILE))
                val output = file.startWrite()
                try {
                    output.write(Gson().toJson(log!!.snapshot()).toByteArray(Charsets.UTF_8))
                    file.finishWrite(output)
                } catch (e: Exception) {
                    file.failWrite(output)
                    Log.e(TAG, "Failed to save decision log", e)
                }
            }
        }
    }
}
