"""Wayland screen capture via the xdg-desktop-portal ScreenCast interface.

Yields a PipeWire fd + node id that GStreamer's pipewiresrc can consume.
The portal shows the desktop's standard "share your screen" picker, so the
user chooses which monitor to stream (the Wayland equivalent of Android's
MediaProjection consent dialog).
"""

from __future__ import annotations

import threading
from typing import Optional, Tuple

import gi

gi.require_version("Gio", "2.0")
from gi.repository import Gio, GLib  # noqa: E402

_PORTAL_BUS = "org.freedesktop.portal.Desktop"
_PORTAL_PATH = "/org/freedesktop/portal/desktop"

SOURCE_MONITOR = 1
SOURCE_WINDOW = 2
SOURCE_VIRTUAL = 4  # GNOME/KDE: creates a brand-new virtual (extended) monitor
CURSOR_EMBEDDED = 2


class ScreenCast:
    """Synchronous wrapper around the async portal request/response dance."""

    def __init__(self) -> None:
        self._loop = GLib.MainLoop()
        threading.Thread(target=self._loop.run, daemon=True, name="glib-loop").start()
        self._bus = Gio.bus_get_sync(Gio.BusType.SESSION, None)
        self._proxy = Gio.DBusProxy.new_sync(
            self._bus, Gio.DBusProxyFlags.NONE, None,
            _PORTAL_BUS, _PORTAL_PATH, "org.freedesktop.portal.ScreenCast", None,
        )
        self._sender = self._bus.get_unique_name()[1:].replace(".", "_")
        self._counter = 0
        self._session: Optional[str] = None

    # ------------------------------------------------------------ plumbing --

    def _request(self, method: str, signature: str, *args, **options) -> dict:
        """Calls a portal method and blocks until its Request emits Response."""
        self._counter += 1
        token = f"spacedesk{self._counter}"
        opts = {k: v for k, v in options.items()}
        opts["handle_token"] = GLib.Variant("s", token)
        request_path = f"/org/freedesktop/portal/desktop/request/{self._sender}/{token}"

        done = threading.Event()
        outcome: dict = {}

        def on_response(_conn, _sender, _path, _iface, _signal, params):
            code, results = params.unpack()
            outcome["code"] = code
            outcome["results"] = results
            done.set()

        sub = self._bus.signal_subscribe(
            _PORTAL_BUS, "org.freedesktop.portal.Request", "Response",
            request_path, None, Gio.DBusSignalFlags.NONE, on_response,
        )
        try:
            self._proxy.call_sync(
                method, GLib.Variant(signature, (*args, opts)),
                Gio.DBusCallFlags.NONE, -1, None,
            )
            if not done.wait(timeout=120):
                raise TimeoutError(f"portal {method}: no response (dialog dismissed?)")
        finally:
            self._bus.signal_unsubscribe(sub)

        if outcome["code"] != 0:
            raise RuntimeError(f"portal {method} denied/cancelled (code {outcome['code']})")
        return outcome["results"]

    # ------------------------------------------------------------- public ---

    def start(self, types: int = SOURCE_MONITOR) -> Tuple[int, int, Tuple[int, int], Tuple[int, int]]:
        """Runs the full consent flow.

        Returns (pipewire_fd, node_id, (w, h), (x, y)) — position is the
        stream's place in the global desktop layout when the portal reports it.
        Pass types=SOURCE_MONITOR|SOURCE_VIRTUAL to let the user create a new
        virtual monitor (true extended display on GNOME/KDE).
        """
        self._counter_session = getattr(self, "_counter_session", 0) + 1
        results = self._request(
            "CreateSession", "(a{sv})",
            session_handle_token=GLib.Variant("s", f"spacedesk_session{self._counter_session}"),
        )
        self._session = results["session_handle"]

        self._request(
            "SelectSources", "(oa{sv})", self._session,
            types=GLib.Variant("u", types),
            multiple=GLib.Variant("b", False),
            cursor_mode=GLib.Variant("u", CURSOR_EMBEDDED),
        )

        results = self._request("Start", "(osa{sv})", self._session, "")
        streams = results["streams"]
        if not streams:
            raise RuntimeError("portal returned no streams")
        node_id, props = streams[0]
        size = tuple(props.get("size", (0, 0)))
        position = tuple(props.get("position", (0, 0)))

        fd_variant, fd_list = self._proxy.call_with_unix_fd_list_sync(
            "OpenPipeWireRemote",
            GLib.Variant("(oa{sv})", (self._session, {})),
            Gio.DBusCallFlags.NONE, -1, None, None,
        )
        fd = fd_list.get(fd_variant.unpack()[0])
        return fd, node_id, size, position

    def close(self) -> None:
        if self._session:
            try:
                Gio.DBusProxy.new_sync(
                    self._bus, Gio.DBusProxyFlags.NONE, None,
                    _PORTAL_BUS, self._session, "org.freedesktop.portal.Session", None,
                ).call_sync("Close", None, Gio.DBusCallFlags.NONE, -1, None)
            except GLib.Error:
                pass
        self._loop.quit()
