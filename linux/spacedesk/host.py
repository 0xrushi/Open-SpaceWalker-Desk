"""SpaceDesk Linux host: shares the Wayland desktop with Android/Linux clients.

Single-screen: mirrors the monitor picked in the portal dialog.
SpaceWalker (multi-screen): each `start_stream` with a new screenId opens
another portal session — on GNOME/KDE the picker offers a *virtual* monitor,
i.e. a true extended display, streamed to the client's 3D workspace.

Input arrives over the control channel; touch events carry a screenId and are
mapped into the global desktop layout before uinput injection.

Usage:
    python -m spacedesk.host [--port 53210] [--no-input]
"""

from __future__ import annotations

import argparse
import queue
import socket
import socketserver
import threading
import time
from typing import Dict, Optional

import gi

gi.require_version("Gst", "1.0")
from gi.repository import Gst  # noqa: E402

from . import discovery, protocol
from .sender import VideoSender
from .portal import SOURCE_MONITOR, SOURCE_VIRTUAL, ScreenCast


# ----------------------------------------------------------------- video ----

# Candidate H.264 encoders, tried in order. Templates get kbps/bps/gop filled in.
_ENCODERS = [
    ("x264enc",
     "x264enc tune=zerolatency speed-preset=ultrafast bframes=0 bitrate={kbps} key-int-max={gop}"),
    ("openh264enc",
     "openh264enc usage-type=screen bitrate={bps} gop-size={gop}"),
    ("vah264enc",
     "vah264enc bitrate={kbps} key-int-max={gop}"),
    ("vaapih264enc",
     "vaapih264enc bitrate={kbps} keyframe-period={gop}"),
]


