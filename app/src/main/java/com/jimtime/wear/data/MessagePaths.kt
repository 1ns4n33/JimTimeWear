package com.jimtime.wear.data

object MessagePaths {
    // Phone → Watch
    const val PATH_SESSION = "/jimtime/session"
    const val PATH_ROUTE   = "/jimtime/route"

    // Watch → Phone
    const val PATH_WATCH   = "/jimtime/watch"

    // Discriminator on every command — absent/`activity` = legacy
    // outdoor/indoor flow, `workout` = gym session protocol.
    const val KEY_KIND     = "kind"
    const val KIND_WORKOUT = "workout"

    // Command keys (same protocol as iOS WatchBridge)
    const val CMD_START_SESSION  = "startSession"
    const val CMD_STOP_SESSION   = "stopSession"
    const val CMD_PAUSE_SESSION  = "pauseSession"
    const val CMD_RESUME_SESSION = "resumeSession"
    const val CMD_ROUTE_POINT    = "routePoint"

    // Workout protocol — phone → watch
    const val CMD_UPDATE_CURSOR = "updateCursor"
    const val CMD_START_REST    = "startRest"
    const val CMD_CLEAR_REST    = "clearRest"

    // Watch → phone (workout actions)
    const val CMD_COMPLETE_SET   = "completeSet"
    const val CMD_SKIP_REST      = "skipRest"

    const val CMD_STOP_FROM_WATCH  = "stopActivity"
    const val CMD_PAUSE_FROM_WATCH = "pauseActivity"
    const val CMD_RESUME_FROM_WATCH = "resumeActivity"

    // Plan days: phone pushes the active plan's day list; the watch can
    // browse it offline and ask the phone to start one (needs phone).
    // Unlike iOS applicationContext, Wear messages aren't cached — the
    // watch re-requests the list at launch to cover missed pushes.
    const val CMD_PLAN_DAYS         = "planDays"
    const val CMD_START_PLAN_DAY    = "startPlanDay"
    const val CMD_REQUEST_PLAN_DAYS = "requestPlanDays"

    // F5b (Nuoto, §7) — serie del coach (proprie + assegnate, ≤10/≤40),
    // forwarded verbatim dal telefono come planDays/intervalConfig.
    const val CMD_SWIM_WORKOUTS = "swimWorkouts"

    // Standalone session delivery at reconnection: with GPS points =
    // routeSync, without (indoor on the wrist) = sessionSync.
    const val CMD_ROUTE_SYNC   = "routeSync"
    const val CMD_SESSION_SYNC = "sessionSync"
    /// Phone → watch: conferma che una routeSync/sessionSync è stata
    /// persistita lato phone. Solo questo comando autorizza a svuotare
    /// PendingRouteStore — un send riuscito a livello di trasporto NON
    /// basta (il phone potrebbe essere morto prima di scrivere su DB).
    const val CMD_SYNC_ACK = "syncAck"
    /// Phone → watch: la routeSync/sessionSync è stata RICEVUTA ma NON
    /// salvata (non loggato, sessione già aperta sul telefono, salvataggio
    /// fallito, payload illeggibile — `reason` porta il motivo). A
    /// differenza di CMD_SYNC_ACK l'item RESTA in coda: solo il motivo
    /// mostrato cambia (PendingSyncCoordinator/PendingSyncStatusStore),
    /// mirror di `syncStatus` in WatchConnectivity.swift lato iOS.
    const val CMD_SYNC_STATUS = "syncStatus"
    /// Phone → watch: rispedisci SUBITO tutta la coda pending-sync (dopo un
    /// login, o al ritorno in foreground del telefono) — nessuno stato da
    /// resuscitare, si limita a rilanciare gli invii come farebbe il retry
    /// periodico.
    const val CMD_REQUEST_PENDING_SYNC = "requestPendingSync"
    /// Sessione palestra/intervalli standalone dal polso → telefono.
    const val CMD_GYM_SESSION_SYNC = "gymSessionSync"

    // "Oggi" home: riepilogo giornaliero phone → watch e coda acqua
    // watch → phone (vedi spec-contract.md "Watch Oggi home wire contract").
    const val CMD_DAILY_SUMMARY         = "dailySummary"
    const val CMD_REQUEST_DAILY_SUMMARY = "requestDailySummary"
    const val CMD_WATER_DELTA           = "waterDelta"

    // F7 (Schede multisport al polso, §2.1–2.5) — capacità, avvio guidato
    // dal telefono, mirror della corsa guidata al polso, striscia fase del
    // companion. Tutti message-only.
    /// Watch → phone: annuncio di capacità (all'avvio e prima di ogni
    /// requestPlanDays), `{platform, caps, appVersion, build}`.
    const val CMD_WATCH_HELLO = "watchHello"
    /// Phone → watch (companion F1): fase corrente guidata dal telefono.
    const val CMD_GUIDED_STEP = "guidedStep"
    /// Watch → phone: mirror di una corsa guidata/da piano posseduta dal polso.
    const val CMD_PLAN_ACTIVITY_STARTED = "planActivityStarted"
    const val CMD_GUIDED_STATE          = "guidedState"
    const val CMD_PLAN_ACTIVITY_ENDED   = "planActivityEnded"
    /// F7 (J17) — watch → phone: `startSession{wristAuthoritative}` scartato
    /// da GuidedStartPolicy, `{platform, startedAt, reason}` con `reason` ∈
    /// `liveSession|replay|stale`. Solo se raggiungibile, senza retry.
    const val CMD_PLAN_ACTIVITY_START_IGNORED = "planActivityStartIgnored"

    /// `startSession{wristAuthoritative:true}` = il polso possiede la
    /// sessione (TrackingService + motore), il telefono fa solo mirror.
    const val KEY_WRIST_AUTHORITATIVE = "wristAuthoritative"
    const val KEY_GUIDED = "guided"
    const val KEY_PLAN   = "plan"

    const val PLATFORM_WEAR = "wear"
    val WATCH_CAPS = listOf("guidedRunV1", "sportDaysV1", "guidedMultiV1")

    /// Extra dell'Intent che apre MainActivity da PhoneMessageService (avvio
    /// dal telefono) o dal chip OngoingActivity: MainActivity naviga alla
    /// rotta `session` se c'è una sessione attiva (J12).
    const val EXTRA_OPEN_SESSION = "jt_open_session"

    /// Extra della notifica «Tocca per iniziare»: MainActivity, in primo
    /// piano, consuma l'avvio dal telefono rimasto in sospeso perché la FGS
    /// da background era stata negata (WristGuidedStart.consumePending).
    const val EXTRA_PENDING_START = "jt_pending_start"
}
