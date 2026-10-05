package com.jimtime.wear.guided

/*
 * F7 (Schede multisport: al polso, WP-KE, D7) — port LETTERALE di
 * `advance`, `progressOf` ed `effectiveEnd` di
 * `FE/lib/modules/session/guided/step_engine.dart` (regole 1–9, stesse
 * costanti, stesso ordine). Funzione pura: nessuna lettura dell'ora (J9),
 * il tempo è `StepSample.activeMs` (ms ATTIVI, già al netto delle pause).
 * Aritmetica identica al Dart: `dt / 1000` è una divisione in virgola
 * mobile, `.round()` è [dartRound] (metà lontano da zero).
 */
object StepEngine {
    const val kOffTargetMinDt: Long = 20000L
    const val kOffTargetPaceToleranceSecPerKm: Double = 8.0
    const val kOffTargetHrToleranceBpm: Double = 3.0
    const val kOffTargetCadenceToleranceSpm: Double = 3.0
    const val kOffTargetPowerToleranceW: Double = 10.0
    const val kOffTargetRepeatMs: Long = 30000L
    const val kMinDistanceForPace: Double = 30.0

    fun advance(
        steps: List<ResolvedStep>,
        s: StepEngineState,
        x: StepSample,
    ): Pair<StepEngineState, List<StepEvent>> {
        val events = mutableListOf<StepEvent>()

        // Rule 9 — sessione strutturata finita: corsa libera, nessun evento.
        if (s.index >= steps.size) {
            return Pair(s.copy(lastActiveMs = x.activeMs, lastDistanceM = x.distanceM), events)
        }

        // Rule 1 — prima chiamata (anche se in pausa): StepStart(0) prima di tutto.
        val isFirstCall = !s.started
        if (isFirstCall) {
            events.add(StepEvent.StepStart(s.index))
        }

        // Rule 2 — in pausa: stato invariato (salvo `started`).
        if (x.paused) {
            return Pair(s.copy(started = true), events)
        }

        var index = s.index
        var stepStartActiveMs = s.stepStartActiveMs
        var stepStartDistanceM = s.stepStartDistanceM
        var hrSum = s.hrSum
        var hrCount = s.hrCount
        var cadSum = s.cadSum
        var cadCount = s.cadCount
        var powerSum = s.powerSum
        var powerCount = s.powerCount
        var inBandCount = s.inBandCount
        var evalCount = s.evalCount
        var offTarget = s.offTarget
        var lastOffTargetCueMs = s.lastOffTargetCueMs
        var halfwayFired = s.halfwayFired
        var lastPartFired = s.lastPartFired

        // Rule 3 — accumula hr/cadenza/potenza PRIMA di valutare la fine.
        if (x.hr != null) {
            hrSum += x.hr
            hrCount++
        }
        if (x.cadenceSpm != null) {
            cadSum += x.cadenceSpm
            cadCount++
        }
        if (x.powerW != null) {
            powerSum += x.powerW
            powerCount++
        }

        // Il tasto «Fase successiva» chiude SOLO lo step attivo (rule 4).
        var lapPending = x.lap

        // Rule 4 — fine step, con recupero esatto dopo una sospensione
        // (loop limitato a `steps.size` giri).
        var guard = 0
        while (guard <= steps.size) {
            if (index >= steps.size) break
            val step = steps[index]
            val dt = x.activeMs - stepStartActiveMs
            val dd = x.distanceM - stepStartDistanceM

            var endedBy: StepEndedBy? = null
            if (lapPending) {
                endedBy = StepEndedBy.LAP
            } else {
                when (step.end.type) {
                    ResolvedEndType.TIME ->
                        if (dt / 1000.0 >= step.end.value) endedBy = StepEndedBy.AUTO
                    ResolvedEndType.DISTANCE ->
                        if (dd >= step.end.value) endedBy = StepEndedBy.AUTO
                    ResolvedEndType.LAP -> {}
                    ResolvedEndType.HR_BELOW ->
                        if (x.hr != null && x.hr < step.end.value && dt >= 10000L) {
                            endedBy = StepEndedBy.HR_BELOW
                        }
                }
            }
            if (endedBy == null) break
            lapPending = false

            val avgPace = if (dd >= kMinDistanceForPace) (dt / 1000.0) / (dd / 1000.0) else null
            val avgHr = if (hrCount > 0) hrSum / hrCount else null
            val avgCadence = if (cadCount > 0) cadSum / cadCount else null
            val avgPower = if (powerCount > 0) powerSum / powerCount else null
            val inRangePct = if (evalCount > 0) (100 * inBandCount).toDouble() / evalCount else null
            events.add(
                StepEvent.StepEnd(
                    index,
                    endedBy,
                    StepSegmentStats(
                        startActiveMs = stepStartActiveMs,
                        endActiveMs = x.activeMs,
                        startDistanceM = stepStartDistanceM,
                        endDistanceM = x.distanceM,
                        avgPaceSecPerKm = avgPace,
                        avgHr = avgHr,
                        avgCadence = avgCadence,
                        avgPowerW = avgPower,
                        inRangePct = inRangePct,
                    ),
                ),
            )

            val isLast = index == steps.size - 1
            if (isLast) {
                index = steps.size
                events.add(StepEvent.WorkoutEnd(index))
                break
            }

            // Reset accumulatori per il nuovo step.
            hrSum = 0.0
            hrCount = 0
            cadSum = 0.0
            cadCount = 0
            powerSum = 0.0
            powerCount = 0
            inBandCount = 0
            evalCount = 0
            offTarget = false
            lastOffTargetCueMs = null
            halfwayFired = false
            lastPartFired = false

            // Recupero ESATTO, solo per una chiusura AUTO: la dimensione che ha
            // chiuso lo step avanza di esattamente il previsto; l'altra riparte
            // dal campione corrente. F7 — una chiusura col tasto (LAP) o per FC
            // riparte SEMPRE dal campione corrente.
            if (endedBy != StepEndedBy.AUTO) {
                stepStartActiveMs = x.activeMs
                stepStartDistanceM = x.distanceM
            } else {
                when (step.end.type) {
                    ResolvedEndType.TIME -> {
                        stepStartActiveMs += dartRound(step.end.value * 1000)
                        stepStartDistanceM = x.distanceM
                    }
                    ResolvedEndType.DISTANCE -> {
                        stepStartDistanceM += step.end.value
                        stepStartActiveMs = x.activeMs
                    }
                    ResolvedEndType.LAP, ResolvedEndType.HR_BELOW -> {
                        stepStartActiveMs = x.activeMs
                        stepStartDistanceM = x.distanceM
                    }
                }
            }

            index++
            events.add(StepEvent.StepStart(index))
            guard++
        }

        // Rule 5/6/7 — valutati UNA sola volta per campione, sullo step
        // corrente dopo il loop di rule 4.
        if (index < steps.size) {
            val step = steps[index]
            val dt = x.activeMs - stepStartActiveMs
            val dd = x.distanceM - stepStartDistanceM

            // Rule 5 — halfway.
            if (!halfwayFired) {
                val fraction: Double? = when (step.end.type) {
                    ResolvedEndType.TIME -> if (step.end.value > 0) (dt / 1000.0) / step.end.value else null
                    ResolvedEndType.DISTANCE -> if (step.end.value > 0) dd / step.end.value else null
                    ResolvedEndType.LAP, ResolvedEndType.HR_BELOW -> null
                }
                if (fraction != null && fraction >= 0.5) {
                    events.add(StepEvent.StepHalfway(index))
                    halfwayFired = true
                }
            }

            // Rule 6 — ultima parte.
            if (!lastPartFired) {
                if (step.end.type == ResolvedEndType.TIME && step.end.value >= 30) {
                    val remainingSec = step.end.value - dt / 1000.0
                    if (remainingSec <= 10) {
                        events.add(StepEvent.StepLastPart(index, seconds = dartRound(remainingSec).toInt()))
                        lastPartFired = true
                    }
                } else if (step.end.type == ResolvedEndType.DISTANCE && step.end.value >= 300) {
                    val remainingM = step.end.value - dd
                    if (remainingM <= 100) {
                        events.add(StepEvent.StepLastPart(index, meters = dartRound(remainingM).toInt()))
                        lastPartFired = true
                    }
                }
            }

            // Rule 7 — off-target (isteresi d'ingresso/uscita). H3: la zona
            // `walk` non genera MAI un fuori-target; statistiche e
            // `inRangePct` restano.
            val isWalkZone = step.target.zoneLabel == "walk"
            if (dt >= kOffTargetMinDt &&
                step.target.kind != ResolvedTargetKind.NONE &&
                step.target.kind != ResolvedTargetKind.RPE
            ) {
                val avg: Double? = when (step.target.kind) {
                    ResolvedTargetKind.PACE -> if (dd >= kMinDistanceForPace) (dt / 1000.0) / (dd / 1000.0) else null
                    // Media corrente dello step, non il campione istantaneo.
                    ResolvedTargetKind.HR -> if (hrCount > 0) hrSum / hrCount else null
                    ResolvedTargetKind.CADENCE -> if (cadCount > 0) cadSum / cadCount else null
                    ResolvedTargetKind.POWER -> if (powerCount > 0) powerSum / powerCount else null
                    ResolvedTargetKind.RPE, ResolvedTargetKind.NONE -> null
                }
                if (avg != null) {
                    val currentAvg: Double = avg
                    evalCount++
                    val low = step.target.low
                    val high = step.target.high
                    val tolerance: Double = when (step.target.kind) {
                        ResolvedTargetKind.PACE -> kOffTargetPaceToleranceSecPerKm
                        ResolvedTargetKind.HR -> kOffTargetHrToleranceBpm
                        ResolvedTargetKind.CADENCE -> kOffTargetCadenceToleranceSpm
                        ResolvedTargetKind.POWER -> kOffTargetPowerToleranceW
                        else -> 0.0
                    }
                    val inPureBand = currentAvg >= low && currentAvg <= high
                    if (inPureBand) inBandCount++
                    val inToleratedBand = currentAvg >= (low - tolerance) && currentAvg <= (high + tolerance)

                    fun direction(): OffTargetDirection {
                        if (step.target.kind == ResolvedTargetKind.PACE) {
                            return if (currentAvg < low) OffTargetDirection.TOO_FAST else OffTargetDirection.TOO_SLOW
                        }
                        if (step.target.kind == ResolvedTargetKind.HR) {
                            return if (currentAvg > high) OffTargetDirection.HR_HIGH else OffTargetDirection.HR_LOW
                        }
                        if (step.target.kind == ResolvedTargetKind.POWER) {
                            return if (currentAvg > high) OffTargetDirection.POWER_HIGH else OffTargetDirection.POWER_LOW
                        }
                        return if (currentAvg > high) OffTargetDirection.CADENCE_HIGH else OffTargetDirection.CADENCE_LOW
                    }

                    if (isWalkZone) {
                        // H3 — nessun evento per `walk`; i contatori restano.
                    } else if (!offTarget) {
                        if (!inToleratedBand) {
                            events.add(StepEvent.StepOffTarget(index, direction(), currentAvg))
                            offTarget = true
                            lastOffTargetCueMs = x.activeMs
                        }
                    } else if (inPureBand) {
                        events.add(StepEvent.StepBackOnTarget(index))
                        offTarget = false
                        lastOffTargetCueMs = null
                    } else if (!inToleratedBand) {
                        val lastCue = lastOffTargetCueMs
                        if (lastCue != null && x.activeMs - lastCue >= kOffTargetRepeatMs) {
                            events.add(StepEvent.StepOffTarget(index, direction(), currentAvg))
                            lastOffTargetCueMs = x.activeMs
                        }
                    }
                }
            }
        }

        return Pair(
            StepEngineState(
                index = index,
                stepStartActiveMs = stepStartActiveMs,
                stepStartDistanceM = stepStartDistanceM,
                hrSum = hrSum,
                hrCount = hrCount,
                cadSum = cadSum,
                cadCount = cadCount,
                powerSum = powerSum,
                powerCount = powerCount,
                inBandCount = inBandCount,
                evalCount = evalCount,
                offTarget = offTarget,
                lastOffTargetCueMs = lastOffTargetCueMs,
                halfwayFired = halfwayFired,
                lastPartFired = lastPartFired,
                lastActiveMs = x.activeMs,
                lastDistanceM = x.distanceM,
            ),
            events,
        )
    }

