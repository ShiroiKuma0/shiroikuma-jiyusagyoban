package com.opentasker.ui.charts.huawei

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.opentasker.core.huawei.HuaweiFrom
import com.opentasker.core.huawei.HuaweiSettings
import com.opentasker.core.huawei.HuaweiSleep
import com.opentasker.core.huawei.HuaweiStatus
import com.opentasker.core.huawei.HuaweiSyncArgs
import com.opentasker.core.huawei.HuaweiSyncRunner
import com.opentasker.core.huawei.HuaweiSyncState
import com.opentasker.core.storage.AppDatabase
import com.opentasker.ui.charts.ChartPipeline
import com.opentasker.ui.charts.ChartPoint
import com.opentasker.ui.charts.HealthIndexSource
import com.opentasker.ui.charts.Loc
import com.opentasker.ui.charts.MetricChart
import com.opentasker.ui.charts.MetricSpec
import com.opentasker.ui.charts.RenderKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One undecoded field, as the 診断 card lists it. */
data class HuaweiUnknownField(
    val storageKey: String,
    val samples: Int,
    val firstSeconds: Long?,
    val lastSeconds: Long?,
)

/**
 * The most recent night, rebuilt from stored segments.
 *
 * Reuses [HuaweiSleep.Session] rather than defining a parallel UI type, so the totals the card shows
 * are computed by the same code the decoder's tests check against the band's own screen.
 */
data class HuaweiSleepNight(val session: HuaweiSleep.Session)

data class HuaweiDashboardState(
    val loading: Boolean = true,
    val status: HuaweiStatus? = null,
    val bound: Boolean = false,
    val metrics: List<MetricChart> = emptyList(),
    val coverage: List<HuaweiCoverage> = emptyList(),
    val diagnostics: List<MetricChart> = emptyList(),
    val unknownFields: List<HuaweiUnknownField> = emptyList(),
    val sleep: HuaweiSleepNight? = null,
    val bounds: LongRange = 0L..0L,
    val message: Loc? = null,
)

/**
 * The Huawei report's state.
 *
 * A **sibling** of `BandDashboardModel`, not a generalisation of it. That model's `DashboardState`
 * carries twenty fields and this one would return null for fourteen — index, recovery, sleep, blood
 * pressure, the felt ratings, the register. A shared type would then assert, at every call site,
 * that any device can produce a recovery card, which is the one claim this report must never make.
 * The reuse that matters is already paid for by `MetricSpec` and `MetricChart`, which were never
 * device-specific.
 *
 * Loading is one-shot rather than a Room `Flow`, for the reason the Hume model records: table-
 * granular invalidation re-runs the whole filter chain on every write, thousands of times per sync.
 */