def _pick_encoder(bitrate_bps: int, gop: int) -> str:
    for name, template in _ENCODERS:
        if Gst.ElementFactory.find(name):
            print(f"[host] using H.264 encoder: {name}")
            return template.format(kbps=max(1, bitrate_bps // 1000), bps=bitrate_bps, gop=gop)
    raise RuntimeError(
        "No H.264 encoder element found. Install one of:\n"
        "  Arch:   sudo pacman -S gst-plugins-ugly          (x264enc, recommended)\n"
        "          sudo pacman -S gst-plugin-va              (vah264enc, hardware)\n"
        "  Debian: sudo apt install gstreamer1.0-plugins-ugly"
    )


class Encoder:
    """pipewiresrc → H.264(zerolatency) → appsink, one Annex-B AU per sample."""

    def __init__(self, fd: Optional[int], node_id: int, out_w: int, out_h: int,
                 fps: int, bitrate_bps: int, on_frame,
                 src_caps: str = ""):
        self.on_frame = on_frame
        self._sent_config = False
        encoder = _pick_encoder(bitrate_bps, gop=fps * 2)
        fd_part = f"fd={fd} " if fd is not None else ""
        caps_part = f"! {src_caps} " if src_caps else ""
        desc = (
            f"pipewiresrc {fd_part}path={node_id} do-timestamp=true keepalive-time=1000 "
            f"{caps_part}"
            f"! videorate ! videoscale ! videoconvert "
            f"! video/x-raw,format=I420,width={out_w},height={out_h},framerate={fps}/1 "
            f"! {encoder} "
            f"! h264parse config-interval=-1 "
            f"! video/x-h264,stream-format=byte-stream,alignment=au "
            f"! appsink name=sink emit-signals=true sync=false max-buffers=4 drop=true"
        )
        self.pipeline = Gst.parse_launch(desc)
        sink = self.pipeline.get_by_name("sink")
        sink.connect("new-sample", self._on_sample)

    def _on_sample(self, sink) -> Gst.FlowReturn:
        sample = sink.emit("pull-sample")
        buf = sample.get_buffer()
        ok, info = buf.map(Gst.MapFlags.READ)
        if not ok:
            return Gst.FlowReturn.OK
        try:
            au = bytes(info.data)
        finally:
            buf.unmap(info)

        keyframe = not buf.has_flags(Gst.BufferFlags.DELTA_UNIT)
        if keyframe:
            config = protocol.extract_config(au)
            if config and not self._sent_config:
                self.on_frame(config, False, True)  # standalone SPS/PPS for MediaCodec
                self._sent_config = True
        self.on_frame(au, keyframe, False)
        return Gst.FlowReturn.OK

    def start(self) -> None:
        self.pipeline.set_state(Gst.State.PLAYING)

    def stop(self) -> None:
        self.pipeline.set_state(Gst.State.NULL)


# ---------------------------------------------------------------- screens ---

class ScreenStream:
    """One captured display: capture backend + encoder + sender."""

    def __init__(self, screen_id: int):
        self.id = screen_id
        self.fd: Optional[int] = None
        self.node_id = 0
        self.size = (1920, 1080)
        self.position = (0, 0)
        self.src_caps = ""
        self.is_virtual = False
        self._backend = None  # ScreenCast or MutterVirtualScreen
        self.encoder: Optional[Encoder] = None
        self.sender: Optional[VideoSender] = None

    @classmethod
    def from_portal(cls, screen_id: int, types: int) -> "ScreenStream":
        s = cls(screen_id)
        portal = ScreenCast()
        s._backend = portal
        s.fd, s.node_id, size, s.position = portal.start(types)
        if size[0]:
            s.size = size
        return s

    @classmethod
    def gnome_virtual(cls, screen_id: int, width: int, height: int, fps: int) -> "ScreenStream":
        from .mutter import MutterVirtualScreen
        s = cls(screen_id)
        virt = MutterVirtualScreen()
        s._backend = virt
        s.node_id = virt.node_id
        s.size = (width, height)
        s.is_virtual = True
        # Pin the source caps: PipeWire negotiation fixes the virtual
        # monitor's resolution to exactly what we request here.
        s.src_caps = f"video/x-raw,width={width},height={height}"
        return s

    def refresh_layout(self) -> bool:
        """Mutter assigns the virtual monitor's desktop position after the
        stream starts; pull it for correct tap-through mapping."""
        if not self.is_virtual:
            return False
        pos, size = self._backend.layout()
        changed = size[0] and (pos != self.position or size != self.size)
        if size[0]:
            self.position = pos
            self.size = size
        return bool(changed)

    def stop_stream(self) -> None:
        if self.encoder:
            self.encoder.stop()
            self.encoder = None
        if self.sender:
            self.sender.stop()
            self.sender = None

    def close(self) -> None:
        self.stop_stream()
        if self._backend:
            self._backend.close()


class HostSession:
    def __init__(self, args):
        self.args = args
        Gst.init(None)
        print("[host] requesting screen capture (pick a monitor in the dialog)…")
        self.screens: Dict[int, ScreenStream] = {}
        self.lock = threading.Lock()
        primary = ScreenStream.from_portal(0, SOURCE_MONITOR)
        self.screens[0] = primary
        print(f"[host] screen 0: {primary.size[0]}x{primary.size[1]} at {primary.position}")

        self.input_sink = None
        self._sink_origin = (0, 0)
        if not args.no_input:
            self._rebuild_input()

    # ------------------------------------------------------------- input ----

    def _rebuild_input(self) -> None:
        """(Re)creates the uinput touchscreen spanning all screens' bounding box."""
        if self.args.no_input:
            return
        try:
            from .inputsink import InputSink
            min_x = min(s.position[0] for s in self.screens.values())
            min_y = min(s.position[1] for s in self.screens.values())
            max_x = max(s.position[0] + s.size[0] for s in self.screens.values())
            max_y = max(s.position[1] + s.size[1] for s in self.screens.values())
            if self.input_sink:
                self.input_sink.close()
            self.input_sink = InputSink(max_x - min_x, max_y - min_y)
            self._sink_origin = (min_x, min_y)
            print(f"[host] input region {max_x - min_x}x{max_y - min_y} @ {self._sink_origin}")
        except Exception as ex:
            self.input_sink = None
            print(f"[host] WARNING: input injection unavailable: {ex}")
            print("        (need write access to /dev/uinput — see README)")

    def handle_input(self, events: list[dict]) -> None:
        sink = self.input_sink
        if not sink:
            return
        for ev in events:
            t = ev.get("type")
            if t == "touch":
                screen = self.screens.get(ev.get("screenId", 0))
                if not screen:
                    continue
                gx = screen.position[0] + ev["x"] * screen.size[0] - self._sink_origin[0]
                gy = screen.position[1] + ev["y"] * screen.size[1] - self._sink_origin[1]
                sink.touch_px(ev["action"], int(gx), int(gy))
            elif t == "nav":
                sink.nav(ev.get("action", ""))
            elif t == "text":
                sink.text(ev.get("text", ""))

    # ------------------------------------------------------------ streams ---

    def start_stream(self, req: dict, client_ip: str, send_ctrl) -> None:
        """May block on the portal dialog for new screens — call from a thread."""
        sid = req.get("screenId", 0)
        screen = self.screens.get(sid)
        if screen is None:
            screen = self._create_screen(sid)
            if screen is None:
                return
            with self.lock:
                self.screens[sid] = screen
            print(f"[host] screen {sid}: {screen.size[0]}x{screen.size[1]} at "
                  f"{screen.position}{' (virtual/extended)' if screen.is_virtual else ''}")
            self._rebuild_input()

        with self.lock:
            screen.stop_stream()
            sw, sh = screen.size
            scale = min(req["maxWidth"] / sw, req["maxHeight"] / sh, 1.0)
            out_w = max(2, int(sw * scale) & ~1)
            out_h = max(2, int(sh * scale) & ~1)
            fps = req.get("fps", 30)

            screen.sender = VideoSender((client_ip, req["videoUdpPort"]))
            screen.encoder = Encoder(screen.fd, screen.node_id, out_w, out_h, fps,
                                     req.get("bitrateBps", 6_000_000), screen.sender.submit,
                                     src_caps=screen.src_caps)
            send_ctrl(protocol.video_config(out_w, out_h, fps, screen_id=sid))
            screen.encoder.start()
            print(f"[host] screen {sid}: streaming {out_w}x{out_h}@{fps} "
                  f"→ {client_ip}:{req['videoUdpPort']}")

        if screen.is_virtual:
            # Mutter places the virtual monitor once the stream is live; grab
            # its desktop position so tap-through maps to the right place.
            def settle():
                for _ in range(20):
                    time.sleep(0.5)
                    if screen.refresh_layout():
                        print(f"[host] screen {sid} placed at {screen.position} "
                              f"size {screen.size}")
                        self._rebuild_input()
                        return
            threading.Thread(target=settle, daemon=True).start()

    def _create_screen(self, sid: int) -> Optional[ScreenStream]:
        """Prefers a true extended (virtual) monitor; falls back to the portal picker."""
        import os
        desktop = os.environ.get("XDG_CURRENT_DESKTOP", "").upper()
        if "GNOME" in desktop:
            try:
                vw, vh = self.args.virtual_size
                print(f"[host] creating GNOME virtual monitor {vw}x{vh} for screen {sid} (no dialog)")
                return ScreenStream.gnome_virtual(sid, vw, vh, fps=30)
            except Exception as ex:
                print(f"[host] virtual monitor failed ({ex}); falling back to portal picker")
        print(f"[host] screen {sid} requested — approve the capture dialog "
              f"(pick 'Virtual monitor'/'Virtual output' if offered for an extended display; "
              f"picking an existing monitor mirrors it)")
        try:
            return ScreenStream.from_portal(sid, SOURCE_MONITOR | SOURCE_VIRTUAL)
        except Exception as ex:
            print(f"[host] screen {sid} capture failed/denied: {ex}")
            return None

    def remove_screen(self, sid: int) -> None:
        if sid == 0:
            return
        with self.lock:
            screen = self.screens.pop(sid, None)
        if screen:
            screen.close()
            print(f"[host] screen {sid} removed")
            self._rebuild_input()

    def stop_all_streams(self) -> None:
        """Client gone: stop encoders; drop extra screens (their portal grants die with them)."""
        with self.lock:
            for sid in [s for s in self.screens if s != 0]:
                self.screens.pop(sid).close()
            self.screens[0].stop_stream()
        self._rebuild_input()

    @property
    def primary(self) -> ScreenStream:
        return self.screens[0]

    def close(self) -> None:
        with self.lock:
            for screen in self.screens.values():
                screen.close()
            self.screens.clear()
        if self.input_sink:
            self.input_sink.close()


# ----------------------------------------------------------------- control --

class ControlHandler(socketserver.StreamRequestHandler):
    def handle(self) -> None:
        session: HostSession = self.server.session  # type: ignore[attr-defined]
        self.connection.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        peer = self.client_address[0]
        print(f"[host] client connected from {peer}")
        send_lock = threading.Lock()

        def send(msg: dict) -> None:
            with send_lock:
                try:
                    self.wfile.write(protocol.encode_msg(msg))
                except OSError:
                    pass

        try:
            for raw in self.rfile:
                msg = protocol.decode_msg(raw.decode("utf-8", "replace"))
                if not msg:
                    continue
                t = msg["type"]
                if t == "hello":
                    send(protocol.hello_ack(
                        socket.gethostname(),
                        session.primary.size[0], session.primary.size[1],
                        session.input_sink is not None,
                    ))
                elif t == "start_stream":
                    # Portal dialogs can block for a long time; don't stall input.
                    threading.Thread(
                        target=session.start_stream, args=(msg, peer, send),
                        daemon=True,
                    ).start()
                elif t == "remove_screen":
                    session.remove_screen(msg.get("screenId", -1))
                elif t == "input":
                    session.handle_input(msg.get("events", []))
                elif t == "ping":
                    send({"type": "pong"})
                elif t == "bye":
                    break
        except OSError:
            pass
        finally:
            session.stop_all_streams()
            print(f"[host] client {peer} disconnected")


class ControlServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


def main() -> None:
    parser = argparse.ArgumentParser(description="SpaceDesk Linux host")
    parser.add_argument("--port", type=int, default=protocol.DEFAULT_CONTROL_PORT)
    parser.add_argument("--no-input", action="store_true",
                        help="view-only: don't create uinput devices")
    parser.add_argument("--virtual-size", default="1920x1080",
                        help="resolution of created virtual monitors (WxH)")
    args = parser.parse_args()
    args.virtual_size = tuple(int(v) for v in args.virtual_size.lower().split("x"))

    session = HostSession(args)
    server = ControlServer(("0.0.0.0", args.port), ControlHandler)
    server.session = session  # type: ignore[attr-defined]
    threading.Thread(target=server.serve_forever, daemon=True).start()
    zc = discovery.advertise(args.port)
    print(f"[host] control server on port {args.port}; waiting for clients (Ctrl+C to stop)")

    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("\n[host] shutting down")
    finally:
        zc.close()
        server.shutdown()
        session.close()


if __name__ == "__main__":
    main()
