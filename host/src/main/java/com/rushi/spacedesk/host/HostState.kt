package com.rushi.spacedesk.host

import kotlinx.coroutines.flow.MutableStateFlow

/** Observable host status shared between the capture service and the UI. */
object HostState {
    val isSharing = MutableStateFlow(false)
    val accessibilityRunning = MutableStateFlow(false)
    val connectedClient = MutableStateFlow<String?>(null)
    val inputAllowed = MutableStateFlow(true)
    val streamInfo = MutableStateFlow<String?>(null)
}
