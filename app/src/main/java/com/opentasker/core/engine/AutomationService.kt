package com.opentasker.core.engine

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.opentasker.app.MainActivity
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.bubbles.FlashBubbleOverlayManager
import com.opentasker.core.bubbles.FreezeBubbleOverlayManager
import com.opentasker.automation.app.AppUsageMonitor
import com.opentasker.automation.network.ConnectivityMonitor
import com.opentasker.automation.network.WiFiNetworkMonitor
import com.opentasker.automation.sensor.FoldDetector
import com.opentasker.automation.sensor.OrientationDetector
import com.opentasker.automation.sensor.ShakeDetector
import com.opentasker.automation.scheduler.TimeEventScheduler
import com.opentasker.core.actions.NotificationActionReceiver
import com.opentasker.core.actions.NotificationTaskBindings
import com.opentasker.core.actions.NotificationTaskCandidate
import com.opentasker.core.actions.NotificationTaskReference
import com.opentasker.core.actions.NotificationTaskResolution
import com.opentasker.core.external.AutomationTargetContract
import com.opentasker.core.external.ExternalExecutions
import com.opentasker.core.external.ExternalExecutionState
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.storage.recoveryMessage
import com.opentasker.core.contexts.BluetoothContextEvents
import com.opentasker.core.contexts.BootContextEvents
import com.opentasker.core.contexts.BroadcastContextEvents
import com.opentasker.core.contexts.CameraMicContextEvents
import com.opentasker.core.contexts.PackageContextEvents
import com.opentasker.core.contexts.PluginConditionSubscription
import com.opentasker.core.contexts.PluginConditionSubscriptions
import androidx.room.withTransaction
import com.opentasker.core.model.AutomationMode
import com.opentasker.core.platform.AudioForegroundServiceEligibility
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.ProfileLifecyclePolicy
import com.opentasker.core.model.ProfileLifetime
import com.opentasker.core.model.ProfileOverflowPolicy
import com.opentasker.core.model.Task
import com.opentasker.core.storage.toEntity
import com.opentasker.core.storage.AutoStartSettings
import com.opentasker.core.storage.RunLogRetentionSettings
import com.opentasker.core.storage.minimumTimestamp
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.util.Collections

/**
 * Foreground service that hosts the trigger engine.
 *
 * Subscribes to context sources, evaluates active profiles, and dispatches tasks.
 * A foreground service is required on modern Android (Doze/App Standby) for the
 * automation engine to evaluate triggers reliably.
 */
class AutomationService : Service() {
    // SupervisorJob: a failing child coroutine (a matcher, the reload collector, a prune) must NOT cancel
    // the parent and take down every engine coroutine with it — that froze the clock while the process
    // stayed alive. Children now fail in isolation; the heartbeat re-arms anything that does die.
    private val job = SupervisorJob()
    /**
     * Without a handler an exception from any engine coroutine reaches the default handler and
     * kills the process. The realistic trigger is the database not being ready inside its 30 s
     * deadline on a cold boot that is applying a staged restore, which the watchdog and boot alarm
     * would then retry into a crash loop. The engine is restarted by those same mechanisms, so
     * logging and letting this launch fail is the recoverable behaviour.
     */
    private val engineExceptionHandler = CoroutineExceptionHandler { _, error ->
        AppLogger.error(TAG, "Engine coroutine failed", error)
    }
    // Dispatchers.Main stays the fork's default: the engine drives scene overlays and the edge bar
    // through WindowManager, which must be touched from the main thread. Room's suspend DAOs use
    // Room's own executor and task execution hops to Dispatchers.IO inside executeAndLogTask, so
    // this does not put automation work on the UI thread.
    private val scope = CoroutineScope(Dispatchers.Main + job + engineExceptionHandler)
    private val db by lazy { OpenTaskerApp_NoHilt.db }
    private val timeEventScheduler by lazy { TimeEventScheduler(this) }
    private val wifiNetworkMonitor by lazy { WiFiNetworkMonitor(this) }
    private val connectivityMonitor by lazy { ConnectivityMonitor(this) }
    private val appUsageMonitor by lazy { AppUsageMonitor(this) }
    private val shakeDetector by lazy { ShakeDetector(this) }
    private val orientationDetector by lazy { OrientationDetector(this) }
    private val foldDetector by lazy { FoldDetector(this) }
    private val hardwareKeyListener by lazy { com.opentasker.core.input.ShizukuKeyEventListener() }
    private val runLogRetentionSettings by lazy { RunLogRetentionSettings(this) }
    
