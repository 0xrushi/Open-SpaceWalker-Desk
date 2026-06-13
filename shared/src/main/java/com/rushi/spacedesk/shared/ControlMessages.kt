package com.rushi.spacedesk.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Control-channel messages, exchanged as newline-delimited JSON over TCP.
 * Polymorphic on the "type" field.
 */
@Serializable
sealed class ControlMessage {

    /** client -> host: first message after connecting. */
    @Serializable
    @SerialName("hello")
    data class Hello(
        val protocolVersion: Int = Protocol.VERSION,
        val deviceName: String,
    ) : ControlMessage()

    /** host -> client: accepts the session and describes the host. */
    @Serializable
    @SerialName("hello_ack")
    data class HelloAck(
        val protocolVersion: Int = Protocol.VERSION,
        val hostName: String,
        val screenWidth: Int,
        val screenHeight: Int,
        val inputAllowed: Boolean,
    ) : ControlMessage()

    /** client -> host: requests a video stream to begin (screenId 0 = primary). */
    @Serializable
    @SerialName("start_stream")
    data class StartStream(
        /** UDP port on the client where video packets should be sent. */
        val videoUdpPort: Int,
        val maxWidth: Int = 1280,
        val maxHeight: Int = 720,
        val fps: Int = 30,
        val bitrateBps: Int = 4_000_000,
        /** SpaceWalker: which virtual screen this stream is for. */
        val screenId: Int = 0,
    ) : ControlMessage()

    /** client -> host: tear down one SpaceWalker screen's stream. */
    @Serializable
    @SerialName("remove_screen")
    data class RemoveScreen(val screenId: Int) : ControlMessage()

    /** host -> client: actual encoder configuration in effect. */
    @Serializable
    @SerialName("video_config")
    data class VideoConfig(
        val width: Int,
        val height: Int,
        val fps: Int,
        val screenId: Int = 0,
    ) : ControlMessage()

    /** client -> host: a batch of input events to inject. */
    @Serializable
    @SerialName("input")
    data class Input(
        val events: List<RemoteInputEvent>,
    ) : ControlMessage()

    /** Either direction: keep-alive. */
    @Serializable
    @SerialName("ping")
    data object Ping : ControlMessage()

    @Serializable
    @SerialName("pong")
    data object Pong : ControlMessage()

    /** Either direction: graceful disconnect. */
    @Serializable
    @SerialName("bye")
    data class Bye(val reason: String = "") : ControlMessage()

    companion object {
        val json = Json {
            ignoreUnknownKeys = true
            classDiscriminator = "type"
            encodeDefaults = true
        }

        fun encode(msg: ControlMessage): String = json.encodeToString(serializer(), msg)
        fun decode(line: String): ControlMessage = json.decodeFromString(serializer(), line)
    }
}

/**
 * Input events sent from client to host.
 * Coordinates are normalized to 0..1 relative to the streamed video frame,
 * so the host can map them onto its own screen regardless of resolutions.
 */
@Serializable
sealed class RemoteInputEvent {

    @Serializable
    @SerialName("touch")
    data class Touch(
        val action: TouchAction,
        val pointerId: Int,
        /** 0..1 in stream space. */
        val x: Float,
        /** 0..1 in stream space. */
        val y: Float,
        val pressure: Float = 1f,
        /** Milliseconds, client monotonic clock; used for gesture duration. */
        val timeMs: Long,
        /** SpaceWalker: which screen the touch landed on. */
        val screenId: Int = 0,
    ) : RemoteInputEvent()

    /** Global navigation actions (Back / Home / Recents). */
    @Serializable
    @SerialName("nav")
    data class Nav(val action: NavAction) : RemoteInputEvent()

    /** Text committed from the client keyboard, applied to the host's focused field. */
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : RemoteInputEvent()
}

@Serializable
enum class TouchAction { DOWN, MOVE, UP, CANCEL }

@Serializable
enum class NavAction { BACK, HOME, RECENTS }
