"""UDP video sender shared by all hosts (Linux/Windows/macOS)."""

from __future__ import annotations

import queue
import socket
import threading

from . import protocol


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