    private val cooldownStore by lazy { CooldownStore(this) }
    private val executionAdmission by lazy { ExecutionAdmissionController.persisted(this) }
    private val matchers = Collections.synchronizedMap(mutableMapOf<Long, ProfileMatcher>())
    /**
     * Priority arbitration state: every profile currently *matched*, whether or not it dispatched.
     *
     * Arbitration needs the matched set, not the running set — a higher-priority profile outranks a
     * lower one for as long as its conditions hold, including while its own task has already
     * finished. [admittedProfiles] then records which of them were ADMITTED — passed the lifetime
     * and priority checks — which is what deactivation consults to decide whether an exit task is
     * owed. Admission, not dispatch: a profile carrying only an exit task is admitted and still owes
     * it, while a profile that was outranked never acted and has nothing to undo.
     *
     * Both are read under their own lock: the matcher runs one coroutine per profile, so iterating
     * these maps unsynchronized can throw ConcurrentModificationException under a burst.
     */
    private val matchedProfiles = Collections.synchronizedMap(mutableMapOf<Long, Profile>())
    private val admittedProfiles = Collections.synchronizedSet(mutableSetOf<Long>())
    private val profileCooldowns = Collections.synchronizedMap(mutableMapOf<Long, Long>()) // profileId -> cooldownUntilMs
    private val matcherJobs = Collections.synchronizedMap(mutableMapOf<Long, Job>()) // Track jobs for cleanup
    private val profileTaskJobs = Collections.synchronizedMap(mutableMapOf<Long, Job>())
    // Each queued run carries its own event snapshot, so a burst of different-source events (e.g.
    // notifications from different apps) each runs with ITS values, not the latest one's.
    private data class QueuedRun(val task: Task, val eventVars: Map<String, String>)
    private val queuedProfileTasks = Collections.synchronizedMap(mutableMapOf<Long, ArrayDeque<QueuedRun>>())
    @Volatile private var lastRunLogPruneAt = 0L
    @Volatile private var audioForegroundServiceEligibility = AudioForegroundServiceEligibility.BACKGROUND_STARTED
    // Each sensor/usage monitor runs only while an enabled profile needs it — tracked so we start/stop
    // on transitions (白い熊: no idle CPU drain, no wakelock, phone can deep-sleep).
    private var shakeOn = false
    private var orientationOn = false
    private var foldOn = false
    private var appUsageOn = false
    private var autoStartDone = false
    /** Set when onCreate refused to start (the app is stopped) — onDestroy then has nothing to undo. */
    @Volatile private var refusedStart = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
        // The shutdown gate has to bite HERE, not just in onStartCommand: onCreate is what subscribes
        // every context source and spawns the Shizuku key-grabber process, and it runs first. Gating
        // only the later callback meant a refused start still built the whole engine up and tore it
        // down again — observed leaving an orphaned :keygrab process behind. startForeground above is
        // not optional even on this path: we were launched with startForegroundService and must go
        // foreground before stopping, or the system kills the process for not doing so in time.
        if (EngineShutdown.isStopped(this)) {
            AppLogger.info(TAG, "Engine create ignored — the app is stopped")
            refusedStart = true
            stopSelf()
            return
        }
        // No engine wakelock: the per-minute clock rides a Doze-exempt exact alarm and triggers ride
        // their own event sources, so the CPU is free to deep-sleep when idle (白い熊 freezes Powergenie).
        timeEventScheduler.scheduleNextMinute()
        wifiNetworkMonitor.start()
        connectivityMonitor.start()
        // Shake / orientation / app-foreground monitors are NOT started here — reloadProfiles() gates
        // them on whether an enabled profile actually uses them (applyContextSourceGating).
        hardwareKeyListener.start(this, scope)
        ContextCompat.registerReceiver(
            this,
            PackageContextEvents.receiver,
            PackageContextEvents.intentFilter(),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(
            this,
            BluetoothContextEvents.receiver,
            BluetoothContextEvents.intentFilter(),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        CameraMicContextEvents.start(this)
        // Freeze bubbles: render pending re-freeze bubbles, gated to the Desktop launcher being foreground.
        FreezeBubbleOverlayManager.start(this, scope)
        // Flash bubbles (通知明滅): per-app flashing icons + kill-all icon, left edge, Desktop-only.
        FlashBubbleOverlayManager.start(this, scope)
        profileCooldowns.putAll(cooldownStore.loadAll())
        scope.launch { pruneRunLogs(force = true) }
        // Re-arm matchers (and dynamic receivers like the broadcast trigger) whenever profiles change,
        // so enabling/importing a profile takes effect without relaunching the app. drop(1) skips the
        // initial emission — onStartCommand does the first load.
        scope.launch {
            db.profileDao().getAllAsFlow().drop(1).collect { reloadProfiles() }
        }
        isRunning = true
        EngineHeartbeat.markEngineStart()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Backstop for the shutdown flag: every caller is gated at its own entry point (so the run log
        // names what tried), but a path we missed — or a system-initiated restart — must not quietly
        // undo an "Exit app fully" either.
        if (EngineShutdown.isStopped(this)) {
            AppLogger.info(TAG, "Engine start ignored — the app is stopped")
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.getBooleanExtra(EXTRA_STARTED_FROM_VISIBLE_UI, false) == true) {
            audioForegroundServiceEligibility = AudioForegroundServiceEligibility.WHILE_IN_USE
        }
        if (intent?.action == ACTION_RUN_NOTIFICATION_TASK) {
            // Run notification-button tasks inside the foreground service's own scope instead of a
            // BroadcastReceiver's ~10 s goAsync window, so long tasks (e.g. flow.wait up to 30 min)
            // complete and log reliably without the system killing the receiver mid-run.
            val taskId = intent.getLongExtra(NotificationActionReceiver.EXTRA_TASK_ID, -1L).takeIf { it > 0 }
            val legacyName = intent.getStringExtra(NotificationActionReceiver.EXTRA_TASK_NAME)
            val buttonLabel = intent.getStringExtra(NotificationActionReceiver.EXTRA_BUTTON_LABEL)
                ?: legacyName ?: "Task ${taskId ?: "unknown"}"
            val reference = taskId?.let { NotificationTaskReference.Id(it) }
                ?: legacyName?.let { NotificationTaskReference.LegacyName(it) }
            if (reference != null) {
                scope.launch { runNotificationTask(reference, buttonLabel) }
            }
            return START_STICKY
        }
        if (intent?.action == ACTION_RUN_EXTERNAL_TASK) {
            // Upstream's protocol-v2 broker: the external RUN_TASK broadcast cannot hold its window
            // open for a task that may wait minutes, so the receiver validates and hands the run here,
            // where the foreground service owns it to completion and the caller polls QUERY_EXECUTION.
            val executionId = intent.getStringExtra(AutomationTargetContract.EXTRA_EXECUTION_ID)
            val taskId = intent.getLongExtra(AutomationTargetContract.EXTRA_TASK_ID, -1L).takeIf { it > 0 }
            val variables = externalVariables(intent)
            val runSource = AutomationTargetContract.runSourceLabel(
                intent.getStringExtra(AutomationTargetContract.EXTRA_RUN_SOURCE),
            )
            if (executionId != null && taskId != null) {
                scope.launch { runExternalTask(executionId, taskId, variables, runSource) }
            }
            return START_STICKY
        }
        val bootCompletedTrigger = intent?.action == ACTION_BOOT_COMPLETED_TRIGGER
        val rearm = intent?.action == ACTION_REARM
        scope.launch {
            if (rearm) {
                // The per-minute alarm found the tick stale (engine coroutines died while the process
                // lived). Re-arm the matchers, which relaunches the tick loop.
                if (EngineHeartbeat.isStale()) {
                    EngineHeartbeat.markRearm()
                    reloadProfiles()
                }
            } else {
                reloadProfiles()
                if (!autoStartDone) {
                    autoStartDone = true
                    runAutoStartTasks()
                }
                if (bootCompletedTrigger) {
                    BootContextEvents.publishBootCompleted()
                }
            }
        }
        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        AppLogger.warn(TAG, "Foreground service timeout (startId=$startId, fgsType=$fgsType); stopping cleanly")
        scope.launch {
            val entry = com.opentasker.core.storage.RunLogEntity(
                taskId = 0,
                taskName = "AutomationService",
                timestamp = System.currentTimeMillis(),
                durationMs = 0,
                success = false,
                message = "Service stopped by system foreground-service timeout",
                source = "system",
                sourceLabel = "FGS timeout",
            )
            runCatching { db.runLogDao().insert(entry) }
        }
        stopSelf(startId)
    }

    override fun onDestroy() {
        // Nothing was ever subscribed on the refused-start path, so stopping monitors that never
        // started would be the only thing that could throw here. Cancel the alarm and leave.
        if (refusedStart) {
            runCatching { timeEventScheduler.cancel() }
            job.cancel()
            super.onDestroy()
            return
        }
        val matcherJobSnapshot = matcherJobs.values.toList()
        val taskJobSnapshot = profileTaskJobs.values.toList()
        matcherJobSnapshot.forEach { it.cancel() }
        taskJobSnapshot.forEach { it.cancel() }
        matcherJobs.clear()
        matchers.clear()
        // Arbitration state is per-service-lifetime: nothing is matched once the engine is down, and
        // carrying a stale matched set into the next start would suppress profiles that nothing is
        // actually outranking any more.
        matchedProfiles.clear()
        admittedProfiles.clear()
        profileCooldowns.clear()
        profileTaskJobs.clear()
        queuedProfileTasks.clear()
        timeEventScheduler.cancel()
        wifiNetworkMonitor.stop()
        connectivityMonitor.stop()
        appUsageMonitor.stop()
        shakeDetector.stop()
        orientationDetector.stop()
        foldDetector.stop()
        hardwareKeyListener.stop()
        // The bubble layers' collectors live on this service's scope, but their windows belong to the
        // WindowManager: without an explicit teardown they stayed on screen after the engine stopped,
        // with `started` still true so a restart never re-subscribed them.
        FreezeBubbleOverlayManager.stop()
        FlashBubbleOverlayManager.stop()
        runCatching { unregisterReceiver(PackageContextEvents.receiver) }
        runCatching { unregisterReceiver(BluetoothContextEvents.receiver) }
        CameraMicContextEvents.stop(this)
        PluginConditionSubscriptions.clear()
        BroadcastContextEvents.stop(this)
        shakeOn = false
        orientationOn = false
        foldOn = false
        appUsageOn = false
        isRunning = false
        job.cancel()
        super.onDestroy()
    }

    private suspend fun reloadProfiles() {
        val oldJobs = matcherJobs.values.toList()
        matcherJobs.clear()
        matchers.clear()
        oldJobs.forEach { it.cancel() }

        val profiles = db.profileDao().getAllEnabled()
        val activeIds = profiles.map { it.id }.toSet()
        // Prune against profiles that still exist, not the enabled ones: activeIds comes from
        // getAllEnabled(), so disabling a profile mid-cooldown deleted its persisted deadline while
        // the in-memory reservation survived. Whether the cooldown still applied then depended on
        // whether the service happened to restart before the profile was switched back on.
        cooldownStore.pruneDeleted(db.profileDao().getAllIds().toSet())
        synchronized(queuedProfileTasks) {
            // Slots are keyed +id for enter tasks and -id for exit tasks; prune both.
            queuedProfileTasks.keys.removeAll { kotlin.math.abs(it) !in activeIds }
        }
        val domains = profiles.map { it.toDomain() }
        // Keep the arbitration set in step with the reload: drop profiles that were deleted or
        // disabled, and refresh the ones still matched so an edited priority takes effect on the
        // next activation rather than at the next service restart.
        synchronized(matchedProfiles) {
            matchedProfiles.keys.retainAll(activeIds)
            admittedProfiles.retainAll(activeIds)
            domains.forEach { profile ->
                if (profile.id in matchedProfiles) matchedProfiles[profile.id] = profile
            }
        }
        registerPluginSubscriptions(domains)
        // Keep the broadcast (Intent Received) receiver listening for exactly the actions in use.
        val broadcastActions = domains
            .flatMap { it.contexts }
            .filter { it.type == ContextType.EVENT && it.config["event"]?.trim().equals("broadcast", ignoreCase = true) }
            .flatMap { (it.config["action"] ?: it.config["actions"] ?: "").split(",") }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        BroadcastContextEvents.setActions(this, broadcastActions)
        applyContextSourceGating(domains)
        for (domain in domains) {
            val matcher = ProfileMatcher(this, domain)

            val matcherJob = scope.launch {
                // Self-healing: if a matcher's flow ever errors, re-collect after a short backoff
                // instead of dying permanently. A dead matcher used to freeze the clock/battery tick and
                // the 電池線 charging state for hours; now it recovers within seconds, independent of the
                // alarm re-arm. (A clean completion — e.g. a profile with no contexts — just stops.)
                while (isActive) {
                    val errored = try {
                        matcher.stateChanges().collect { change ->
                            try {
                                when (change) {
                                    is ProfileStateChange.Activated -> onProfileActivated(domain, change.vars)
                                    is ProfileStateChange.Deactivated -> onProfileDeactivated(domain)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                AppLogger.error("OpenTasker", "Failed handling state change for ${domain.name}", e)
                            }
                        }
                        false
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.error("OpenTasker", "Profile matcher errored for ${domain.name}; restarting", e)
                        EngineHeartbeat.markMatcherRestart(domain.name)
                        true
                    }
                    if (!errored) break
                    if (isActive) delay(3_000)
                }
            }
            
            matcherJobs[domain.id] = matcherJob
            matchers[domain.id] = matcher
        }
    }

    private fun registerPluginSubscriptions(profiles: List<Profile>) {
        val subs = profiles.flatMap { profile ->
            profile.contexts
                .filter { it.type == com.opentasker.core.model.ContextType.PLUGIN }
                .mapNotNull { spec ->
                    val pkg = spec.config["package"]?.trim().orEmpty()
                    if (pkg.isBlank()) return@mapNotNull null
                    PluginConditionSubscription(
                        packageName = pkg,
                        bundleJson = spec.config["bundleJson"]?.trim().orEmpty().ifBlank { "{}" },
                        timeoutMs = spec.config["timeoutMs"]?.toLongOrNull()?.coerceIn(1_000, 30_000) ?: 5_000,
                    )
                }
        }.toSet()
        PluginConditionSubscriptions.replaceAll(subs)
    }

    /**
     * Resolve a profile's task by NAME first (survives bundle re-imports that re-id tasks), with the id
     * as the fallback. A rename leaves the stored name stale → the name misses → the id fallback still
     * works; a re-import re-ids the task → the id is stale → the name still resolves it.
     */
    private suspend fun resolveTask(name: String, fallbackId: Long): com.opentasker.core.model.Task? {
        val entity = (if (name.isNotBlank()) db.taskDao().getByName(name) else null)
            ?: (if (fallbackId > 0) db.taskDao().getById(fallbackId) else null)
        return entity?.toDomain()
    }

    private suspend fun onProfileActivated(profile: com.opentasker.core.model.Profile, eventVars: Map<String, String>) {
        // A pulse profile (one driven by an EVENT context) fires and is immediately done; it never
        // holds a matched state, so it must not enter the arbitration set — parking it there would
        // leave a phantom high-priority profile suppressing everything else for ever.
        val pulseProfile = profile.contexts.any { it.type == ContextType.EVENT }

        // Lifetime first: an expired or already-spent profile must not run at all. Disabling it is
        // upstream's behaviour and the honest one — the profile has done what it was set up to do,
        // and leaving it enabled would re-evaluate it on every tick for ever.
        val suppression = ProfileLifecyclePolicy.suppressionReason(profile, System.currentTimeMillis())
        if (suppression != null) {
            AppLogger.info(TAG, "Profile ${profile.id} (${profile.name}) activation suppressed: $suppression")
            if (profile.enabled) {
                runCatching { db.profileDao().update(profile.copy(enabled = false).toEntity()) }
                    .onFailure { AppLogger.error(TAG, "Could not disable spent profile ${profile.id}", it) }
            }
            return
        }

        // Read the candidate set once under the lock, then decide outside it. This happens before
        // the enter task is resolved, because arbitration is about the PROFILE, not its task: a
        // profile carrying only an exit task is still matched and still outranks lower ones.
        val suppressedBy = synchronized(matchedProfiles) {
            if (!pulseProfile) matchedProfiles[profile.id] = profile
            ProfileLifecyclePolicy.suppressionByPriority(profile, matchedProfiles.values + profile)
        }
        val hasEnterTask = profile.enterTaskName.isNotBlank() || profile.enterTaskId > 0
        if (suppressedBy != null) {
            AppLogger.info(TAG, "Profile ${profile.id} (${profile.name}) $suppressedBy")
            // A run-log row needs a task to name; a profile with no enter task leaves the log alone.
            if (hasEnterTask) {
                resolveTask(profile.enterTaskName, profile.enterTaskId)
                    ?.let { logProfileSkippedRun(profile, it, suppressedBy) }
            }
            return
        }

        // One-shot profiles are consumed transactionally, so two contexts matching in the same
        // instant cannot both win the single run.
        if (profile.lifetime == ProfileLifetime.ONCE && !consumeOneShotProfile(profile)) {
            if (!pulseProfile) synchronized(matchedProfiles) { matchedProfiles.remove(profile.id) }
            return
        }

        // Admitted — recorded whether or not there is an enter task to run, because this is what
        // deactivation consults to decide whether the exit task is owed. Gating that on a *dispatch*
        // instead would silently stop the exit task of every profile that carries only one, which is
        // a perfectly ordinary way to write a profile.
        if (!pulseProfile) admittedProfiles += profile.id

        if (!hasEnterTask) return
        val domain = resolveTask(profile.enterTaskName, profile.enterTaskId)
        if (domain == null) {
            AppLogger.warn("OpenTasker", "Enter task not found for profile ${profile.name} (name='${profile.enterTaskName}', id=${profile.enterTaskId})")
            return
        }
        dispatchTask(profile, domain, eventVars, isExit = false)
    }

    private suspend fun onProfileDeactivated(profile: com.opentasker.core.model.Profile) {
        // Leaving the matched set can un-suppress others: anything this profile was outranking, and
        // that nothing else still outranks, becomes eligible now.
        val released: List<Profile>
        val wasAdmitted: Boolean
        synchronized(matchedProfiles) {
            val before = matchedProfiles.values.toList()
            matchedProfiles.remove(profile.id)
            wasAdmitted = admittedProfiles.remove(profile.id)
            val remaining = matchedProfiles.values.toList()
            released = ProfileLifecyclePolicy.released(before, remaining)
        }

        // The exit task is owed only if the profile was admitted. One that never ran because it was
        // outranked has no cleanup to do, and running its exit task would undo state it never set.
        if (wasAdmitted && (profile.exitTaskName.isNotBlank() || (profile.exitTaskId != null && profile.exitTaskId > 0))) {
            val domain = resolveTask(profile.exitTaskName, profile.exitTaskId ?: 0L)
            if (domain == null) {
                AppLogger.warn("OpenTasker", "Exit task not found for profile ${profile.name} (name='${profile.exitTaskName}', id=${profile.exitTaskId})")
            } else {
                dispatchTask(profile, domain, emptyMap(), isExit = true)
            }
        }

        released.forEach { candidate ->
            AppLogger.info(TAG, "Profile ${candidate.id} (${candidate.name}) released by ${profile.name} deactivating")
            onProfileActivated(candidate, emptyMap())
        }
    }

    /**
     * Spends a one-shot profile, returning true only for the caller that actually consumed it.
     *
     * The read-modify-write is one transaction because two contexts can satisfy a profile in the
     * same instant on different matcher coroutines; without it both would see `lifetimeConsumed`
     * false and a "run once" profile would run twice.
     */
    private suspend fun consumeOneShotProfile(profile: Profile): Boolean = runCatching {
        db.withTransaction {
            val entity = db.profileDao().getById(profile.id) ?: return@withTransaction false
            val current = entity.toDomain()
            if (!current.enabled || current.lifetime != ProfileLifetime.ONCE || current.lifetimeConsumed) {
                return@withTransaction false
            }
            db.profileDao().update(current.copy(enabled = false, lifetimeConsumed = true).toEntity())
            true
        }
    }.onFailure {
        AppLogger.error(TAG, "Could not consume one-shot profile ${profile.id}", it)
    }.getOrDefault(false)

    /**
     * Enter and exit tasks are distinct execution units: they use separate job slots (so a
     * still-running enter task never suppresses or gets cancelled by the exit task) and only
     * enter dispatch consumes the profile cooldown — cooldown gates re-triggering, not the
     * cleanup work users rely on when a profile deactivates.
     */
    private fun dispatchTask(
        profile: Profile,
        task: Task,
        eventVars: Map<String, String>,
        isExit: Boolean,
    ) {
        val slot = taskSlotKey(profile.id, isExit)
        when (profile.automationMode) {
            AutomationMode.SINGLE -> {
                if (profileTaskJobs[slot]?.isActive == true) {
                    AppLogger.info(TAG, "Profile ${profile.id} already running; SINGLE mode skipped retrigger")
                    logProfileSkippedRun(profile, task, "Profile is already running in SINGLE mode.")
                    return
                }
                if (!isExit) {
                    val reservation = reserveCooldown(profile.id, profile.cooldownSec)
                    if (!reservation.accepted) {
                        logCooldownSkip(profile, task, reservation.remainingMs)
                        return
                    }
                }
                profileTaskJobs[slot] = launchTrackedTask(profile, slot, task, eventVars)
            }

            AutomationMode.RESTART -> {
                if (!isExit) {
                    val reservation = reserveCooldown(profile.id, profile.cooldownSec)
                    if (!reservation.accepted) {
                        logCooldownSkip(profile, task, reservation.remainingMs)
                        return
                    }
                }
                profileTaskJobs[slot]?.cancel()
                profileTaskJobs[slot] = launchTrackedTask(profile, slot, task, eventVars)
            }

            AutomationMode.QUEUED -> {
                // The queue check, enqueue, and the consumer's drain-or-deregister decision all
                // synchronize on queuedProfileTasks, so a task can never be enqueued into a queue
                // whose consumer has already decided to exit (which would strand it unrun).
                val outcome = synchronized(queuedProfileTasks) {
                    if (profileTaskJobs[slot]?.isActive == true) {
                        val queue = queuedProfileTasks.getOrPut(slot) { ArrayDeque() }
                        if (queue.size >= MAX_QUEUED_TASKS) {
                            QueueOutcome.FULL
                        } else {
                            queue.add(QueuedRun(task, eventVars))
                            QueueOutcome.QUEUED
                        }
                    } else {
                        QueueOutcome.START
                    }
                }
                when (outcome) {
                    QueueOutcome.FULL -> {
                        AppLogger.warn(TAG, "Profile ${profile.id} queue full ($MAX_QUEUED_TASKS), dropping retrigger")
                        logProfileSkippedRun(profile, task, "Task queue is full ($MAX_QUEUED_TASKS pending).")
                    }
                    QueueOutcome.QUEUED -> AppLogger.info(TAG, "Profile ${profile.id} queued retrigger")
                    QueueOutcome.START -> {
                        // Reserve cooldown only when actually starting a fresh run, not when a
                        // trigger queues behind a running task. Reserving at enqueue time dropped
                        // a later distinct trigger as "cooldown active" that should have queued.
                        if (!isExit) {
                            val reservation = reserveCooldown(profile.id, profile.cooldownSec)
                            if (!reservation.accepted) {
                                logCooldownSkip(profile, task, reservation.remainingMs)
                                return
                            }
                        }
                        profileTaskJobs[slot] = launchQueuedTasks(profile, slot, task, eventVars)
                    }
                }
            }

            AutomationMode.PARALLEL -> {
                if (!isExit) {
                    val reservation = reserveCooldown(profile.id, profile.cooldownSec)
                    if (!reservation.accepted) {
                        logCooldownSkip(profile, task, reservation.remainingMs)
                        return
                    }
                }
                scope.launch { runTask(task, profile, eventVars) }
            }
        }
    }

    /** Profile ids are positive Room autogenerated keys, so -id is a collision-free exit slot. */
    private fun taskSlotKey(profileId: Long, isExit: Boolean): Long = if (isExit) -profileId else profileId

    private enum class QueueOutcome { START, QUEUED, FULL }

    private fun launchTrackedTask(profile: Profile, slot: Long, task: Task, eventVars: Map<String, String>): Job =
        scope.launch(start = CoroutineStart.DEFAULT) {
            val thisJob = currentCoroutineContext()[Job]
            try {
                runTask(task, profile, eventVars)
            } finally {
                synchronized(profileTaskJobs) {
                    if (profileTaskJobs[slot] == thisJob) {
                        profileTaskJobs.remove(slot)
                    }
                }
            }
        }

    private fun launchQueuedTasks(profile: Profile, slot: Long, firstTask: Task, firstEventVars: Map<String, String>): Job =
        scope.launch(start = CoroutineStart.DEFAULT) {
            val thisJob = currentCoroutineContext()[Job]
            var nextRun: QueuedRun? = QueuedRun(firstTask, firstEventVars)
            try {
                while (isActive && nextRun != null) {
                    val run = requireNotNull(nextRun)
                    runTask(run.task, profile, run.eventVars)
                    nextRun = synchronized(queuedProfileTasks) {
                        val polled = queuedProfileTasks[slot]?.poll()
                        if (polled == null) {
                            // Deregister inside the queue lock so the producer either sees an
                            // active consumer (and enqueues into a queue that will be drained)
                            // or no consumer at all (and starts a fresh one) — never a consumer
                            // that has already decided to exit.
                            queuedProfileTasks.remove(slot)
                            synchronized(profileTaskJobs) {
                                if (profileTaskJobs[slot] == thisJob) {
                                    profileTaskJobs.remove(slot)
                                }
                            }
                        } else if (queuedProfileTasks[slot]?.isEmpty() == true) {
                            queuedProfileTasks.remove(slot)
                        }
                        polled
                    }
                }
            } finally {
                synchronized(queuedProfileTasks) {
                    synchronized(profileTaskJobs) {
                        if (profileTaskJobs[slot] == thisJob) {
                            profileTaskJobs.remove(slot)
                        }
                    }
                }
            }
        }

    private suspend fun runTask(
        task: Task,
        profile: Profile,
        eventVars: Map<String, String> = emptyMap(),
    ) {
        val result = executeAndLogTask(
            appContext = this,
            db = db,
            task = task,
            source = "Profile: ${profile.name}",
            metadata = profileRunMetadata(profile),
            audioForegroundService = audioForegroundServiceEligibility,
            admissionController = executionAdmission,
            profileId = profile.id,
            // The profile's own concurrency policy, honoured per run rather than only globally.
            profileLimits = profile.toExecutionAdmissionProfileLimits(),
            overflowPolicy = profile.overflowPolicy,
            eventLocals = eventVars,
        )
        if (result.logInserted) {
            pruneRunLogs(force = false)
        }
    }

    /** Variable extras a validated external request forwarded, already name-checked and capped. */
    private fun externalVariables(intent: Intent): Map<String, String> {
        val extras = intent.extras ?: return emptyMap()
        return extras.keySet()
            .asSequence()
            .filter { it.startsWith(AutomationTargetContract.VARIABLE_EXTRA_PREFIX) }
            .mapNotNull { key ->
                val name = key.removePrefix(AutomationTargetContract.VARIABLE_EXTRA_PREFIX)
                if (!AutomationTargetContract.isValidVariableName(name)) return@mapNotNull null
                extras.getString(key)?.let { name to it }
            }
            .toMap()
    }

    private suspend fun runExternalTask(
        executionId: String,
        taskId: Long,
        variables: Map<String, String>,
        runSource: String,
    ) {
        fun fail(reason: String) {
            AppLogger.warn(TAG, "External execution $executionId failed: $reason")
            ExternalExecutions.update(this, executionId, ExternalExecutionState.FAILED, error = reason)
        }

        try {
            val entity = db.taskDao().getById(taskId) ?: return fail("Task $taskId no longer exists.")
            val decoded = entity.toDomainDecodeResult()
            decoded.issue?.let { issue ->
                val reason = issue.recoveryMessage()
                logSkippedRun(
                    db = db,
                    task = decoded.value,
                    source = runSource,
                    reason = reason,
                    metadata = listOf("execution=$executionId"),
                )
                return fail(reason)
            }
            ExternalExecutions.update(this, executionId, ExternalExecutionState.RUNNING)
            val result = executeAndLogTask(
                appContext = this,
                db = db,
                task = decoded.value,
                source = runSource,
                metadata = listOf("execution=$executionId", "Variables: ${variables.size} provided"),
                initialVariables = variables,
                audioForegroundService = audioForegroundServiceEligibility,
                logTag = TAG,
                admissionController = executionAdmission,
            )
            ExternalExecutions.update(
                context = this,
                executionId = executionId,
                state = if (result.report.success) ExternalExecutionState.SUCCEEDED else ExternalExecutionState.FAILED,
                durationMs = result.report.durationMs,
                error = when {
                    result.report.success -> null
                    result.skippedReason != null -> result.skippedReason
                    else -> "Task reported a failure; see the run log."
                },
            )
        } catch (e: CancellationException) {
            ExternalExecutions.update(this, executionId, ExternalExecutionState.FAILED, error = "The run was cancelled.")
            throw e
        } catch (e: Exception) {
            fail(e.message ?: "The run threw an unexpected error.")
        }
    }

    private suspend fun runNotificationTask(reference: NotificationTaskReference, buttonLabel: String) {
        try {
            val entities = when (reference) {
                is NotificationTaskReference.Id -> listOfNotNull(db.taskDao().getById(reference.taskId))
                is NotificationTaskReference.LegacyName -> db.taskDao().getAll()
                is NotificationTaskReference.Invalid -> emptyList()
            }
            val resolution = NotificationTaskBindings.resolve(
                reference = reference,
                candidates = entities.map { NotificationTaskCandidate(it.id, it.name) },
            )
            if (resolution !is NotificationTaskResolution.Bound) {
                AppLogger.warn(
                    TAG,
                    "Notification button '$buttonLabel' did not run: ${NotificationTaskBindings.failureMessage(resolution)}",
                )
                return
            }
            val entity = entities.single { it.id == resolution.task.id }
            val decoded = entity.toDomainDecodeResult()
            val issue = decoded.issue
            if (issue != null) {
                val reason = issue.recoveryMessage()
                AppLogger.error(TAG, "Notification button '$buttonLabel' blocked: $reason")
                logSkippedRun(
                    db = db,
                    task = decoded.value,
                    source = NotificationActionReceiver.SOURCE,
                    reason = reason,
                    metadata = listOf("button=$buttonLabel"),
                )
                return
            }
            val task = decoded.value
            val result = executeAndLogTask(
                appContext = this,
                db = db,
                task = task,
                source = NotificationActionReceiver.SOURCE,
                metadata = listOf("button=$buttonLabel"),
                audioForegroundService = audioForegroundServiceEligibility,
                admissionController = executionAdmission,
            )
            if (result.logInserted) pruneRunLogs(force = false)
            val status = when {
                result.skippedReason != null -> "skipped"
                result.report.success -> "succeeded"
                else -> "failed"
            }
            AppLogger.info(TAG, "Notification button '$buttonLabel' -> ${task.name} $status (${result.report.durationMs}ms)")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.error(TAG, "Notification button '$buttonLabel' failed", e)
        }
    }

    private fun logCooldownSkip(profile: Profile, task: Task, remainingMs: Long) {
        logProfileSkippedRun(profile, task, "Cooldown active for ${formatRemainingCooldown(remainingMs)}.")
    }

    private fun logProfileSkippedRun(profile: Profile, task: Task, reason: String) {
        scope.launch {
            val inserted = logSkippedRun(
                db = db,
                task = task,
                source = "Profile: ${profile.name}",
                reason = reason,
                metadata = profileRunMetadata(profile),
            )
            if (inserted) pruneRunLogs(force = false)
        }
    }

    private suspend fun pruneRunLogs(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRunLogPruneAt < RUN_LOG_PRUNE_INTERVAL_MS) return

        val policy = runLogRetentionSettings.load()
        runCatching {
            db.runLogDao().pruneRetention(
                maxEntries = policy.maxEntries,
                minimumTimestamp = policy.minimumTimestamp(now),
            )
        }
            .onSuccess { deleted ->
                lastRunLogPruneAt = now
                if (deleted > 0) {
                    AppLogger.info(TAG, "Pruned $deleted old run log entries")
                }
            }
            .onFailure { error ->
                AppLogger.error(TAG, "Failed to prune run logs", error)
            }
    }

    private fun reserveCooldown(profileId: Long, cooldownSec: Int): CooldownReservation {
        val now = System.currentTimeMillis()
        synchronized(profileCooldowns) {
            val cooldownUntil = profileCooldowns[profileId] ?: 0
            if (now < cooldownUntil) {
                AppLogger.info(TAG, "Profile $profileId on cooldown, skipping")
                return CooldownReservation(accepted = false, remainingMs = cooldownUntil - now)
            }
            if (cooldownSec > 0) {
                val deadline = now + (cooldownSec * 1000L)
                profileCooldowns[profileId] = deadline
                cooldownStore.set(profileId, deadline)
            }
            return CooldownReservation(accepted = true)
        }
    }

    private fun profileRunMetadata(profile: Profile): List<String> = buildList {
        add("Profile ID: ${profile.id}")
        add("Mode: ${profile.automationMode.name.lowercase()}")
        if (profile.cooldownSec > 0) add("Cooldown: ${profile.cooldownSec}s")
    }

    private fun formatRemainingCooldown(remainingMs: Long): String {
        val seconds = TimeUnit.MILLISECONDS.toSeconds(remainingMs).coerceAtLeast(1)
        return if (seconds == 1L) "1 second" else "$seconds seconds"
    }


    /** Run the user's configured auto-start tasks once per process (after a fresh start / resurrect),
     *  so overlays and state come back without manually running the master "起動" task. */
    private fun runAutoStartTasks() {
        scope.launch {
            delay(2_000) // let the engine + context sources settle before re-establishing state
            for (id in AutoStartSettings.taskIds(this@AutomationService)) {
                val task = db.taskDao().getById(id)?.toDomain() ?: continue
                runCatching { executeAndLogTask(this@AutomationService, db, task, source = "Auto-start") }
            }
        }
    }

    /** Start each sensor/usage monitor ONLY while an enabled profile actually uses it. Shake &
     *  orientation are accelerometer listeners; app-foreground is a 2-second usage poll — running them
     *  for the whole service life kept the CPU busy and blocked deep sleep even when nothing used them.
     *  Transition-guarded (the flags) so we never double-register a sensor listener. */
    private fun applyContextSourceGating(domains: List<Profile>) {
        val contexts = domains.flatMap { it.contexts }
        fun usesEvent(key: String) = contexts.any {
            it.type == ContextType.EVENT && it.config["event"]?.trim().equals(key, ignoreCase = true)
        }
        val needShake = usesEvent("shake")
        val needOrientation = usesEvent("orientation")
        val needFold = usesEvent("fold")
        // AppUsageMonitor drives BOTH the APPLICATION context type (音楽端灯・前面) AND the "app_foreground"
        // EVENT (通知明滅・前面) — gate on either, or the foreground-clear profile silently stops.
        val needApp = contexts.any { it.type == ContextType.APPLICATION } || usesEvent("app_foreground")
        if (needShake != shakeOn) { if (needShake) shakeDetector.start() else shakeDetector.stop(); shakeOn = needShake }
        if (needOrientation != orientationOn) { if (needOrientation) orientationDetector.start() else orientationDetector.stop(); orientationOn = needOrientation }
        if (needFold != foldOn) { if (needFold) foldDetector.start() else foldDetector.stop(); foldOn = needFold }
        if (needApp != appUsageOn) { if (needApp) appUsageMonitor.start(scope) else appUsageMonitor.stop(); appUsageOn = needApp }
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL, "白い熊 自由作業盤 engine", NotificationManager.IMPORTANCE_MIN)
        nm.createNotificationChannel(channel)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("白い熊 自由作業盤 is running")
            .setContentText("Tap to open automation status")
            // The app's own monochrome mark and accent, not a platform holo drawable: this
            // notification is visible permanently, and holo renders as a muddy alpha blob whose
            // shape is not a stable cross-OEM contract.
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(this, R.color.notification_accent))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, foregroundServiceTypes())
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun foregroundServiceTypes(): Int {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        if (hasBackgroundLocationForegroundServicePrerequisites()) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        return types
    }

