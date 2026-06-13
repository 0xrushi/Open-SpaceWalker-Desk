"""GNOME-only virtual monitor via org.gnome.Mutter.ScreenCast.RecordVirtual.

Creates a real extended display (no consent dialog — it's a session-internal
API, the same one gnome-remote-desktop uses). The monitor's resolution is
fixed by PipeWire caps negotiation, i.e. by the caps our pipeline requests.
"""

from __future__ import annotations

import threading
from typing import Optional, Tuple

import gi

gi.require_version("Gio", "2.0")
from gi.repository import Gio, GLib  # noqa: E402

_BUS = "org.gnome.Mutter.ScreenCast"
_PATH = "/org/gnome/Mutter/ScreenCast"

CURSOR_EMBEDDED = 1


class MutterVirtualScreen:
    """One virtual monitor + its PipeWire node id."""

    def __init__(self) -> None:
        self._loop = GLib.MainLoop()
        threading.Thread(target=self._loop.run, daemon=True, name="glib-mutter").start()
        self._bus = Gio.bus_get_sync(Gio.BusType.SESSION, None)

        screencast = self._proxy(_PATH, "org.gnome.Mutter.ScreenCast")
        session_path = screencast.call_sync(
            "CreateSession", GLib.Variant("(a{sv})", ({},)),
            Gio.DBusCallFlags.NONE, -1, None,
        ).unpack()[0]
        self._session = self._proxy(session_path, "org.gnome.Mutter.ScreenCast.Session")

        stream_path = self._session.call_sync(
            "RecordVirtual",
            GLib.Variant("(a{sv})", ({"cursor-mode": GLib.Variant("u", CURSOR_EMBEDDED)},)),
            Gio.DBusCallFlags.NONE, -1, None,
        ).unpack()[0]
        self._stream = self._proxy(stream_path, "org.gnome.Mutter.ScreenCast.Stream")

        got_node = threading.Event()
        self.node_id: Optional[int] = None

        def on_signal(_c, _s, _p, _i, name, params):
            if name == "PipeWireStreamAdded":
                self.node_id = params.unpack()[0]
                got_node.set()

        self._bus.signal_subscribe(
            None, "org.gnome.Mutter.ScreenCast.Stream", "PipeWireStreamAdded",
            stream_path, None, Gio.DBusSignalFlags.NONE, on_signal,
        )
        self._session.call_sync("Start", None, Gio.DBusCallFlags.NONE, -1, None)
        if not got_node.wait(timeout=10):
            raise RuntimeError("Mutter did not announce a PipeWire stream "
                               "(GNOME < 42, or screencasting disabled?)")

    def _proxy(self, path: str, iface: str) -> Gio.DBusProxy:
        return Gio.DBusProxy.new_sync(
            self._bus, Gio.DBusProxyFlags.NONE, None, _BUS, path, iface, None,
        )

    def layout(self) -> Tuple[Tuple[int, int], Tuple[int, int]]:
        """Returns ((x, y), (w, h)) of the virtual monitor in the desktop
        layout, once mutter has placed it. (0,0)/(0,0) if not yet known."""
        params = self._stream.get_cached_property("Parameters")
        if params is None:
            return (0, 0), (0, 0)
        d = params.unpack()
        return tuple(d.get("position", (0, 0))), tuple(d.get("size", (0, 0)))

    def close(self) -> None:
        try:
            self._session.call_sync("Stop", None, Gio.DBusCallFlags.NONE, -1, None)
        except GLib.Error:
            pass
        self._loop.quit()
