# SpaceDesk — desktop hosts & client (Linux / Windows / macOS)

Python implementations of the same wire protocol as the Android apps — any client works with any host: Android, Linux, Windows, or macOS.

## Windows / macOS host

```bash
pip install -r requirements.txt
python -m spacedesk.desktop_host          # [--fps 30] [--no-input] [--port 53210]
```

Capture is via mss (GDI / CoreGraphics) + libx264, input via pynput (mouse/keyboard). Screen 0 is your primary monitor; SpaceWalker's added screens map to your 2nd/3rd monitors. Windows/macOS have no public API to create monitors, so for extended screens beyond your physical ones, create virtual displays first — **BetterDisplay** (macOS) or **usbmmidd / IddSampleDriver** (Windows), or an HDMI dummy plug — and they're picked up as ordinary monitors. macOS: grant your terminal **Screen Recording** and **Accessibility** permissions (System Settings → Privacy & Security); Retina coordinate scaling is handled automatically.

## Install

```bash
cd linux
python3 -m venv .venv --system-site-packages   # system-site needed for PyGObject (host only)
source .venv/bin/activate
pip install -r requirements.txt
```

The host additionally needs system packages for PyGObject + GStreamer (see comments in `requirements.txt`) and a Wayland desktop with xdg-desktop-portal + PipeWire (any modern GNOME/KDE/wlroots setup has these).

## Client (view & control any host — runs on Linux, Windows, and macOS)

```bash
python -m spacedesk.client              # discover via mDNS
python -m spacedesk.client 192.168.1.5  # or connect by IP
```

The client is fully cross-platform (pygame + PyAV + zeroconf): `pip install -r requirements.txt` on Windows or macOS and run the same command. Prebuilt pygame/av wheels exist for Python ≤3.13 — if pip tries to compile from source on 3.14, use a 3.12/3.13 interpreter for a painless install.

Mouse = touch (click-drag scrolls), typing goes to the host's focused field, Esc = Back, F1 = Home, F2 = Recents, F11 = fullscreen, Ctrl+Q = quit.

## Host (share this desktop to an Android or Linux client)

Input injection uses `/dev/uinput`. One-time setup:

```bash
sudo modprobe uinput
echo 'KERNEL=="uinput", MODE="0660", GROUP="input", OPTIONS+="static_node=uinput"' \
  | sudo tee /etc/udev/rules.d/99-spacedesk-uinput.rules
sudo udevadm control --reload && sudo udevadm trigger
sudo usermod -aG input $USER   # then log out and back in
```

Run:

```bash
python -m spacedesk.host             # or --no-input for view-only
```

Your desktop's screen-share consent dialog appears (the Wayland equivalent of Android's capture prompt) — pick the monitor to share. Clients then discover the host via mDNS or connect by IP, exactly like an Android host.

## SpaceWalker mode (XR multi-screen)

Toggle **SpaceWalker mode** on the Android client's discovery screen, then connect to a Linux host. Plug XR glasses (VITURE etc.) into the phone — they mirror the phone's fullscreen 3D workspace. Up to 3 screens float on an arc.

Controls: **Locked** mode — drag empty space to look around; touch a screen to control that monitor on the host (tap-through). **Edit** mode (✏ button) — drag a screen to move it along the arc and up/down, pinch to resize; ✏/🔒 toggles back. **＋/− Screen** adds/removes screens.

On **GNOME**, added screens create a true virtual (extended) monitor automatically via Mutter's ScreenCast API — no dialog, and a new monitor appears in Settings → Displays that you can drag windows onto. Size it with `--virtual-size 1920x1080` (default). On other desktops, each added screen opens a capture-consent dialog: pick **Virtual monitor / Virtual output** if your portal offers it (recent KDE does) for an extended display; picking an existing monitor mirrors it instead. Head tracking is drag-based in v1 — the VITURE SDK IMU can be wired in later.

Limitations: tap-through to added screens relies on the portal reporting each stream's desktop position (GNOME does; some compositors report (0,0) — taps then land on the wrong monitor). The Android host supports only one screen in SpaceWalker mode.

## Troubleshooting

**Host: `no element "x264enc"`** — the host probes for H.264 encoders in this order: `x264enc` (gst-plugins-ugly, recommended), `openh264enc`, `vah264enc`/`vaapih264enc` (hardware). Install at least one: Arch `sudo pacman -S gst-plugins-ugly`, Debian `sudo apt install gstreamer1.0-plugins-ugly`, or `gst-plugin-va` for hardware encoding.

**Client: `pygame.error: video system not initialized`** — your pygame/SDL build lacks the Wayland backend (common when pip compiles pygame from source, e.g. on Python 3.14). The client auto-falls back to X11/XWayland; if it still fails, force a driver: `SDL_VIDEODRIVER=x11 python -m spacedesk.client`.

**Low capture resolution** — the stream can't exceed what the portal hands over. If the host logs e.g. `capturing 1280x720` on a higher-res monitor, you likely picked a scaled source or a window in the share dialog; re-pick the full monitor, and check your compositor's screen-share resolution settings.

## Notes & limitations

The virtual touchscreen spans the *captured* monitor's coordinate space; on multi-monitor setups some compositors map touch devices to a different output — bind the `spacedesk-touch` device to the shared monitor in your compositor settings if taps land on the wrong screen (e.g. Hyprland `input:touchdevice:output`, sway `input ... map_to_output`). Text injection is keystroke-based ASCII (layout-dependent); non-ASCII characters are skipped. Nav mapping on a Linux host: Back→Esc, Home→Super, Recents→Alt-Tab. Encryption/pairing is not implemented — trusted networks only. A true *extended* (not mirrored) display is possible on wlroots compositors by creating a headless output (`hyprctl output create headless`) and sharing it via the portal picker — the client then shows a second desktop you can drag windows onto.