    /** = Dart `progressOf` — per l'UI, nessun side effect. */
    fun progressOf(
        steps: List<ResolvedStep>,
        s: StepEngineState,
        x: StepSample,
    ): StepProgress {
        if (s.index >= steps.size) {
            return StepProgress(fraction = 1.0)
        }
        val step = steps[s.index]
        val dt = x.activeMs - s.stepStartActiveMs
        val dd = x.distanceM - s.stepStartDistanceM

        var remainingSec: Double? = null
        var remainingM: Double? = null
        var fraction = 0.0
        when (step.end.type) {
            ResolvedEndType.TIME -> {
                val total = step.end.value
                remainingSec = if (total > 0) (total - dt / 1000.0).coerceIn(0.0, total) else 0.0
                fraction = if (total > 0) ((dt / 1000.0) / total).coerceIn(0.0, 1.0) else 0.0
            }
            ResolvedEndType.DISTANCE -> {
                val total = step.end.value
                remainingM = if (total > 0) (total - dd).coerceIn(0.0, total) else 0.0
                fraction = if (total > 0) (dd / total).coerceIn(0.0, 1.0) else 0.0
            }
            ResolvedEndType.LAP, ResolvedEndType.HR_BELOW -> fraction = 0.0
        }

        val avgPace = if (dd >= kMinDistanceForPace) (dt / 1000.0) / (dd / 1000.0) else null
        val avgHr = if (s.hrCount > 0) s.hrSum / s.hrCount else null
        val avgCadence = if (s.cadCount > 0) s.cadSum / s.cadCount else null
        val avgPower = if (s.powerCount > 0) s.powerSum / s.powerCount else null

        val current: Double? = when (step.target.kind) {
            ResolvedTargetKind.PACE -> avgPace
            // Media corrente dello step — stessa semantica di rule 7.
            ResolvedTargetKind.HR -> avgHr
            ResolvedTargetKind.CADENCE -> avgCadence
            ResolvedTargetKind.POWER -> avgPower
            ResolvedTargetKind.RPE, ResolvedTargetKind.NONE -> null
        }
        val inBand: Boolean? = current?.let { it >= step.target.low && it <= step.target.high }

        return StepProgress(
            remainingSec = remainingSec,
            remainingM = remainingM,
            fraction = fraction,
            currentAvgPace = avgPace,
            currentAvgHr = avgHr,
            currentAvgPower = avgPower,
            inBand = inBand,
        )
    }

    /**
     * Una fase a DISTANZA senza segnale di distanza (tapis roulant) diventa
     * «fino al tasto» a runtime (D12 F1, D20 F7).
     */
    fun effectiveEnd(step: ResolvedStep, hasDistanceSignal: Boolean): ResolvedEnd {
        if (step.end.type == ResolvedEndType.DISTANCE && !hasDistanceSignal) {
            return ResolvedEnd(ResolvedEndType.LAP, 0.0)
        }
        return step.end
    }
}
