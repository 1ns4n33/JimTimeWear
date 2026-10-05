package com.jimtime.wear.guided

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/*
 * F7 (Schede multisport al polso, WP-WD, §2.9) — ponte stateful fra il
 * motore puro [StepEngine] e il resto dell'app Wear, modello
 * `SwimGuideRepository`: [GuidedRunRepository.tick] lo chiama
 * TrackingService una volta al secondo (C12: mai dalla schermata),
 * GuidedSessionScreen osserva [GuidedRunRepository.current].
 *
 * Port dell'orchestrazione di `GuidedRunSession.swift` SENZA effetti:
 * [GuidedRunRepository.tick] ritorna gli eventi e basta — aptica/voce/
 * mirror/checkpoint li fa il service. Nessuna lettura dell'ora (J9): ogni
 * istante arriva dal chiamante.
 */

/** Checkpoint della corsa guidata (D18, appendice I) — chiavi additive tollerate. */
data class GuidedRunSnapshot(
    val structure: ResolvedStructure,
    val planRef: JSONObject,
    val lang: String,
    val title: String?,
    val sport: String,
    val variant: String?,
    val engine: StepEngineState,
    val segments: List<ActivityStepSegmentWire>,
    /** Eco per `stepSegments.profile`; assente dal wire se vuoto. */
    val profile: JSONObject? = null,
    /** `false` = indoor senza distanza (D20): le fasi a distanza vanno «fino al tasto». */
    val hasDistanceSignal: Boolean = true,
) {
    fun toJson(): JSONObject {
        val segs = JSONArray()
        segments.forEach { segs.put(it.toWire()) }
        val o = JSONObject()
            .put("structure", structure.toWire())
            .put("engine", engine.toJson())
            .put("segments", segs)
            .put("planRef", JSONObject(planRef.toString()))
            .put("lang", lang)
            .put("sport", sport)
            .put("distanceSignal", hasDistanceSignal)
        title?.let { o.put("title", it) }
        variant?.let { o.put("variant", it) }
        if (profile != null && profile.length() > 0) o.put("profile", JSONObject(profile.toString()))
        return o
    }

    companion object {
        /** Tollerante: senza una `structure` valida ⇒ `null`; il resto ha default. */
        fun fromJson(o: JSONObject): GuidedRunSnapshot? {
            val structure = (o.opt("structure") as? JSONObject)?.let(ResolvedStructure::fromWire) ?: return null
            val engine = (o.opt("engine") as? JSONObject)?.let(StepEngineState::fromJson) ?: StepEngineState.INITIAL
            val rawSegs = o.opt("segments") as? JSONArray
            val segments = if (rawSegs == null) emptyList() else (0 until rawSegs.length()).mapNotNull { i ->
                (rawSegs.opt(i) as? JSONObject)?.let(ActivityStepSegmentWire::fromWire)
            }
            return GuidedRunSnapshot(
                structure = structure,
                planRef = o.opt("planRef") as? JSONObject ?: JSONObject(),
                lang = o.opt("lang") as? String ?: "",
                title = o.opt("title") as? String,
                sport = o.opt("sport") as? String ?: "run",
                variant = o.opt("variant") as? String,
                engine = engine,
                segments = segments,
                profile = o.opt("profile") as? JSONObject,
                hasDistanceSignal = o.opt("distanceSignal") as? Boolean ?: true,
            )
        }
    }
}

/**
 * Stato pronto per la UI (§2.8). [index] è 0-based e sempre in
 * `0 until total` (a struttura finita resta sull'ultima fase).
 * Testi già localizzati ([GuidedStrings] o `ui` del wire).
 *
 * - [remainingText]: `"4:32"` (tempo), `"420 m"` (distanza); per `lap`/
 *   `hrBelow` il tempo trascorso nella fase (con [remainingSubtitle]);
 *   a struttura finita la durata attiva totale.
 * - [remainingSubtitle]: `"fino al tasto"` / `"FC < 130"` solo per `lap`/`hrBelow`.
 * - [target]: la banda della fase (UI decide per `kind`); `null` a fine struttura.
 * - [targetLabel]: `ui.target` del wire (`"3:55–4:05 /km"`), `""` se assente.
 * - [currentValue]: media corrente della fase nella stessa unità della banda
 *   (passo sec/km, bpm, W), `null` se non valutabile.
 * - [nextLabel]: `"Recupero 3/6 · 2:00"`, SENZA il prefisso «Poi:» (chiave
 *   `next_label` della UI); `null` all'ultima fase.
 */
