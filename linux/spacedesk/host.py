"""SpaceDesk Linux host: shares the Wayland desktop with Android/Linux clients.

Pipeline: xdg-desktop-portal (consent + monitor pick) → PipeWire → GStreamer
x264 zerolatency → UDP packetizer. Input arrives over the control channel and
is injected through uinput (real touchscreen + keyboard devices).

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
from typing import Optional

import gi

gi.require_version("Gst", "1.0")
from gi.repository import Gst  # noqa: E402

from . import discovery, protocol
from .portal import ScreenCast


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
    """pipewiresrc → x264enc(zerolatency) → appsink, one Annex-B AU per sample."""

    def __init__(self, fd: int, node_id: int, out_w: int, out_h: int,
                 fps: int, bitrate_bps: int, on_frame):
        self.on_frame = on_frame
        self._sent_config = False
        encoder = _pick_encoder(bitrate_bps, gop=fps * 2)
        desc = (
            f"pipewiresrc fd={fd} path={node_id} do-timestamp=true keepalive-time=1000 "
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


class VideoSender:
    """Dedicated send thread; drops delta frames if the queue backs up."""

    def __init__(self, address: tuple[str, int]):
        self.address = address
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.q: "queue.Queue[tuple[bytes, bool, bool]]" = queue.Queue(maxsize=30)
        self.frame_id = 0
        self.alive = True
        threading.Thread(target=self._loop, daemon=True, name="video-send").start()

    def submit(self, frame: bytes, keyframe: bool, config: bool) -> None:
        item = (frame, keyframe, config)
        try:
            self.q.put_nowait(item)
        except queue.Full:
            if keyframe or config:
                try:
                    self.q.get_nowait()
                    self.q.put_nowait(item)
                except queue.Empty:
                    pass

    def _loop(self) -> None:
        while self.alive:
            frame, keyframe, config = self.q.get()
            for pkt in protocol.packetize(frame, self.frame_id, keyframe, config):
                try:
                    self.sock.sendto(pkt, self.address)
                except OSError:
                    pass
            self.frame_id += 1

    def stop(self) -> None:
        self.alive = False
        self.sock.close()


# ----------------------------------------------------------------- control --

class HostSession:
    """Holds the active capture + per-client stream state."""

    def __init__(self, args):
        self.args = args
        Gst.init(None)
        print("[host] requesting screen capture (pick a monitor in the dialog)…")
        self.portal = ScreenCast()
        self.fd, self.node_id, (self.screen_w, self.screen_h) = self.portal.start()
        if not self.screen_w:
            self.screen_w, self.screen_h = 1920, 1080  # portal didn't report; assume
        print(f"[host] capturing {self.screen_w}x{self.screen_h} (pipewire node {self.node_id})")

        self.input_sink = None
        if not args.no_input:
            try:
                from .inputsink import InputSink
                self.input_sink = InputSink(self.screen_w, self.screen_h)
                print("[host] uinput devices created (touch + keyboard)")
            except Exception as ex:  # PermissionError, missing module, …
                print(f"[host] WARNING: input injection unavailable: {ex}")
                print("        (need write access to /dev/uinput — see README)")

        self.encoder: Optional[Encoder] = None
        self.sender: Optional[VideoSender] = None
        self.lock = threading.Lock()

    def start_stream(self, req: dict, client_ip: str, send_ctrl) -> None:
        with self.lock:
            self.stop_stream()
            scale = min(req["maxWidth"] / self.screen_w,
                        req["maxHeight"] / self.screen_h, 1.0)
            out_w = max(2, int(self.screen_w * scale) & ~1)
            out_h = max(2, int(self.screen_h * scale) & ~1)
            fps = req.get("fps", 30)

            self.sender = VideoSender((client_ip, req["videoUdpPort"]))
            self.encoder = Encoder(self.fd, self.node_id, out_w, out_h, fps,
                                   req.get("bitrateBps", 6_000_000), self.sender.submit)
            send_ctrl(protocol.video_config(out_w, out_h, fps))
            self.encoder.start()
            print(f"[host] streaming {out_w}x{out_h}@{fps} → {client_ip}:{req['videoUdpPort']}")

    def stop_stream(self) -> None:
        if self.encoder:
            self.encoder.stop()
            self.encoder = None
        if self.sender:
            self.sender.stop()
            self.sender = None

    def handle_input(self, events: list[dict]) -> None:
        if self.input_sink:
            self.input_sink.handle(events)

    def close(self) -> None:
        self.stop_stream()
        if self.input_sink:
            self.input_sink.close()
        self.portal.close()


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
                        socket.gethostname(), session.screen_w, session.screen_h,
                        session.input_sink is not None,
                    ))
                elif t == "start_stream":
                    session.start_stream(msg, peer, send)
                elif t == "input":
                    session.handle_input(msg.get("events", []))
                elif t == "ping":
                    send({"type": "pong"})
                elif t == "bye":
                    break
        except OSError:
            pass
        finally:
            session.stop_stream()
            print(f"[host] client {peer} disconnected")


class ControlServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


def main() -> None:
    parser = argparse.ArgumentParser(description="SpaceDesk Linux host")
    parser.add_argument("--port", type=int, default=protocol.DEFAULT_CONTROL_PORT)
    parser.add_argument("--no-input", action="store_true",
                        help="view-only: don't create uinput devices")
    args = parser.parse_args()

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
