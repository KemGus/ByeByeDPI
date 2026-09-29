package io.github.romanvht.byedpi.services

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.AtomicFile
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.activities.TestActivity
import io.github.romanvht.byedpi.data.*
import io.github.romanvht.byedpi.strategy.DecisionKind
import io.github.romanvht.byedpi.strategy.StrategyMutator
import io.github.romanvht.byedpi.strategy.StrategyPlanner
import io.github.romanvht.byedpi.utility.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class TestService : Service() {

    companion object {
        private const val TAG = "TestService"
        private const val CHANNEL = "Proxy test"
        private const val RESULTS_FILE = "proxy_test_results.json"
        private const val NOTIFICATION_ID = 4
        private const val ADAPTIVE_ROUNDS = 3
        private const val ADAPTIVE_SEEDS = 3
        private const val ADAPTIVE_CANDIDATES = 3
        private const val SCREEN_SITES = 15
        private const val CONFIRM_TOP = 3
        private val mutableState = MutableStateFlow(TestState())
        private val resultsLock = Any()
        val state: StateFlow<TestState> = mutableState.asStateFlow()
        val isRunning: Boolean
            get() = state.value.isRunning

        fun start(context: Context) {
            if (isRunning) return
            ServiceManager.refresh(context)
            val previous = state.value
            mutableState.value = previous.copy(
                runId = previous.runId + 1,
                isRunning = true,
                isStopping = false,
                currentStrategy = 0,
                failed = false,
                wasInterrupted = false,
            )
            try {
                ContextCompat.startForegroundService(context, Intent(context, TestService::class.java).setAction(START_ACTION))
            } catch (e: Exception) {
                mutableState.value = previous.copy(failed = true)
                Log.e(TAG, "Failed to start proxy tests", e)
            }
        }

        fun stop() {
            if (!isRunning) return
            mutableState.value = state.value.copy(isStopping = true)
        }

        suspend fun awaitStopped() {
            state.first { !it.isRunning }
        }

        suspend fun loadResults(context: Context) {
            val previous = state.value
            if (previous.isLoaded || previous.isRunning) return
            val app = context.applicationContext
            val loaded = withContext(Dispatchers.IO) {
                val results = try {
                    synchronized(resultsLock) {
                        AtomicFile(File(app.filesDir, RESULTS_FILE)).openRead().bufferedReader().use { reader ->
                            val type = object : TypeToken<List<StrategyResult>>() {}.type
                            Gson().fromJson<List<StrategyResult>>(reader, type) ?: emptyList()
                        }
                    }
                } catch (_: Exception) {
                    emptyList()
                }
                previous.copy(
                    isLoaded = true,
                    strategies = results,
                    wasInterrupted = app.getPreferences().getBoolean("is_test_running", false),
                )
            }
            withContext(Dispatchers.Main.immediate) {
                if (mutableState.compareAndSet(previous, loaded)) {
                    app.getPreferences().edit { putBoolean("is_test_running", false) }
                }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gson = Gson()
    private val engineMutex = Mutex()
    private var engine: NativeEngine? = null
    private var testJob: Job? = null
    private var lastStartId = 0
    private var destroyed = false
    private var notifiedStrategy = -1
    private var notifiedCompleted = -1

    override fun onCreate() {
        super.onCreate()
        registerNotificationChannel(this, CHANNEL, R.string.title_test)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            START_ACTION -> {
                try {
                    startForeground()
                    if (!isRunning) {
                        @Suppress("DEPRECATION")
                        stopForeground(true)
                        stopSelfResult(startId)
                    } else if (testJob == null) {
                        startTesting()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to show proxy test notification", e)
                    if (testJob == null) {
                        mutableState.value = state.value.copy(isRunning = false, isStopping = false, failed = true)
                        stopSelfResult(startId)
                    } else {
                        stop()
                    }
                }
            }
            STOP_ACTION -> {
                stop()
                if (testJob == null) stopSelfResult(startId)
            }
            else -> if (testJob == null) stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        if (testJob != null) stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun startTesting() {
        val previous = state.value.copy(isRunning = false, isStopping = false)
        testJob = scope.launch(start = CoroutineStart.LAZY) {
            val testingJob = coroutineContext.job
            var settings: TestSettings? = null
            var currentStrategy: StrategyResult? = null
            var prepared = false
            var failed = false
            val strategies = mutableListOf<StrategyResult>()
            val stopWatcher = launch {
                try {
                    state.first { it.isStopping }
                    testingJob.cancel()
                } finally {
                    stopEngine()
                }
            }

            try {
                if (state.value.isStopping) {
                    throw CancellationException("Proxy test stopped")
                }
                val options = withContext(Dispatchers.IO) { loadSettings() }
                settings = options
                if (options.sites.isEmpty()) {
                    Toast.makeText(this@TestService, R.string.test_settings_domain_empty, Toast.LENGTH_LONG).show()
                    return@launch
                }
                val network = withContext(Dispatchers.IO) { NetworkProfileUtils.currentNetwork(this@TestService) }
                val book = withContext(Dispatchers.IO) { NetworkProfileUtils.load(this@TestService) }
                val known = book.scores(network.key)
                val knownWinners = book.fullWinners(network.key).toSet()
                val knownDead = book.deadSites(network.key, System.currentTimeMillis())
                val commands = StrategyPlanner.order(options.commands, known)
                val proven = commands.count { (known[it] ?: 0) > 0 }
                val dead = commands.count { known[it] == 0 }
                decide(DecisionKind.Run, "Test started on ${network.label}",
                    "${commands.size} strategies, network id ${network.key}, ${known.size} already scored here")
                decide(DecisionKind.Order, "Order: $proven proven first, ${commands.size - proven - dead} new, $dead known-dead last",
                    commands.take(3).mapIndexed { i, c -> "${i + 1}. ${known[c]?.let { "$it%" } ?: "new"}  $c" }.joinToString("\n"))
                strategies.addAll(commands.map { StrategyResult(command = it) })
                prepared = true
                getPreferences().edit(commit = true) { putBoolean("is_test_running", true) }
                publish(strategies, 0)
                ServiceManager.waitStop()

                // Sites that already load without any bypass, or that nothing ever got through, say nothing
                // about which strategy is best. Measure them once, then leave them out of the comparison.
                val direct = SiteCheckUtils(null, 0).checkSitesAsync(
                    sites = options.sites.filter { it !in knownDead },
                    requestsCount = 1,
                    requestTimeout = options.requestTimeout,
                    concurrentRequests = maxOf(options.requestLimit, 50),
                    fullLog = false,
                )
                val open = direct.filter { it.second > 0 }.map { it.first }.toSet()
                val usable = StrategyPlanner.informativeSites(options.sites, open, knownDead)
                decide(DecisionKind.Run, "Without any bypass ${open.size} of ${options.sites.size} sites already load",
                    "${knownDead.size} sites are known to be unreachable here. Comparing on ${usable.size} sites: " +
                        "everything blocked plus ${StrategyPlanner.CANARY_SITES} open ones as a sanity check")
                var fullOptions = options.copy(sites = usable)
                val screenSites = StrategyPlanner.sample(usable, SCREEN_SITES)
                val staged = screenSites.size < usable.size
                val screenOptions = options.copy(sites = screenSites)
                val fullTested = mutableSetOf<String>()

                suspend fun runStrategy(strategy: StrategyResult, index: Int, run: TestSettings) {
                    ensureActive()
                    currentStrategy = strategy
                    strategy.totalRequests = run.sites.size * run.requestsCount
                    publish(strategies, index + 1)
                    val configuration = withContext(Dispatchers.IO) {
                        runCatching { testConfiguration(this@TestService, strategy.command, run.host, run.port) }
                    }.getOrNull()
                    if (configuration == null || !checkStrategy(strategy, strategies, configuration, run)) {
                        resetStrategyResult(strategy, run)
                    }
                    strategy.isCompleted = true
                    publish(strategies)
                    saveResults(strategies)
                    stopEngine()
                    currentStrategy = null
                    delay(run.delaySec * 500L)
                }

                suspend fun settle(strategy: StrategyResult, title: String, full: Boolean = true) {
                    withContext(Dispatchers.IO) {
                        NetworkProfileUtils.record(this@TestService, network, strategy.command, strategy.successPercentage, full)
                    }
                    decide(DecisionKind.Result, "${strategy.successPercentage}%  (${strategy.successCount}/${strategy.totalRequests})  $title".trim(), strategy.command)
                }

                // One run can be lucky or unlucky: measure again and keep the average.
                suspend fun confirm(strategy: StrategyResult) {
                    val firstCount = strategy.successCount
                    val firstPercent = strategy.successPercentage
                    strategy.isCompleted = false
                    strategy.currentProgress = 0
                    strategy.successCount = 0
                    strategy.siteResults.clear()
                    runStrategy(strategy, strategies.indexOf(strategy), fullOptions)
                    val secondPercent = strategy.successPercentage
                    strategy.successCount = StrategyPlanner.average(firstCount, strategy.successCount)
                    strategy.note = "average of two runs: $firstPercent% and $secondPercent%"
                    publish(strategies)
                    saveResults(strategies)
                    settle(strategy, "average of $firstPercent% and $secondPercent%")
                }

                for ((index, strategy) in strategies.toList().withIndex()) {
                    if (strategy.command in knownWinners) {
                        // Already proven here: measure it properly right away instead of screening it again.
                        runStrategy(strategy, index, fullOptions)
                        settle(strategy, "full test of an earlier winner, ${fullOptions.sites.size} sites")
                        fullTested.add(strategy.command)
                    } else {
                        runStrategy(strategy, index, screenOptions)
                        settle(strategy, if (staged) "quick screen, ${screenSites.size} sites" else "", full = !staged)
                        if (!staged) fullTested.add(strategy.command)
                    }
                }

                if (staged) {
                    val screened = strategies.filter { it.command !in fullTested }.associate { it.command to it.successPercentage }
                    val promoted = StrategyPlanner.seeds(screened, StrategyPlanner.promoteCount(screened.size))
                    if (promoted.isEmpty()) {
                        decide(DecisionKind.Stop, "Nothing new passed the quick screen", "No untested strategy got a single site through")
                    } else {
                        decide(DecisionKind.Order, "Quick screen done: full test for the top ${promoted.size} of ${screened.size}",
                            promoted.joinToString("\n") { "${screened[it]}%  $it" })
                        for (command in promoted) {
                            val strategy = strategies.first { it.command == command }
                            strategy.isCompleted = false
                            strategy.currentProgress = 0
                            strategy.successCount = 0
                            strategy.siteResults.clear()
                            runStrategy(strategy, strategies.indexOf(strategy), fullOptions)
                            settle(strategy, "full test, ${fullOptions.sites.size} sites")
                            fullTested.add(command)
                        }
                    }
                }

                // Sites that not one fully tested strategy could load are dead weight from here on.
                val fullRuns = strategies.filter { it.command in fullTested }
                val newDead = StrategyPlanner.deadSites(fullRuns.map { run -> run.siteResults.associate { it.site to it.successCount } })
                    .filter { it !in open }.toSet()
                if (newDead.isNotEmpty() && fullOptions.sites.size - newDead.size >= 10) {
                    fullRuns.forEach { run ->
                        run.siteResults.removeAll { it.site in newDead }
                        run.totalRequests = run.siteResults.sumOf { it.totalCount }
                    }
                    fullOptions = fullOptions.copy(sites = fullOptions.sites.filter { it !in newDead })
                    publish(strategies)
                    saveResults(strategies)
                    withContext(Dispatchers.IO) { NetworkProfileUtils.markDead(this@TestService, network, newDead) }
                    decide(DecisionKind.Skip, "Ignoring ${newDead.size} sites nothing can reach",
                        "Scores now cover ${fullOptions.sites.size} sites. Remembered for this network for a week.\n" + newDead.take(5).joinToString(", "))
                }

                fun fullScores() = strategies.filter { it.command in fullTested }.associate { it.command to it.successPercentage }

                val finalists = StrategyPlanner.seeds(fullScores(), CONFIRM_TOP)
                if (finalists.isNotEmpty()) {
                    decide(DecisionKind.Order, "Confirming the top ${finalists.size} with a second run",
                        "One run can be lucky or unlucky, so their score becomes the average of two")
                    for (command in finalists) confirm(strategies.first { it.command == command })
                }

                if (getPreferences().getBoolean("byedpi_proxytest_adaptive", true)) {
                    val tested = strategies.map { it.command }.toMutableSet()
                    var best = fullScores().values.maxOrNull() ?: 0
                    for (round in 1..ADAPTIVE_ROUNDS) {
                        if (best >= 100) break
                        val scores = fullScores()
                        var seeds = StrategyPlanner.seeds(scores, ADAPTIVE_SEEDS)
                        if (seeds.isEmpty()) {
                            seeds = listOfNotNull(withContext(Dispatchers.IO) { NetworkProfileUtils.load(this@TestService).best(network.key) })
                            if (seeds.isNotEmpty()) decide(DecisionKind.Seed, "Round $round: nothing worked this run", "Falling back to the saved best for ${network.label}")
                        } else {
                            decide(DecisionKind.Seed, "Round $round: improving the top ${seeds.size}",
                                seeds.joinToString("\n") { "${scores[it]}%  $it" })
                        }
                        val generated = seeds.flatMap { seed ->
                            StrategyMutator.candidates(seed, ADAPTIVE_CANDIDATES, kotlin.random.Random, tested).map { seed to it }
                        }.distinctBy { it.second }
                        if (generated.isEmpty()) {
                            decide(DecisionKind.Stop, "Round $round: no new variations left", "Every nearby variation was already tested")
                            break
                        }
                        var improved = false
                        for ((seed, command) in generated) {
                            tested.add(command)
                            val parent = scores[seed] ?: known[seed] ?: 0
                            decide(DecisionKind.Try, "Trying a variation of a $parent% strategy", "${StrategyMutator.describe(seed, command)}\n$command")
                            val strategy = StrategyResult(command = command)
                            strategies.add(strategy)
                            runStrategy(strategy, strategies.lastIndex, fullOptions)
                            fullTested.add(command)
                            fun verdict(score: Int) = when {
                                score > best -> "improved best $best% -> $score%"
                                score > parent -> "better than parent ($parent% -> $score%)"
                                score == 0 -> "failed"
                                else -> "no gain ($parent% -> $score%)"
                            }
                            var score = strategy.successPercentage
                            if (score > best) {
                                confirm(strategy)
                                score = strategy.successPercentage
                                decide(DecisionKind.Result, verdict(score), command)
                            } else {
                                settle(strategy, verdict(score))
                            }
                            strategy.note = listOfNotNull("generated from $seed: ${verdict(score)}", strategy.note).joinToString("; ")
                            publish(strategies)
                            saveResults(strategies)
                            if (score > best) {
                                best = score
                                improved = true
                            }
                        }
                        if (!improved) {
                            decide(DecisionKind.Stop, "Round $round found nothing better than $best%", "Stopping the search")
                            break
                        }
                    }
                    if (best >= 100) decide(DecisionKind.Stop, "Reached 100%", "Nothing left to improve")
                }
                decide(DecisionKind.Run, "Test finished", "Best result: ${fullScores().values.maxOrNull() ?: strategies.maxOfOrNull { it.successPercentage } ?: 0}%")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to run proxy tests", e)
                failed = true
            } finally {
                stopWatcher.cancel()
                withContext(NonCancellable) {
                    mutableState.value = state.value.copy(isStopping = true)
                    settings?.let { options ->
                        currentStrategy?.takeIf { !it.isCompleted }?.let {
                            resetStrategyResult(it, options)
                            it.isCompleted = true
                        }
                    }
                    stopEngine()
                    if (prepared) {
                        try {
                            saveResults(strategies)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to save proxy test results", e)
                            failed = true
                        }
                        getPreferences().edit(commit = true) { putBoolean("is_test_running", false) }
                    }
                    testJob = null
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                    stopSelfResult(lastStartId)
                    mutableState.value = if (prepared) TestState(
                        runId = previous.runId,
                        isLoaded = true,
                        strategies = snapshot(strategies),
                        failed = failed,
                    ) else previous.copy(failed = failed)
                }
            }
        }
        testJob?.start()
    }

    private suspend fun checkStrategy(
        strategy: StrategyResult,
        strategies: List<StrategyResult>,
        configuration: Configuration,
        settings: TestSettings,
    ): Boolean {
        return try {
            val current = startEngine(configuration)
            supervisorScope {
                val engineExit = async { current.awaitExit() }
                val hopeless = CompletableDeferred<Boolean>()
                var checked = 0
                val check = async {
                    delay(settings.delaySec * 500L)
                    val host = when (configuration.host) {
                        "0.0.0.0" -> "127.0.0.1"
                        "::", "[::]" -> "::1"
                        else -> configuration.host
                    }
                    SiteCheckUtils(host, configuration.port).checkSitesAsync(
                        sites = settings.sites,
                        requestsCount = settings.requestsCount,
                        requestTimeout = settings.requestTimeout,
                        concurrentRequests = settings.requestLimit,
                        fullLog = true,
                        onSiteChecked = { site, successCount, countRequests ->
                            withContext(Dispatchers.Main.immediate) {
                                strategy.currentProgress += countRequests
                                strategy.successCount += successCount
                                strategy.siteResults.add(SiteResult(site, successCount, countRequests))
                                publish(strategies)
                                checked++
                                if (StrategyPlanner.isHopeless(checked, strategy.successCount) && hopeless.complete(false)) {
                                    decide(DecisionKind.Skip, "Dropped early: 0 of the first $checked sites got through", strategy.command)
                                }
                            }
                        }
                    )
                    true
                }
                try {
                    select {
                        engineExit.onAwait { false }
                        check.onAwait { it }
                        hopeless.onAwait { it }
                    }
                } finally {
                    engineExit.cancel()
                    check.cancel()
                }
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            if (state.value.isStopping) throw CancellationException("Proxy test stopped")
            Log.e(TAG, "Proxy test engine failed", e)
            Toast.makeText(this, getString(R.string.failed_to_start, Sender.Proxy.name), Toast.LENGTH_SHORT).show()
            false
        }
    }

    private suspend fun startEngine(configuration: Configuration): NativeEngine {
        if (state.value.isStopping) throw CancellationException("Proxy test stopped")
        val current = NativeEngine(applicationContext, Mode.Proxy, foreground = false)
        engine = current
        current.start(configuration)
        return current
    }

    private suspend fun stopEngine() = withContext(NonCancellable) {
        engineMutex.withLock {
            val current = engine ?: return@withLock
            var stopFailed = false
            while (true) {
                try {
                    current.stop()
                    engine = null
                    break
                } catch (e: Exception) {
                    if (!stopFailed) Log.e(TAG, "Failed to stop proxy test engine", e)
                    stopFailed = true
                    delay(500)
                }
            }
        }
    }

    private fun loadSettings(): TestSettings {
        val prefs = getPreferences()
        val userCommands = prefs.getBoolean("byedpi_proxytest_usercommands", false)
        val sniValue = prefs.getStringNotNull("byedpi_proxytest_sni", "google.com")
        val content = if (userCommands) prefs.getStringNotNull("byedpi_proxytest_commands", "")
        else assets.open("proxytest_strategies.list").bufferedReader().use { it.readText() }
        val commands = content.replace("{sni}", "\"${sniValue}\"").lines().map { it.trim() }.filter { it.isNotEmpty() }
        val host = prefs.getStringNotNull("byedpi_proxy_ip", "127.0.0.1")
        val port = prefs.getIntStringNotNull("byedpi_proxy_port", 1080)
        val delaySec = prefs.getIntStringNotNull("byedpi_proxytest_delay", 1).coerceAtLeast(0)
        val requestsCount = prefs.getIntStringNotNull("byedpi_proxytest_requests", 1).coerceAtLeast(1)
        val requestTimeout = prefs.getLongStringNotNull("byedpi_proxytest_timeout", 5).coerceAtLeast(1)
        val requestLimit = prefs.getIntStringNotNull("byedpi_proxytest_limit", 20).coerceAtLeast(1)
        DomainListUtils.syncLists(this)
        return TestSettings(DomainListUtils.getActiveDomains(this).toList(), commands, host, port,
            delaySec, requestsCount, requestTimeout, requestLimit)
    }

    private fun decide(kind: DecisionKind, title: String, detail: String = "") =
        DecisionLogUtils.add(this, kind, title, detail)

    private fun resetStrategyResult(strategy: StrategyResult, settings: TestSettings) {
        strategy.successCount = 0
        strategy.currentProgress = 0
        strategy.siteResults.clear()
        strategy.siteResults.addAll(settings.sites.map { SiteResult(it, 0, settings.requestsCount) })
    }

    private fun snapshot(strategies: List<StrategyResult>): List<StrategyResult> =
        strategies.map { it.copy(siteResults = it.siteResults.toMutableList()) }

    private suspend fun saveResults(strategies: List<StrategyResult>) {
        val results = snapshot(strategies)
        withContext(Dispatchers.IO) {
            synchronized(resultsLock) {
                val file = AtomicFile(File(filesDir, RESULTS_FILE))
                val output = file.startWrite()
                try {
                    output.write(gson.toJson(results).toByteArray(Charsets.UTF_8))
                    file.finishWrite(output)
                } catch (e: Exception) {
                    file.failWrite(output)
                    throw e
                }
            }
        }
    }

    private fun publish(strategies: List<StrategyResult>, currentStrategy: Int = state.value.currentStrategy) {
        mutableState.value = state.value.copy(isLoaded = true, strategies = snapshot(strategies), currentStrategy = currentStrategy)
        val completed = strategies.count { it.isCompleted }
        if (!destroyed && (notifiedStrategy != currentStrategy || notifiedCompleted != completed)) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification())
            notifiedStrategy = currentStrategy
            notifiedCompleted = completed
        }
    }

    private fun notification(): Notification {
        val value = state.value
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.title_test))
            .setContentText(getString(R.string.test_process, value.currentStrategy, value.strategies.size))
            .setProgress(value.strategies.size, value.strategies.count { it.isCompleted }, value.currentStrategy == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, TestActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .addAction(0, getString(R.string.test_stop), PendingIntent.getService(this, 0,
                Intent(this, TestService::class.java).setAction(STOP_ACTION), PendingIntent.FLAG_IMMUTABLE))
            .build()
    }

    private fun startForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification())
        }
        notifiedStrategy = state.value.currentStrategy
        notifiedCompleted = state.value.strategies.count { it.isCompleted }
    }
}
