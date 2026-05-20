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
}
