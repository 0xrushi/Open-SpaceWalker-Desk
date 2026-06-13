"""SpaceDesk host for Windows and macOS.

Capture: mss (GDI/CoreGraphics screen grabs) → PyAV libx264 zerolatency.
Input:   pynput (SendInput on Windows, CGEvent on macOS).

Screens map to physical monitors: screen 0 = primary, added SpaceWalker
screens = your 2nd/3rd monitors. Windows/macOS expose no public API to create
virtual monitors, so to *extend* beyond your physical monitors use a virtual
display tool first (Windows: usbmmidd/IddSampleDriver or a dummy plug;
macOS: BetterDisplay's virtual screens) — they then appear as ordinary
monitors here.

Usage:
    python -m spacedesk.desktop_host [--port 53210] [--fps 30] [--no-input]

macOS: grant Terminal/Python both "Screen Recording" and "Accessibility"
permissions in System Settings → Privacy & Security.
"""

from __future__ import annotations

import argparse
import platform
import socket
import socketserver
import sys
import threading
import time
from fractions import Fraction
from typing import Dict, Optional

from . import discovery, protocol
from .sender import VideoSender

IS_WINDOWS = platform.system() == "Windows"
IS_MAC = platform.system() == "Darwin"


def _enable_windows_dpi_awareness() -> None:
    """Make mss pixels and SendInput coordinates agree on scaled displays."""
    if not IS_WINDOWS:
        return
    import ctypes
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(2)  # per-monitor DPI aware
    except Exception:
        try:
            ctypes.windll.user32.SetProcessDPIAware()
        except Exception:
            pass


# ----------------------------------------------------------------- video ----

class ScreenEncoder:
    """Grabs one monitor at a fixed fps and emits H.264 Annex-B access units."""

    def __init__(self, monitor: dict, out_w: int, out_h: int, fps: int,
                 bitrate_bps: int, on_frame):
        import av
        self.monitor = monitor
        self.fps = fps
        self.on_frame = on_frame
        self.alive = True
        self._sent_config = False

        self.codec = av.CodecContext.create("libx264", "w")
        self.codec.width = out_w
        self.codec.height = out_h
        self.codec.pix_fmt = "yuv420p"
        self.codec.time_base = Fraction(1, fps)
        self.codec.framerate = Fraction(fps, 1)
        self.codec.bit_rate = bitrate_bps
        self.codec.options = {
            "preset": "ultrafast",
            "tune": "zerolatency",
            "x264-params": f"repeat-headers=1:keyint={fps * 2}:bframes=0",
        }
        self._thread = threading.Thread(target=self._loop, daemon=True,
                                        name=f"capture-{monitor['left']}x{monitor['top']}")

    def start(self) -> None:
        self._thread.start()

    def _loop(self) -> None:
        import av
        import mss
        import numpy as np

        interval = 1.0 / self.fps
        pts = 0
        with mss.mss() as sct:  # must be created in the thread that uses it
            while self.alive:
                t0 = time.monotonic()
                shot = sct.grab(self.monitor)
                frame = av.VideoFrame.from_ndarray(np.asarray(shot), format="bgra")
                frame = frame.reformat(self.codec.width, self.codec.height, "yuv420p")
                frame.pts = pts
                pts += 1
                try:
                    packets = self.codec.encode(frame)
                except av.AVError:
                    continue
                for pkt in packets:
                    data = bytes(pkt)
                    key = bool(pkt.is_keyframe)
                    if key and not self._sent_config:
                        config = protocol.extract_config(data)
                        if config:
                            self.on_frame(config, False, True)
                            self._sent_config = True
                    self.on_frame(data, key, False)
                # Pace to target fps.
                remaining = interval - (time.monotonic() - t0)
                if remaining > 0:
                    time.sleep(remaining)

    def stop(self) -> None:
        self.alive = False
        self._thread.join(timeout=2)


# ----------------------------------------------------------------- input ----

class DesktopInput:
    """Touch → mouse, nav → keys, text → typing, in global desktop coords."""

    def __init__(self) -> None:
        from pynput.keyboard import Controller as Keyboard, Key
        from pynput.mouse import Button, Controller as Mouse
        self.mouse = Mouse()
        self.keyboard = Keyboard()
        self.Button = Button
        self.Key = Key
        self._down = False

    def touch(self, action: str, gx: float, gy: float) -> None:
        self.mouse.position = (int(gx), int(gy))
        if action == "DOWN" and not self._down:
            self.mouse.press(self.Button.left)
            self._down = True
        elif action in ("UP", "CANCEL") and self._down:
            self.mouse.release(self.Button.left)
            self._down = False

    def nav(self, action: str) -> None:
        k = self.Key
        if action == "BACK":
            self._tap(k.esc)
        elif action == "HOME":
            self._tap(k.cmd)  # Win key on Windows, Cmd on macOS
        elif action == "RECENTS":
            mod = k.cmd if IS_MAC else k.alt
            with self.keyboard.pressed(mod):
                self._tap(k.tab)

    def text(self, text: str) -> None:
        self.keyboard.type(text)

    def _tap(self, key) -> None:
        self.keyboard.press(key)
        self.keyboard.release(key)

    def close(self) -> None:
        if self._down:
            self.mouse.release(self.Button.left)


# --------------------------------------------------------------- session ----