data class GuidedRunView(
    val index: Int,
    val total: Int,
    val label: String,
    val remainingText: String,
    val remainingSubtitle: String?,
    val target: ResolvedTarget?,
    val targetLabel: String,
    val currentValue: Double?,
    val inBand: Boolean?,
    val nextLabel: String?,
    val finished: Boolean,
    val variant: String?,
    val lang: String = "",
    val title: String? = null,
    val sport: String = "run",
    /** Avanzamento della fase 0..1 (0 per `lap`/`hrBelow`). */
    val fraction: Double = 0.0,
    /** Fine EFFETTIVA della fase (D20: distanza senza segnale ⇒ `LAP`); `null` a fine struttura. */
    val endType: ResolvedEndType? = null,
)

/** Una corsa guidata in memoria — tutta la logica, nessun singleton (testabile, riusabile dal recovery). */
internal class GuidedRun(
    val structure: ResolvedStructure,
    val planRef: JSONObject,
    val lang: String,
    val title: String?,
    val sport: String,
    val variant: String?,
    val profile: JSONObject?,
    val hasDistanceSignal: Boolean,
    var engine: StepEngineState = StepEngineState.INITIAL,
    initialSegments: List<ActivityStepSegmentWire> = emptyList(),
) {
    val steps: List<ResolvedStep> = structure.expand()
    val segments: MutableList<ActivityStepSegmentWire> = initialSegments.toMutableList()
    var lastSample: StepSample? = null
    var lapPending = false

    /// Click 3-2-1 dovuti dopo l'ultimo [tick] (solo fasi a TEMPO effettivo).
    var countdownClicks = 0
    private var countdownIndex = -1
    private var countdownPrevRemainingSec: Double? = null
    private var lastMirroredIndex = -1
    private var lastMirrorAtMs: Long? = null

    val finished: Boolean get() = engine.index >= steps.size

    fun stepAt(index: Int): ResolvedStep? = steps.getOrNull(index)

    fun tick(activeMs: Long, distanceM: Double, hr: Double?, paused: Boolean): List<StepEvent> {
        // Il «Fase ›» premuto in pausa resta in attesa: il motore in pausa
        // (regola 2) lo scarterebbe.
        val lap = lapPending && !paused
        // Il tempo attivo non torna mai indietro (ripresa da checkpoint,
        // cambio d'orologio): clampato al precedente, così nessun segmento
        // può avere `endActiveSec < startActiveSec`.
        val floorMs = lastSample?.activeMs ?: engine.lastActiveMs
        val sample = StepSample(
            activeMs = maxOf(activeMs, floorMs),
            distanceM = distanceM,
            hr = hr,
            cadenceSpm = null, // D19 — nessun sensore di cadenza sul Wear.
            powerW = null,
            paused = paused,
            lap = lap,
        )
        if (lap) lapPending = false
        lastSample = sample
        val (next, events) = StepEngine.advance(steps, engine, sample)
        engine = next
        for (e in events) {
            if (e is StepEvent.StepEnd) closeSegment(e.stepIndex, e.endedBy, e.stats)
        }
        updateCountdown(sample)
        return events
    }

    /// Porting di `scheduleLastPartClicks` (Swift): un click a 3, 2 e 1 s
    /// dalla fine delle sole fasi a tempo, mai per i metri.
    private fun updateCountdown(sample: StepSample) {
        countdownClicks = 0
        val step = steps.getOrNull(engine.index)
        if (step == null || sample.paused) {
            countdownIndex = -1
            countdownPrevRemainingSec = null
            return
        }
        val end = StepEngine.effectiveEnd(step, hasDistanceSignal)
        if (end.type != ResolvedEndType.TIME) {
            countdownIndex = engine.index
            countdownPrevRemainingSec = null
            return
        }
        val remaining = end.value - (sample.activeMs - engine.stepStartActiveMs) / 1000.0
        val prev = if (countdownIndex == engine.index) countdownPrevRemainingSec else end.value
        countdownClicks = countdownCrossings(prev, remaining)
        countdownIndex = engine.index
        countdownPrevRemainingSec = remaining
    }

    fun primeCountdown() {
        val sample = lastSample ?: return
        val step = steps.getOrNull(engine.index) ?: return
        val end = StepEngine.effectiveEnd(step, hasDistanceSignal)
        if (end.type != ResolvedEndType.TIME) return
        countdownIndex = engine.index
        countdownPrevRemainingSec = end.value - (sample.activeMs - engine.stepStartActiveMs) / 1000.0
    }

    fun nextStep() {
        if (!finished) lapPending = true
    }

    private fun closeSegment(index: Int, endedBy: StepEndedBy, stats: StepSegmentStats) {
        val step = steps.getOrNull(index) ?: return
        val none = step.target.kind == ResolvedTargetKind.NONE
        segments.add(
            ActivityStepSegmentWire(
                stepId = step.id,
                index = index,
                role = StepSegmentRole.of(step.role),
                name = step.name,
                startActiveSec = stats.startActiveMs / 1000.0,
                endActiveSec = stats.endActiveMs / 1000.0,
                startDistanceM = stats.startDistanceM,
                endDistanceM = stats.endDistanceM,
                avgPaceSecPerKm = stats.avgPaceSecPerKm,
                avgHr = stats.avgHr,
                avgCadenceSpm = stats.avgCadence,
                avgPowerW = stats.avgPowerW,
                targetKind = step.target.kind,
                targetLow = if (none) null else step.target.low,
                targetHigh = if (none) null else step.target.high,
                targetZone = step.target.zoneLabel,
                inRangePct = stats.inRangePct,
                endedBy = endedBy,
                plannedEnd = StepSegmentPlannedEnd(
                    type = step.end.type,
                    value = if (step.end.type == ResolvedEndType.LAP) null else step.end.value,
                ),
            ),
        )
    }

    /**
     * = `takeSegments` di `GuidedRunSession.swift`: chiude la fase in corso
     * con `finish` (se la struttura non è finita), oppure aggiunge un
     * segmento `extra` per la corsa libera ≥ 30 s dopo l'ultima fase.
     * `null` se non c'è nessun segmento da consegnare.
     */
    fun takeSegments(activeSec: Double, distanceM: Double): JSONObject? {
        val sample = lastSample
        if (!finished && sample != null) {
            val dt = sample.activeMs - engine.stepStartActiveMs
            val dd = sample.distanceM - engine.stepStartDistanceM
            val avgPace = if (dd >= StepEngine.kMinDistanceForPace) (dt / 1000.0) / (dd / 1000.0) else null
            closeSegment(
                engine.index,
                StepEndedBy.FINISH,
                StepSegmentStats(
                    startActiveMs = engine.stepStartActiveMs,
                    endActiveMs = sample.activeMs,
                    startDistanceM = engine.stepStartDistanceM,
                    endDistanceM = sample.distanceM,
                    avgPaceSecPerKm = avgPace,
                    avgHr = if (engine.hrCount > 0) engine.hrSum / engine.hrCount else null,
                    avgCadence = if (engine.cadCount > 0) engine.cadSum / engine.cadCount else null,
                    avgPowerW = if (engine.powerCount > 0) engine.powerSum / engine.powerCount else null,
                    inRangePct = if (engine.evalCount > 0) 100.0 * engine.inBandCount / engine.evalCount else null,
                ),
            )
        } else if (finished) {
            val last = segments.lastOrNull()
            val startSec = last?.endActiveSec ?: 0.0
            if (activeSec - startSec >= EXTRA_MIN_SEC) {
                segments.add(
                    ActivityStepSegmentWire(
                        stepId = "extra",
                        index = segments.size,
                        role = StepSegmentRole.EXTRA,
                        startActiveSec = startSec,
                        endActiveSec = activeSec,
                        startDistanceM = last?.endDistanceM ?: 0.0,
                        endDistanceM = distanceM,
                        targetKind = ResolvedTargetKind.NONE,
                        endedBy = StepEndedBy.FINISH,
                        plannedEnd = StepSegmentPlannedEnd(ResolvedEndType.LAP),
                    ),
                )
            }
        }
        if (segments.isEmpty()) return null
        return buildStepsRecordWire(
            planActivityId = planRef.opt("activityId") as? String,
            profile = profile,
            segments = segments,
            variant = variant,
            // D16 — SEMPRE le fasi espanse davvero eseguite, ridotta o intera.
            plannedStepCount = steps.size,
        )
    }

    fun snapshot(): GuidedRunSnapshot = GuidedRunSnapshot(
        structure = structure,
        planRef = planRef,
        lang = lang,
        title = title,
        sport = sport,
        variant = variant,
        engine = engine,
        segments = segments.toList(),
        profile = profile,
        hasDistanceSignal = hasDistanceSignal,
    )

    /** Mirror al cambio fase e ogni 5 s, mai in pausa (D23); marca come spinto se `true`. */
    fun mirrorDue(nowMs: Long, paused: Boolean): Boolean {
        if (paused) return false
        val changed = engine.index != lastMirroredIndex
        val last = lastMirrorAtMs
        if (!changed && last != null && nowMs - last < MIRROR_EVERY_MS) return false
        lastMirroredIndex = engine.index
        lastMirrorAtMs = nowMs
        return true
    }

    private fun currentSample(): StepSample =
        lastSample ?: StepSample(activeMs = engine.lastActiveMs, distanceM = engine.lastDistanceM)

    fun view(): GuidedRunView {
        val total = steps.size
        val sample = currentSample()
        if (finished) {
            return GuidedRunView(
                index = (total - 1).coerceAtLeast(0),
                total = total,
                label = GuidedStrings.t("free_run", lang),
                remainingText = formatClock(sample.activeMs / 1000.0),
                remainingSubtitle = null,
                target = null,
                targetLabel = "",
                currentValue = null,
                inBand = null,
                nextLabel = null,
                finished = true,
                variant = variant,
                lang = lang,
                title = title,
                sport = sport,
                fraction = 1.0,
                endType = null,
            )
        }
        val step = steps[engine.index]
        val p = StepEngine.progressOf(steps, engine, sample)
        val end = StepEngine.effectiveEnd(step, hasDistanceSignal)
        val remaining = when (end.type) {
            ResolvedEndType.TIME -> formatClock(p.remainingSec ?: 0.0)
            ResolvedEndType.DISTANCE -> formatMeters(p.remainingM ?: 0.0)
            ResolvedEndType.LAP, ResolvedEndType.HR_BELOW ->
                formatClock(((sample.activeMs - engine.stepStartActiveMs) / 1000.0).coerceAtLeast(0.0))
        }
        val current = when (step.target.kind) {
            ResolvedTargetKind.PACE -> p.currentAvgPace
            ResolvedTargetKind.HR -> p.currentAvgHr
            ResolvedTargetKind.POWER -> p.currentAvgPower
            ResolvedTargetKind.CADENCE, ResolvedTargetKind.RPE, ResolvedTargetKind.NONE -> null
        }
        return GuidedRunView(
            index = engine.index,
            total = total,
            label = labelOf(step),
            remainingText = remaining,
            remainingSubtitle = subtitleOf(end),
            target = step.target,
            targetLabel = step.ui?.target ?: "",
            currentValue = current,
            inBand = p.inBand,
            nextLabel = steps.getOrNull(engine.index + 1)?.let { nextLabelOf(it) },
            finished = false,
            variant = variant,
            lang = lang,
            title = title,
            sport = sport,
            fraction = if (end.type == ResolvedEndType.LAP || end.type == ResolvedEndType.HR_BELOW) 0.0 else p.fraction,
            endType = end.type,
        )
    }

    /** `guidedState` (§2.5) — `remaining` per `lap`/`hrBelow` è il sottotitolo («fino al tasto»). */
    fun mirrorPayload(startedAt: Long, elapsedSec: Int, distanceM: Double, hr: Double?): JSONObject {
        val v = view()
        val o = JSONObject()
            .put("cmd", "guidedState")
            .put("platform", "wear")
            .put("startedAt", startedAt)
            .put("index", v.index)
            .put("total", v.total)
            .put("label", v.label)
            .put("remaining", if (v.finished) "" else v.remainingSubtitle ?: v.remainingText)
            .put("target", v.targetLabel)
            .put("elapsedSec", elapsedSec)
            .put("distanceM", distanceM)
            .put("finished", v.finished)
        v.nextLabel?.let { o.put("next", it) }
        v.inBand?.let { o.put("inBand", it) }
        hr?.let { o.put("hr", it) }
        return o
    }

    /** `ui.label` del wire; senza `ui` (checkpoint vecchio) nome o ruolo tradotto, con `i/n` nei blocchi. */
    private fun labelOf(step: ResolvedStep): String {
        if (step.ui != null) return step.uiLabel()
        val base = step.name ?: GuidedStrings.t("role_${step.role.wire}", lang)
        val i = step.repeatIteration
        val n = step.repeatCount
        return if (i != null && n != null) "$base $i/$n" else base
    }

    private fun subtitleOf(end: ResolvedEnd): String? = when (end.type) {
        ResolvedEndType.LAP -> GuidedStrings.t("until_lap", lang)
        ResolvedEndType.HR_BELOW -> GuidedStrings.t("until_hr_below", lang, mapOf("bpm" to dartRound(end.value).toString()))
        else -> null
    }

    private fun nextLabelOf(step: ResolvedStep): String {
        val end = StepEngine.effectiveEnd(step, hasDistanceSignal)
        val suffix = when (end.type) {
            ResolvedEndType.TIME -> formatClock(end.value)
            ResolvedEndType.DISTANCE -> formatMeters(end.value)
            ResolvedEndType.LAP, ResolvedEndType.HR_BELOW -> null
        }
        val label = labelOf(step)
        return if (suffix == null) label else "$label · $suffix"
    }

    private fun formatClock(sec: Double): String {
        val s = dartRound(sec).coerceAtLeast(0L)
        val h = s / 3600
        val m = (s % 3600) / 60
        val r = s % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, r)
        else String.format(Locale.ROOT, "%d:%02d", m, r)
    }

    /** `"420 m"`; da 1 km `"1,2 km"` (punto decimale solo in inglese). */
    private fun formatMeters(m: Double): String {
        val meters = dartRound(m).coerceAtLeast(0L)
        if (meters < 1000) return "$meters m"
        val km = String.format(Locale.ROOT, "%.1f", meters / 1000.0)
            .removeSuffix(".0")
        val sep = if (lang.startsWith("en")) km else km.replace('.', ',')
        return "$sep km"
    }

    companion object {
        const val EXTRA_MIN_SEC = 30.0
        const val MIRROR_EVERY_MS = 5_000L

        fun fromSnapshot(s: GuidedRunSnapshot): GuidedRun {
            val run = GuidedRun(
                structure = s.structure,
                planRef = s.planRef,
                lang = s.lang,
                title = s.title,
                sport = s.sport,
                variant = s.variant,
                profile = s.profile,
                hasDistanceSignal = s.hasDistanceSignal,
                engine = s.engine,
                initialSegments = s.segments,
            )
            // Ultimo campione noto al motore: la UI ha subito qualcosa da
            // mostrare e un takeSegments immediato (recovery a freddo)
            // chiude la fase in corso dove il motore l'aveva lasciata.
            if (s.engine.started) {
                run.lastSample = StepSample(activeMs = s.engine.lastActiveMs, distanceM = s.engine.lastDistanceM)
                // Nessun click spurio al primo tick dopo la ripresa.
                run.primeCountdown()
            }
            return run
        }
    }
}

