# SpaceDesk — Linux host & client

Python implementations of the same wire protocol as the Android apps, so all four combinations work: Linux↔Linux, Linux client + Android host, Android client + Linux host, and Android↔Android.

## Install

```bash
cd linux
python3 -m venv .venv --system-site-packages   # system-site needed for PyGObject (host only)
source .venv/bin/activate
pip install -r requirements.txt
```

The host additionally needs system packages for PyGObject + GStreamer (see comments in `requirements.txt`) and a Wayland desktop with xdg-desktop-portal + PipeWire (any modern GNOME/KDE/wlroots setup has these).

## Client (view & control an Android or Linux host)

```bash
python -m spacedesk.client              # discover via mDNS
python -m spacedesk.client 192.168.1.5  # or connect by IP
```

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

## Troubleshooting

**Host: `no element "x264enc"`** — the host probes for H.264 encoders in this order: `x264enc` (gst-plugins-ugly, recommended), `openh264enc`, `vah264enc`/`vaapih264enc` (hardware). Install at least one: Arch `sudo pacman -S gst-plugins-ugly`, Debian `sudo apt install gstreamer1.0-plugins-ugly`, or `gst-plugin-va` for hardware encoding.

**Client: `pygame.error: video system not initialized`** — your pygame/SDL build lacks the Wayland backend (common when pip compiles pygame from source, e.g. on Python 3.14). The client auto-falls back to X11/XWayland; if it still fails, force a driver: `SDL_VIDEODRIVER=x11 python -m spacedesk.client`.

**Low capture resolution** — the stream can't exceed what the portal hands over. If the host logs e.g. `capturing 1280x720` on a higher-res monitor, you likely picked a scaled source or a window in the share dialog; re-pick the full monitor, and check your compositor's screen-share resolution settings.

## Notes & limitations

The virtual touchscreen spans the *captured* monitor's coordinate space; on multi-monitor setups some compositors map touch devices to a different output — bind the `spacedesk-touch` device to the shared monitor in your compositor settings if taps land on the wrong screen (e.g. Hyprland `input:touchdevice:output`, sway `input ... map_to_output`). Text injection is keystroke-based ASCII (layout-dependent); non-ASCII characters are skipped. Nav mapping on a Linux host: Back→Esc, Home→Super, Recents→Alt-Tab. Encryption/pairing is not implemented — trusted networks only. A true *extended* (not mirrored) display is possible on wlroots compositors by creating a headless output (`hyprctl output create headless`) and sharing it via the portal picker — the client then shows a second desktop you can drag windows onto.