    private fun hasBackgroundLocationForegroundServicePrerequisites(): Boolean {
        if (!hasAnyLocationPermission()) return false
        if (Build.VERSION.SDK_INT >= 29 && !hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) return false
        val locationManager = getSystemService(LocationManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= 28) {
            locationManager.isLocationEnabled
        } else {
            runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false) ||
                runCatching { locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
        }
    }

    private fun hasAnyLocationPermission(): Boolean =
        hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "AutomationService"
        const val ACTION_BOOT_COMPLETED_TRIGGER = "com.opentasker.action.BOOT_COMPLETED_TRIGGER"
        const val ACTION_TIME_TICK_TRIGGER = "com.opentasker.action.TIME_TICK_TRIGGER"
        const val ACTION_RUN_NOTIFICATION_TASK = "com.opentasker.action.RUN_NOTIFICATION_TASK"
        const val ACTION_REARM = "com.opentasker.action.ENGINE_REARM"
        const val ACTION_RUN_EXTERNAL_TASK = "com.opentasker.action.RUN_EXTERNAL_TASK"
        /** Set by MainActivity when it (re)starts the engine from a visible UI — upstream uses this to
         *  promote audio-action eligibility (Android 17 hardening); accepted here for compatibility. */
        const val EXTRA_STARTED_FROM_VISIBLE_UI = "com.opentasker.extra.STARTED_FROM_VISIBLE_UI"
        private const val CHANNEL = "opentasker.engine"
        private const val NOTIF_ID = 1001
        private const val MAX_QUEUED_TASKS = 50

        /** True while the engine service is alive in this process — lets the per-minute tick resurrect it if EMUI reaped it. */
        @Volatile
        var isRunning = false
            private set

        /** (Re)start the foreground engine service. Safe to call when it is already running. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, AutomationService::class.java))
        }

        /** Stop the engine outright — the last step of the shutdown teardown, and half of a restart. */
        fun stop(context: Context) {
            val app = context.applicationContext
            runCatching { app.stopService(Intent(app, AutomationService::class.java)) }
                .onFailure { AppLogger.warn(TAG, "Failed to stop the automation service: ${it.message}") }
        }

        /** Ask a running engine to re-arm its matchers — used by the heartbeat when the tick went stale. */
        fun rearm(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AutomationService::class.java).setAction(ACTION_REARM),
            )
        }
        private val RUN_LOG_PRUNE_INTERVAL_MS = TimeUnit.HOURS.toMillis(1)
    }
}

private data class CooldownReservation(
    val accepted: Boolean,
    val remainingMs: Long = 0,
)