/**
 * Soglie 3, 2, 1 s attraversate passando da [prevRemainingSec] (`null` =
 * nessun riferimento: conta ogni soglia già raggiunta) a [remainingSec].
 * Il chiamante ne fa UN click per tick.
 */
internal fun countdownCrossings(prevRemainingSec: Double?, remainingSec: Double): Int =
    COUNTDOWN_THRESHOLDS_SEC.count { k ->
        remainingSec <= k && (prevRemainingSec == null || prevRemainingSec > k)
    }

private val COUNTDOWN_THRESHOLDS_SEC = listOf(3.0, 2.0, 1.0)

/**
 * Singleton della corsa guidata al polso (una sessione per volta). Thread:
 * `tick` arriva dal ticker di TrackingService (Dispatchers.Default),
 * `nextStep` dalla UI, `takeSegments` dalla VM — ogni accesso è serializzato.
 */
object GuidedRunRepository {

    private var run: GuidedRun? = null

    private val _current = MutableStateFlow<GuidedRunView?>(null)
    val current: StateFlow<GuidedRunView?> = _current.asStateFlow()

    val isActive: Boolean get() = synchronized(this) { run != null }

    /** Lingua della corsa in corso, `""` se nessuna (GuidedStrings ricade sul device). */
    val lang: String get() = synchronized(this) { run?.lang ?: "" }

