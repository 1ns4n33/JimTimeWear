package com.jimtime.wear.guided

/**
 * F7 (Schede multisport: al polso, WP-KE, D5/§2.4) — decisione PURA per un
 * `startSession` con `wristAuthoritative: true` arrivato dal telefono.
 * Sottoinsieme di `GuidedStartPolicy.swift` (C2): sul Wear non esiste la
 * corsa con `handle(workoutConfiguration)`, quindi niente `upgrade`.
 * Nessuna lettura dell'ora (J9): `nowMs` è iniettato dal chiamante.
 */
object GuidedStartPolicy {
    /** Un comando più vecchio di 10 minuti rispetto ad ORA è morto. */
    const val STALE_WINDOW_MS: Long = 10L * 60L * 1000L

    const val REASON_REPLAY = "replay"
    const val REASON_STALE = "stale"
    const val REASON_LIVE_SESSION = "liveSession"

    sealed class Decision {
        /** Nessuna sessione viva: avvio pieno. */
        data object Start : Decision()

        /** Scartato: `reason` ∈ `replay` | `stale` | `liveSession`. */
        data class Ignore(val reason: String) : Decision()
    }

    /**
     * @param startedAtMs `startSession.startedAt` (epoch ms) del comando.
     * @param nowMs orologio del polso ORA (epoch ms).
     * @param lastGuidedStartAtMs ultimo `startedAt` guidato già applicato, `null` se mai.
     * @param sessionIsIdle nessuna sessione (di nessun tipo) viva sul polso.
     */
    fun decide(startedAtMs: Long, nowMs: Long, lastGuidedStartAtMs: Long?, sessionIsIdle: Boolean): Decision {
        // Deduplica: stesso comando ri-consegnato o uno più vecchio di quello
        // già applicato (come Swift C2.1: `startedAt <= last`).
        if (lastGuidedStartAtMs != null && startedAtMs <= lastGuidedStartAtMs) {
            return Decision.Ignore(REASON_REPLAY)
        }
        if (nowMs - startedAtMs > STALE_WINDOW_MS) {
            return Decision.Ignore(REASON_STALE)
        }
        if (sessionIsIdle) {
            return Decision.Start
        }
        return Decision.Ignore(REASON_LIVE_SESSION)
    }
}
