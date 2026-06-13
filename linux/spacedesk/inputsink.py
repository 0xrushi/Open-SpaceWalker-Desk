"""Injects remote input into the Linux session via /dev/uinput.

Creates two virtual devices: an absolute touchscreen (taps/drags) and a
keyboard (text + nav). Needs write access to /dev/uinput — see README.
"""

from __future__ import annotations

from evdev import AbsInfo, UInput, ecodes as e

# ASCII -> (KEY_*, needs_shift). Enough for typical text entry.
_CHAR_KEYS = {}
for i, ch in enumerate("abcdefghijklmnopqrstuvwxyz"):
    _CHAR_KEYS[ch] = (getattr(e, f"KEY_{ch.upper()}"), False)
    _CHAR_KEYS[ch.upper()] = (getattr(e, f"KEY_{ch.upper()}"), True)
for i, ch in enumerate("1234567890"):
    _CHAR_KEYS[ch] = (getattr(e, f"KEY_{ch}"), False)
_CHAR_KEYS.update({
    " ": (e.KEY_SPACE, False), "\n": (e.KEY_ENTER, False), "\t": (e.KEY_TAB, False),
    "-": (e.KEY_MINUS, False), "_": (e.KEY_MINUS, True),
    "=": (e.KEY_EQUAL, False), "+": (e.KEY_EQUAL, True),
    ",": (e.KEY_COMMA, False), "<": (e.KEY_COMMA, True),
    ".": (e.KEY_DOT, False), ">": (e.KEY_DOT, True),
    "/": (e.KEY_SLASH, False), "?": (e.KEY_SLASH, True),
    ";": (e.KEY_SEMICOLON, False), ":": (e.KEY_SEMICOLON, True),
    "'": (e.KEY_APOSTROPHE, False), '"': (e.KEY_APOSTROPHE, True),
    "[": (e.KEY_LEFTBRACE, False), "{": (e.KEY_LEFTBRACE, True),
    "]": (e.KEY_RIGHTBRACE, False), "}": (e.KEY_RIGHTBRACE, True),
    "\\": (e.KEY_BACKSLASH, False), "|": (e.KEY_BACKSLASH, True),
    "`": (e.KEY_GRAVE, False), "~": (e.KEY_GRAVE, True),
    "!": (e.KEY_1, True), "@": (e.KEY_2, True), "#": (e.KEY_3, True),
    "$": (e.KEY_4, True), "%": (e.KEY_5, True), "^": (e.KEY_6, True),
    "&": (e.KEY_7, True), "*": (e.KEY_8, True), "(": (e.KEY_9, True),
    ")": (e.KEY_0, True),
})

_NAV_KEYS = {
    "BACK": [e.KEY_ESC],
    "HOME": [e.KEY_LEFTMETA],
    "RECENTS": [e.KEY_LEFTALT, e.KEY_TAB],
}


class InputSink:
    def __init__(self, screen_w: int, screen_h: int):
        self.w = screen_w
        self.h = screen_h
        touch_caps = {
            e.EV_KEY: [e.BTN_TOUCH],
            e.EV_ABS: [
                (e.ABS_X, AbsInfo(0, 0, screen_w - 1, 0, 0, 0)),
                (e.ABS_Y, AbsInfo(0, 0, screen_h - 1, 0, 0, 0)),
            ],
        }
        try:
            self.touch = UInput(touch_caps, name="spacedesk-touch",
                                input_props=[e.INPUT_PROP_DIRECT])
        except TypeError:  # older python-evdev without input_props
            self.touch = UInput(touch_caps, name="spacedesk-touch")

        keys = sorted({k for k, _ in _CHAR_KEYS.values()}
                      | {k for combo in _NAV_KEYS.values() for k in combo}
                      | {e.KEY_LEFTSHIFT, e.KEY_BACKSPACE})
        self.kbd = UInput({e.EV_KEY: keys}, name="spacedesk-kbd")

    # ----------------------------------------------------------- handlers ---

    def handle(self, events: list[dict]) -> None:
        for ev in events:
            t = ev.get("type")
            if t == "touch":
                self._touch(ev)
            elif t == "nav":
                self._nav(ev.get("action", ""))
            elif t == "text":
                self._text(ev.get("text", ""))

    def touch_px(self, action: str, x: int, y: int) -> None:
        """Touch at absolute device coordinates (multi-screen mapping)."""
        self._touch_at(action, min(max(x, 0), self.w - 1), min(max(y, 0), self.h - 1))

    def _touch(self, ev: dict) -> None:
        action = ev["action"]
        x = int(min(max(ev["x"], 0.0), 1.0) * (self.w - 1))
        y = int(min(max(ev["y"], 0.0), 1.0) * (self.h - 1))
        self._touch_at(action, x, y)

    def _touch_at(self, action: str, x: int, y: int) -> None:
        if action == "DOWN":
            self.touch.write(e.EV_ABS, e.ABS_X, x)
            self.touch.write(e.EV_ABS, e.ABS_Y, y)
            self.touch.write(e.EV_KEY, e.BTN_TOUCH, 1)
        elif action == "MOVE":
            self.touch.write(e.EV_ABS, e.ABS_X, x)
            self.touch.write(e.EV_ABS, e.ABS_Y, y)
        elif action in ("UP", "CANCEL"):
            self.touch.write(e.EV_KEY, e.BTN_TOUCH, 0)
        self.touch.syn()

    def nav(self, action: str) -> None:
        self._nav(action)

    def text(self, text: str) -> None:
        self._text(text)

    def _nav(self, action: str) -> None:
        combo = _NAV_KEYS.get(action)
        if not combo:
            return
        for k in combo:
            self.kbd.write(e.EV_KEY, k, 1)
        for k in reversed(combo):
            self.kbd.write(e.EV_KEY, k, 0)
        self.kbd.syn()

    def _text(self, text: str) -> None:
        for ch in text:
            mapping = _CHAR_KEYS.get(ch)
            if mapping is None:
                continue  # non-ASCII not supported via uinput
            key, shift = mapping
            if shift:
                self.kbd.write(e.EV_KEY, e.KEY_LEFTSHIFT, 1)
            self.kbd.write(e.EV_KEY, key, 1)
            self.kbd.write(e.EV_KEY, key, 0)
            if shift:
                self.kbd.write(e.EV_KEY, e.KEY_LEFTSHIFT, 0)
            self.kbd.syn()

    def close(self) -> None:
        self.touch.close()
        self.kbd.close()