    fun start(
        structure: ResolvedStructure,
        planRef: JSONObject,
        lang: String,
        title: String?,
        sport: String,
        variant: String?,
        hasDistanceSignal: Boolean,
        profile: JSONObject? = null,
    ) = synchronized(this) {
        val r = GuidedRun(
            structure = structure,
            planRef = planRef,
            lang = lang,
            title = title,
            sport = sport,
            variant = variant,
            profile = profile,
            hasDistanceSignal = hasDistanceSignal,
        )
        run = r
        _current.value = r.view()
    }

    /** Ripresa dal checkpoint (D18): `engine.started` ⇒ nessun `StepStart` ripetuto. */
    fun restore(snapshot: GuidedRunSnapshot) = synchronized(this) {
        val r = GuidedRun.fromSnapshot(snapshot)
        run = r
        _current.value = r.view()
    }

    /** Puro + stato: ritorna gli eventi del motore, NON vibra e NON parla. */
    fun tick(nowMs: Long, activeMs: Long, distanceM: Double, hr: Double?, paused: Boolean): List<StepEvent> =
        synchronized(this) {
            val r = run ?: return emptyList()
            lastTickAtMs = nowMs
            val events = r.tick(activeMs, distanceM, hr, paused)
            _current.value = r.view()
            events
        }