class HuaweiDashboardModel(
    private val db: AppDatabase,
    private val appContext: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(HuaweiDashboardState())
    val state: StateFlow<HuaweiDashboardState> = _state.asStateFlow()

    val progress = HuaweiSyncState.progress

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val next = withContext(Dispatchers.Default) { load() }
            _state.value = next
        }
    }

    private suspend fun load(): HuaweiDashboardState {
        val samples = db.huaweiSampleDao()
        val oldest = samples.oldestAny()
        val newest = samples.newestAny()
        val status = runCatching { HuaweiSyncRunner.status(db) }.getOrNull()
        val bound = HuaweiSettings.isBound(appContext)

        // Loaded before the early return: a night can exist when no per-minute sample does, and
        // showing "no data" over a stored night would be a lie about the band rather than about us.
        val sleep = loadLatestNight()

        if (oldest == null || newest == null) {
            return HuaweiDashboardState(
                loading = false, status = status, bound = bound, sleep = sleep,
                message = if (bound) HuaweiText.noData else HuaweiText.notPaired,
            )
        }
        val fromMs = oldest * 1000L
        val toMs = newest * 1000L

        val charts = HuaweiMetricSpecs.ALL.map { spec -> buildChart(spec, oldest, newest) }
        val diagnostics = HuaweiMetricSpecs.DIAGNOSTIC.map { spec -> buildChart(spec, oldest, newest) }

        val coverage = (HuaweiMetricSpecs.ALL + HuaweiMetricSpecs.DIAGNOSTIC).map { spec ->
            HuaweiCoverage.from(
                spec.key,
                samples.timesFor(HuaweiKeys.storageKey(spec.key), oldest, newest),
            )
        }

        // Anything the band sent that no row describes. Listed rather than charted: the question
        // these answer is "bit 0x10 started appearing on the 3rd", which is a table, not a curve.
        val known = (HuaweiMetricSpecs.ALL + HuaweiMetricSpecs.DIAGNOSTIC)
            .map { HuaweiKeys.storageKey(it.key) }.toSet()
        val unknown = samples.metrics().filter { it !in known }.map { key ->
            val times = samples.timesFor(key, oldest, newest)
            HuaweiUnknownField(key, times.size, times.minOrNull(), times.maxOrNull())
        }

        return HuaweiDashboardState(
            loading = false,
            status = status,
            bound = bound,
            metrics = charts,
            coverage = coverage,
            diagnostics = diagnostics,
            unknownFields = unknown,
            sleep = sleep,
            bounds = fromMs..toMs,
        )
    }

    /** The newest stored night, or null when none has been synced. */
    private suspend fun loadLatestNight(): HuaweiSleepNight? {
        val dao = db.huaweiSleepDao()
        val start = dao.newestSession() ?: return null
        val rows = dao.session(start)
        if (rows.isEmpty()) return null
        return HuaweiSleepNight(
            HuaweiSleep.Session(
                startSeconds = start,
                endSeconds = rows.first().sessionEnd,
                segments = rows.map {
                    HuaweiSleep.Segment(
                        it.startSeconds, it.durationSeconds, HuaweiSleep.Stage.of(it.stage),
                    )
                },
            ),
        )
    }

    private suspend fun buildChart(spec: MetricSpec, from: Long, to: Long): MetricChart {
        val rows = db.huaweiSampleDao().range(HuaweiKeys.storageKey(spec.key), from, to)
        val points = rows.map { ChartPoint(it.epochSeconds * 1000L, it.value) }
        if (points.isEmpty()) {
            return MetricChart(
                spec = spec, chunk = null, buckets = emptyList(), bars = emptyList(),
                headline = "—", headlineBand = null, subtitle = HuaweiText.noData,
            )
        }
        // mixedCadence is on for every row in this table: the real cadence is unmeasured, so the gap
        // threshold has to come from a high percentile rather than a median it cannot trust.
        val chunk = ChartPipeline.qualifyAndSegment(points, spec, mixedCadence = spec.mixedCadence)
        val retained = chunk.segments.flatMap { it.points }
        val lastDay = HealthIndexSource.lastDay(retained)

        val headline = when {
            retained.isEmpty() -> "—"
            // A bar metric headlines its 24 h TOTAL, matching the Hume screen so the two figures
            // answer the same question.
            spec.render == RenderKind.BARS -> spec.format(lastDay.sumOf { it.value }) + " " + spec.unit
            spec.headlineIsRange -> {
                val lo = lastDay.minOfOrNull { it.value }
                val hi = lastDay.maxOfOrNull { it.value }
                if (lo == null || hi == null) "—"
                else "${spec.format(lo)}–${spec.format(hi)} ${spec.unit}"
            }
            else -> HealthIndexSource.median(lastDay.map { it.value })
                ?.let { "${spec.format(it)} ${spec.unit}" } ?: "—"
        }

        return MetricChart(
            spec = spec,
            chunk = chunk,
            buckets = emptyList(),
            // The bar renderer reads raw retained points and must never see a filtered series —
            // though with every filter off on this table, retained is currently everything.
            bars = if (spec.render == RenderKind.BARS) retained else emptyList(),
            headline = headline,
            headlineBand = null,
            // Stated on every card, because every gate on this table is a placeholder.
            subtitle = if (spec.provisional) HuaweiText.provisional else Loc("", ""),
        )
    }

    /**
     * Announce the sync on the main thread BEFORE dispatching, then hand off to the app-scoped
     * runner so it survives this window closing. Both halves matter: reaching a usable session means
     * a socket connect plus a full HiChain pass, and nothing is visible on screen until then.
     */
    fun sync() {
        if (!HuaweiSyncState.arm()) return
        HuaweiSyncRunner.scope.launch {
            runCatching {
                val status = HuaweiSyncRunner.status(db)
                HuaweiSyncRunner.sync(
                    appContext, db,
                    HuaweiSyncRunner.Request(
                        address = HuaweiSettings.address(appContext),
                        windows = HuaweiSyncArgs.resolve(
                            from = HuaweiFrom.Auto,
                            lastSuccessAtSeconds = status.lastSuccessAtMillis?.let { it / 1000 },
                            overlapMinutes = HuaweiSettings.overlapMinutes(appContext),
                            nowSeconds = System.currentTimeMillis() / 1000,
                        ),
                        timeoutSec = HuaweiSettings.timeoutSec(appContext),
                        source = "window",
                    ),
                )
            }.onFailure {
                // The one path where arming without reaching the runner would leave it spinning.
                HuaweiSyncState.finish(it.message ?: "failed")
            }
            refresh()
        }
    }
}

class HuaweiDashboardModelFactory(
    private val db: AppDatabase,
    private val appContext: Context,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HuaweiDashboardModel::class.java)) {
            return HuaweiDashboardModel(db, appContext) as T
        }
        throw IllegalArgumentException("Unknown ViewModel: $modelClass")
    }
}
