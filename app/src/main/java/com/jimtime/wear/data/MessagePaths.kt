package com.jimtime.wear.data

object MessagePaths {
    // Phone → Watch
    const val PATH_SESSION = "/jimtime/session"
    const val PATH_ROUTE   = "/jimtime/route"

    // Watch → Phone
    const val PATH_WATCH   = "/jimtime/watch"

    // Command keys (same protocol as iOS WatchBridge)
    const val CMD_START_SESSION  = "startSession"
    const val CMD_STOP_SESSION   = "stopSession"
    const val CMD_PAUSE_SESSION  = "pauseSession"
    const val CMD_RESUME_SESSION = "resumeSession"
    const val CMD_ROUTE_POINT    = "routePoint"

    const val CMD_STOP_FROM_WATCH  = "stopActivity"
    const val CMD_PAUSE_FROM_WATCH = "pauseActivity"
    const val CMD_RESUME_FROM_WATCH = "resumeActivity"
}