    /** Istante (orologio del service) dell'ultimo [tick]; `null` se mai. */
    var lastTickAtMs: Long? = null
        private set

    /**
     * Click del countdown 3-2-1 dovuti dall'ultimo [tick] (0 se nessuno);
     * letto una volta, poi azzerato. Solo fasi a tempo (D20 incluso).
     */
    fun takeCountdownClicks(): Int = synchronized(this) {
        val r = run ?: return 0
        val n = r.countdownClicks
        r.countdownClicks = 0
        n
    }

    /** «Fase ›» — il campione successivo porta `lap = true`. */
    fun nextStep() = synchronized(this) {
        run?.nextStep()
        Unit
    }

    /** Record wire `stepSegments` (o `null` senza segmenti), poi [stop]. */
    fun takeSegments(activeSec: Double, distanceM: Double): JSONObject? = synchronized(this) {
        val record = run?.takeSegments(activeSec, distanceM)
        stop()
        record
    }

    fun snapshot(): GuidedRunSnapshot? = synchronized(this) { run?.snapshot() }

    fun planRefWire(): JSONObject? = synchronized(this) { run?.planRef?.let { JSONObject(it.toString()) } }

    fun stepAt(index: Int): ResolvedStep? = synchronized(this) { run?.stepAt(index) }

