"""SpaceDesk Linux client: connects to a host (Android or Linux), renders the
H.264 stream in a window, and forwards mouse/keyboard as remote input.

Usage:
    python -m spacedesk.client                 # discover hosts via mDNS
    python -m spacedesk.client 192.168.1.5     # connect directly

Controls:
    Mouse           tap / drag on the host
    Typing          sent to the host's focused field (commit-style on Android)
    Esc             Back (Android)   F1  Home    F2  Recents
    F11             toggle fullscreen
    Ctrl+Q          quit
"""

from __future__ import annotations

import queue
import socket
import sys
import threading
import time

from . import discovery, protocol


class Connection:
    """Control TCP + video UDP, mirroring the Android client's networking."""

    def __init__(self, host: str, port: int):
        self.tcp = socket.create_connection((host, port), timeout=5)
        self.tcp.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self.tcp.settimeout(None)
        self.udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.udp.bind(("0.0.0.0", 0))
        self.udp.settimeout(0.5)

        self.video_config: "queue.Queue[dict]" = queue.Queue()
        self.frames: "queue.Queue[tuple[bytes, bool]]" = queue.Queue(maxsize=60)
        self.input_allowed = True
        self.alive = True
        self._send_q: "queue.Queue[dict]" = queue.Queue(maxsize=512)

        self._assembler = protocol.FrameAssembler(self._on_frame)
        threading.Thread(target=self._read_loop, daemon=True).start()
        threading.Thread(target=self._send_loop, daemon=True).start()
        threading.Thread(target=self._udp_loop, daemon=True).start()

    # --- outbound ---

    def send(self, msg: dict) -> None:
        try:
            self._send_q.put_nowait(msg)
        except queue.Full:
            pass

    def _send_loop(self) -> None:
        while self.alive:
            msg = self._send_q.get()
            try:
                self.tcp.sendall(protocol.encode_msg(msg))
            except OSError:
                self.alive = False
            if msg.get("type") == "bye":
                break

    # --- inbound control ---

    def _read_loop(self) -> None:
        buf = b""
        try:
            while self.alive:
                data = self.tcp.recv(4096)
                if not data:
                    break
                buf += data
                while b"\n" in buf:
                    line, buf = buf.split(b"\n", 1)
                    msg = protocol.decode_msg(line.decode("utf-8", "replace"))
                    if not msg:
                        continue
                    t = msg["type"]
                    if t == "hello_ack":
                        self.input_allowed = msg.get("inputAllowed", True)
                        if not self.input_allowed:
                            print("[client] host reports input disabled")
                    elif t == "video_config":
                        self.video_config.put(msg)
                    elif t == "ping":
                        self.send({"type": "pong"})
                    elif t == "bye":
                        print(f"[client] host disconnected: {msg.get('reason', '')}")
                        self.alive = False
        except OSError:
            pass
        self.alive = False

    # --- inbound video ---

    def _udp_loop(self) -> None:
        while self.alive:
            try:
                data, _ = self.udp.recvfrom(65536)
                self._assembler.on_packet(data)
            except socket.timeout:
                continue
            except OSError:
                break

    def _on_frame(self, frame: bytes, keyframe: bool, config: bool) -> None:
        try:
            self.frames.put_nowait((frame, config))
        except queue.Full:
            try:  # drop oldest, keep latency bounded
                self.frames.get_nowait()
                self.frames.put_nowait((frame, config))
            except queue.Empty:
                pass

    def close(self) -> None:
        self.send(protocol.bye())
        time.sleep(0.2)
        self.alive = False
        for s in (self.tcp, self.udp):
            try:
                s.close()
            except OSError:
                pass


def pick_host(argv: list[str]) -> tuple[str, int]:
    if len(argv) > 1:
        target = argv[1]
        if ":" in target:
            host, port = target.rsplit(":", 1)
            return host, int(port)
        return target, protocol.DEFAULT_CONTROL_PORT

    print("Searching for hosts (Ctrl+C to abort)…")
    found: "queue.Queue[tuple[str, str, int]]" = queue.Queue()
    zc = discovery.browse(lambda name, ip, port: found.put((name, ip, port)))
    try:
        name, ip, port = found.get(timeout=15)
        print(f"Found {name} at {ip}:{port}")
        return ip, port
    except queue.Empty:
        sys.exit("No hosts found. Pass an IP explicitly: python -m spacedesk.client <ip>")
    finally:
        zc.close()


