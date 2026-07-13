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

    // Standalone session delivery at reconnection: with GPS points =
    // routeSync, without (indoor on the wrist) = sessionSync.
    const val CMD_ROUTE_SYNC   = "routeSync"
    const val CMD_SESSION_SYNC = "sessionSync"
    /// Sessione palestra/intervalli standalone dal polso → telefono.
    const val CMD_GYM_SESSION_SYNC = "gymSessionSync"
}