class ScreenStream:
    def __init__(self, screen_id: int, monitor: dict, scale: float):
        self.id = screen_id
        self.monitor = monitor  # mss rect: physical px
        self.scale = scale      # physical px per input point (Retina)
        self.encoder: Optional[ScreenEncoder] = None
        self.sender: Optional[VideoSender] = None

    @property
    def size(self) -> tuple[int, int]:
        return self.monitor["width"], self.monitor["height"]

    def input_point(self, nx: float, ny: float) -> tuple[float, float]:
        """Normalized stream coords → global input coords (points)."""
        gx = (self.monitor["left"] + nx * self.monitor["width"]) / self.scale
        gy = (self.monitor["top"] + ny * self.monitor["height"]) / self.scale
        return gx, gy

    def stop_stream(self) -> None:
        if self.encoder:
            self.encoder.stop()
            self.encoder = None
        if self.sender:
            self.sender.stop()
            self.sender = None


class HostSession:
    def __init__(self, args):
        self.args = args
        import mss
        with mss.mss() as sct:
            # monitors[0] = union of all; [1:] = individual monitors.
            self.monitors = list(sct.monitors[1:])
        if not self.monitors:
            sys.exit("No monitors found")
        self.scale = self._display_scale()
        print(f"[host] {len(self.monitors)} monitor(s): "
              + ", ".join(f"{m['width']}x{m['height']}@({m['left']},{m['top']})"
                          for m in self.monitors)
              + (f"  (input scale {self.scale:g})" if self.scale != 1 else ""))

        self.screens: Dict[int, ScreenStream] = {}
        self.lock = threading.Lock()
        self.input: Optional[DesktopInput] = None
        if not args.no_input:
            try:
                self.input = DesktopInput()
            except Exception as ex:
                print(f"[host] WARNING: input injection unavailable: {ex}")
                if IS_MAC:
                    print("        grant Accessibility permission in System Settings")

    def _display_scale(self) -> float:
        """Physical px per logical point (Retina/HiDPI mapping for pynput)."""
        if IS_MAC:
            try:
                from AppKit import NSScreen  # bundled with pynput's pyobjc deps
                logical_w = NSScreen.mainScreen().frame().size.width
                return self.monitors[0]["width"] / logical_w
            except Exception:
                return 1.0
        return 1.0  # Windows: DPI-aware process → px == input units

    @property
    def primary_size(self) -> tuple[int, int]:
        return self.monitors[0]["width"], self.monitors[0]["height"]

    def start_stream(self, req: dict, client_ip: str, send_ctrl) -> None:
        sid = req.get("screenId", 0)
        screen = self.screens.get(sid)
        if screen is None:
            if sid >= len(self.monitors):
                print(f"[host] screen {sid} requested but only {len(self.monitors)} "
                      f"monitor(s) attached — add a virtual display "
                      f"(BetterDisplay on macOS, usbmmidd/IddSampleDriver on Windows)")
                return
            screen = ScreenStream(sid, self.monitors[sid], self.scale)
            with self.lock:
                self.screens[sid] = screen
            print(f"[host] screen {sid} → monitor {sid + 1} "
                  f"({screen.size[0]}x{screen.size[1]})")

        with self.lock:
            screen.stop_stream()
            sw, sh = screen.size
            scale = min(req["maxWidth"] / sw, req["maxHeight"] / sh, 1.0)
            out_w = max(2, int(sw * scale) & ~1)
            out_h = max(2, int(sh * scale) & ~1)
            fps = min(req.get("fps", 30), self.args.fps)

            screen.sender = VideoSender((client_ip, req["videoUdpPort"]))
            screen.encoder = ScreenEncoder(screen.monitor, out_w, out_h, fps,
                                           req.get("bitrateBps", 6_000_000),
                                           screen.sender.submit)
            send_ctrl(protocol.video_config(out_w, out_h, fps, screen_id=sid))
            screen.encoder.start()
            print(f"[host] screen {sid}: streaming {out_w}x{out_h}@{fps} "
                  f"→ {client_ip}:{req['videoUdpPort']}")

    def remove_screen(self, sid: int) -> None:
        with self.lock:
            screen = self.screens.pop(sid, None)
        if screen:
            screen.stop_stream()
            print(f"[host] screen {sid} removed")

    def handle_input(self, events: list[dict]) -> None:
        sink = self.input
        if not sink:
            return
        for ev in events:
            t = ev.get("type")
            if t == "touch":
                screen = self.screens.get(ev.get("screenId", 0))
                if screen:
                    gx, gy = screen.input_point(ev["x"], ev["y"])
                    sink.touch(ev["action"], gx, gy)
            elif t == "nav":
                sink.nav(ev.get("action", ""))
            elif t == "text":
                sink.text(ev.get("text", ""))

    def stop_all_streams(self) -> None:
        with self.lock:
            for screen in self.screens.values():
                screen.stop_stream()
            self.screens.clear()

    def close(self) -> None:
        self.stop_all_streams()
        if self.input:
            self.input.close()


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
                    w, h = session.primary_size
                    send(protocol.hello_ack(socket.gethostname(), w, h,
                                            session.input is not None))
                elif t == "start_stream":
                    threading.Thread(target=session.start_stream,
                                     args=(msg, peer, send), daemon=True).start()
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
    parser = argparse.ArgumentParser(description="SpaceDesk host (Windows/macOS)")
    parser.add_argument("--port", type=int, default=protocol.DEFAULT_CONTROL_PORT)
    parser.add_argument("--fps", type=int, default=30)
    parser.add_argument("--no-input", action="store_true")
    args = parser.parse_args()

    _enable_windows_dpi_awareness()
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