def main() -> None:
    import av  # noqa: deferred so --help stays fast
    import pygame

    host, port = pick_host(sys.argv)
    conn = Connection(host, port)
    conn.send(protocol.hello(socket.gethostname()))

    pygame.init()
    # pygame.init() swallows display-driver failures; init the display module
    # explicitly. On Linux, fall back to X11 (XWayland) if Wayland fails.
    try:
        pygame.display.init()
    except pygame.error as first_err:
        import os
        import platform
        if platform.system() == "Linux":
            os.environ["SDL_VIDEODRIVER"] = "x11"
            try:
                pygame.display.init()
            except pygame.error as ex:
                sys.exit(
                    f"Could not initialize a display ({ex}).\n"
                    "Try: SDL_VIDEODRIVER=wayland or =x11, and make sure SDL2 "
                    "was built with that backend."
                )
        else:
            sys.exit(f"Could not initialize a display: {first_err}")
    pygame.display.set_caption(f"SpaceDesk — {host}")
    info = pygame.display.Info()

    def window_for_video(vw: int, vh: int) -> tuple[int, int]:
        """Window sized to the video's aspect ratio, fitting 85% of the screen."""
        scale = min(info.current_w * 0.85 / vw, info.current_h * 0.85 / vh)
        return max(320, int(vw * scale)), max(240, int(vh * scale))

    screen = pygame.display.set_mode(
        (int(info.current_w * 0.6), int(info.current_h * 0.6)), pygame.RESIZABLE,
    )

    # Ask for a stream sized to our screen (long side both ways, like Android).
    long_side = max(info.current_w, info.current_h)
    conn.send(protocol.start_stream(conn.udp.getsockname()[1], long_side, long_side))

    cfg = conn.video_config.get(timeout=10)
    vw, vh = cfg["width"], cfg["height"]
    print(f"[client] stream {vw}x{vh} @ {cfg['fps']}fps")
    # Reshape the window to match the stream (portrait host -> portrait window).
    screen = pygame.display.set_mode(window_for_video(vw, vh), pygame.RESIZABLE)

    codec = av.CodecContext.create("h264", "r")
    fullscreen = False
    current = None  # latest decoded frame as pygame Surface
    mouse_down = False
    clock = pygame.time.Clock()

    def fit_rect(win_w: int, win_h: int) -> "pygame.Rect":
        scale = min(win_w / vw, win_h / vh)
        w, h = int(vw * scale), int(vh * scale)
        return pygame.Rect((win_w - w) // 2, (win_h - h) // 2, w, h)

    def send_touch(action: str, pos: tuple[int, int]) -> None:
        rect = fit_rect(*screen.get_size())
        if rect.w == 0 or rect.h == 0:
            return
        x = min(max((pos[0] - rect.x) / rect.w, 0.0), 1.0)
        y = min(max((pos[1] - rect.y) / rect.h, 0.0), 1.0)
        conn.send(protocol.input_msg(
            [protocol.touch_event(action, x, y, int(time.monotonic() * 1000))],
        ))

    nav_keys = {pygame.K_ESCAPE: "BACK", pygame.K_F1: "HOME", pygame.K_F2: "RECENTS"}

    try:
        while conn.alive:
            # --- handle new video config (host rotation) ---
            try:
                cfg = conn.video_config.get_nowait()
                vw, vh = cfg["width"], cfg["height"]
                codec = av.CodecContext.create("h264", "r")
                current = None
                if not fullscreen:  # host rotated: reshape the window too
                    screen = pygame.display.set_mode(window_for_video(vw, vh), pygame.RESIZABLE)
                print(f"[client] stream reconfigured to {vw}x{vh}")
            except queue.Empty:
                pass

            # --- decode pending frames ---
            try:
                while True:
                    frame, _is_config = conn.frames.get_nowait()
                    for packet in codec.parse(frame):
                        for decoded in codec.decode(packet):
                            img = decoded.to_ndarray(format="rgb24")
                            current = pygame.image.frombuffer(
                                img.tobytes(), (decoded.width, decoded.height), "RGB",
                            )
            except queue.Empty:
                pass
            except av.AVError:
                pass  # stale packets around a stream restart

            # --- events ---
            for event in pygame.event.get():
                if event.type == pygame.QUIT:
                    raise KeyboardInterrupt
                elif event.type == pygame.MOUSEBUTTONDOWN and event.button == 1:
                    mouse_down = True
                    send_touch("DOWN", event.pos)
                elif event.type == pygame.MOUSEMOTION and mouse_down:
                    send_touch("MOVE", event.pos)
                elif event.type == pygame.MOUSEBUTTONUP and event.button == 1:
                    mouse_down = False
                    send_touch("UP", event.pos)
                elif event.type == pygame.KEYDOWN:
                    if event.key == pygame.K_q and event.mod & pygame.KMOD_CTRL:
                        raise KeyboardInterrupt
                    elif event.key == pygame.K_F11:
                        fullscreen = not fullscreen
                        screen = pygame.display.set_mode(
                            (0, 0) if fullscreen else
                            (int(info.current_w * 0.8), int(info.current_h * 0.8)),
                            pygame.FULLSCREEN if fullscreen else pygame.RESIZABLE,
                        )
                    elif event.key in nav_keys:
                        conn.send(protocol.input_msg([protocol.nav_event(nav_keys[event.key])]))
                    elif event.key in (pygame.K_RETURN, pygame.K_KP_ENTER):
                        conn.send(protocol.input_msg([protocol.text_event("\n")]))
                elif event.type == pygame.TEXTINPUT:
                    conn.send(protocol.input_msg([protocol.text_event(event.text)]))
                elif event.type == pygame.VIDEORESIZE and not fullscreen:
                    # pygame 2 resizes the display surface itself; re-creating
                    # the window here breaks resizing under Wayland.
                    screen = pygame.display.get_surface()

            # --- render ---
            screen.fill((0, 0, 0))
            if current is not None:
                rect = fit_rect(*screen.get_size())
                scaled = pygame.transform.scale(current, (rect.w, rect.h))
                screen.blit(scaled, rect.topleft)
            pygame.display.flip()
            clock.tick(60)
    except KeyboardInterrupt:
        pass
    finally:
        conn.close()
        pygame.quit()


if __name__ == "__main__":
    main()
