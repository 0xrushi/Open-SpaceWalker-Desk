# SpaceDesk

Cross-platform display streaming: one device (the **host**) shares its screen over Wi-Fi, while another (the **client**) renders it full-screen and sends input back. Android-to-Android streaming is supported through the native apps, with desktop host and client support for Linux, macOS, and Windows.

## Platform support

- **Android:** Host and client supported.
- **Linux:** Desktop host and client supported.
- **macOS:** Desktop host and client supported.
- **Windows:** Desktop host and client added, but not yet tested.
- **Space Walker:** Android client only. Most AR devices run Android, so other client platforms are not currently supported.

See [`linux/README.md`](linux/README.md) for desktop setup instructions.

## Architecture

```
┌─────────────── HOST ───────────────┐         ┌────────────── CLIENT ──────────────┐
│ MediaProjection                    │         │                                    │
│   └─ VirtualDisplay (AUTO_MIRROR)  │         │  SurfaceView (full-screen)         │
│        └─ VideoEncoder (H.264)     │  UDP    │      ▲                             │
│             └─ VideoSender ────────┼────────▶│  VideoDecoder ◀─ FrameAssembler    │
│                                    │         │                  ▲                 │
│ ControlServer (TCP, JSON lines) ◀──┼────────▶│  ControlClient   └─ VideoReceiver  │
│   ├─ session/handshake            │         │   ├─ handshake + StartStream       │
│   └─ input ─▶ InputInjection      │         │   └─ touch/nav/text forwarding     │
│              (AccessibilityService)│         │                                    │
│ NsdAdvertiser (mDNS) ──────────────┼────────▶│  NsdDiscovery                      │
└────────────────────────────────────┘         └────────────────────────────────────┘
```

Modules: `:shared` (protocol: control messages, UDP packetization, frame reassembly), `:host` (capture/encode/serve), `:client` (discover/decode/render/input).

### Protocol

Control channel is TCP (port 53210), newline-delimited JSON, polymorphic on `type`: `hello` → `hello_ack` → `start_stream` → `video_config`, then `input` batches, `ping`/`pong`, `bye`. Video is UDP: each H.264 access unit is split into ≤1200-byte packets with a 16-byte header (magic, flags keyframe/config, frameId, seq/total, length). The client reassembles frames and drops stale incomplete ones, so packet loss costs a frame, not the session. Touch coordinates travel normalized (0..1) in stream space; the host maps them to its own screen, the client compensates for letterboxing.

### Input injection

The host's `InputInjectionService` (AccessibilityService) turns the client's touch stream into gestures using `StrokeDescription.continueStroke`, dispatching ~90 ms segments so drags and scrolls feel live rather than replaying after finger-up. Navigation uses `performGlobalAction` (Back/Home/Recents); text is applied to the focused editable node via `ACTION_SET_TEXT`.

## Build & run

1. Open the project in Android Studio (it will sync with Gradle 8.10 / AGP 8.7 / Kotlin 2.1; if building from CLI, run `gradle wrapper` once, then `./gradlew assembleDebug`).
2. Install `:host` on the device whose screen you want to share, `:client` on the viewer.
3. On the host: enable the **SpaceDesk Remote Input** accessibility service (the app links to settings), then tap **Start sharing** and accept the screen-capture prompt.
4. On the client (same Wi-Fi): the host appears via mDNS — tap it, or enter the host's IP manually. Video starts automatically; touch the screen to control the host, use the overlay for Back/Home/Recents and text entry.

### Android 13+: accessibility service blocked

Android 13's **Restricted Settings** feature intentionally blocks accessibility services for apps installed through ADB (sideloaded), because malware commonly abuses accessibility to steal data. This is Android platform behavior and is not caused by the app's code.

To allow the accessibility service:

1. Go to **Settings → Apps → SpaceDesk** (the host app).
2. Tap the **⋮** three-dot menu in the top-right corner.
3. Tap **Allow restricted settings** and confirm.
4. Return to SpaceDesk and enable the accessibility service normally.

This setting tells Android that you explicitly trust the sideloaded app to use sensitive permissions. You only need to enable it once per installation. Uninstalling and reinstalling the app resets it.

## Current scope (v1) and known limitations

Working: H.264 720p–1080p mirroring at 30 fps with loss-tolerant UDP transport, mDNS discovery, manual IP fallback, touch/drag/scroll injection, nav keys, basic text entry, view-only mode (input toggle), single client.

Not yet implemented (deliberate v1 cuts): audio capture (`AudioPlaybackCaptureConfiguration`), encryption/pairing PIN (use trusted networks only), multi-touch/pinch (single pointer), multiple simultaneous clients, USB-tethering transport, adaptive bitrate, true OS-level extended desktop (requires root/OEM; v1 mirrors). Gesture injection is inherently segmented — fast drawing strokes will look slightly quantized; per-key hardware keyboard events aren't possible via Accessibility, hence text-commit semantics.

## Roadmap

v1.1: audio (AAC over the same UDP framing), TLS on the control channel + PIN pairing, keyframe-on-loss request. v2: app-level second screen via `DisplayManager.createVirtualDisplay` Presentation API (true "extend" for cooperating apps), multi-client, USB mode via tethered networking.
