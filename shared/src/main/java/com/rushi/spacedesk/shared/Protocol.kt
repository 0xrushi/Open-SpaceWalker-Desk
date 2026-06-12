package com.rushi.spacedesk.shared

/**
 * Protocol constants shared by host and client.
 *
 * Transport layout:
 *  - Control channel: TCP, newline-delimited JSON ([ControlMessage]).
 *  - Video channel:   UDP, binary packets ([VideoPacket]) flowing host -> client.
 */
object Protocol {
    const val VERSION = 1

    /** TCP port the host's control server listens on (also advertised via NSD). */
    const val DEFAULT_CONTROL_PORT = 53210

    /** NSD/mDNS service type used for host discovery. */
    const val NSD_SERVICE_TYPE = "_spacedesk._tcp."
    const val NSD_SERVICE_NAME_PREFIX = "SpaceDesk"

    /** Max UDP payload per video packet (stays under typical MTU). */
    const val MAX_VIDEO_PAYLOAD = 1200

    /** Drop incomplete frames older than this many frame ids behind the newest. */
    const val FRAME_REORDER_WINDOW = 8
}