    /** `true` se è ora di spingere `guidedState` (cambio fase o 5 s, mai in pausa). */
    fun mirrorDue(nowMs: Long, paused: Boolean): Boolean = synchronized(this) {
        run?.mirrorDue(nowMs, paused) ?: false
    }

    /** `guidedState` (§2.5); senza corsa in corso un payload `finished` minimo. */
    fun mirrorPayload(startedAt: Long, elapsedSec: Int, distanceM: Double, hr: Double?): JSONObject =
        synchronized(this) {
            run?.mirrorPayload(startedAt, elapsedSec, distanceM, hr)
                ?: JSONObject()
                    .put("cmd", "guidedState")
                    .put("platform", "wear")
                    .put("startedAt", startedAt)
                    .put("elapsedSec", elapsedSec)
                    .put("distanceM", distanceM)
                    .put("finished", true)
        }

    fun stop() = synchronized(this) {
        run = null
        lastTickAtMs = null
        _current.value = null
    }

    /**
     * Recovery a freddo (checkpoint > 30 min, D18): record con la fase in
     * corso chiusa `finish` dove il motore l'aveva lasciata — NON tocca la
     * corsa del singleton.
     */
    fun closedRecord(snapshot: GuidedRunSnapshot): JSONObject? {
        val r = GuidedRun.fromSnapshot(snapshot)
        return r.takeSegments(snapshot.engine.lastActiveMs / 1000.0, snapshot.engine.lastDistanceM)
    }
}
