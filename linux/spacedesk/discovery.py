"""mDNS discovery/advertising, interoperable with Android's NSD ("_spacedesk._tcp.")."""

from __future__ import annotations

import socket
from typing import Callable, Optional

from zeroconf import ServiceBrowser, ServiceInfo, ServiceListener, Zeroconf

from . import protocol


def advertise(port: int, name: Optional[str] = None) -> Zeroconf:
    """Registers this machine as a SpaceDesk host. Returns the Zeroconf handle."""
    zc = Zeroconf()
    hostname = name or f"SpaceDesk-{socket.gethostname()}"
    local_ip = _local_ip()
    info = ServiceInfo(
        protocol.NSD_SERVICE_TYPE,
        f"{hostname}.{protocol.NSD_SERVICE_TYPE}",
        addresses=[socket.inet_aton(local_ip)],
        port=port,
    )
    zc.register_service(info)
    print(f"[discovery] advertising {hostname} at {local_ip}:{port}")
    return zc


class _Listener(ServiceListener):
    def __init__(self, on_found: Callable[[str, str, int], None]):
        self.on_found = on_found

    def add_service(self, zc: Zeroconf, type_: str, name: str) -> None:
        info = zc.get_service_info(type_, name)
        if info and info.addresses:
            self.on_found(
                name.removesuffix("." + protocol.NSD_SERVICE_TYPE),
                socket.inet_ntoa(info.addresses[0]),
                info.port,
            )

    def update_service(self, zc, type_, name) -> None:
        pass

    def remove_service(self, zc, type_, name) -> None:
        pass


def browse(on_found: Callable[[str, str, int], None]) -> Zeroconf:
    """Starts browsing for SpaceDesk hosts; on_found(name, ip, port) per host."""
    zc = Zeroconf()
    ServiceBrowser(zc, protocol.NSD_SERVICE_TYPE, _Listener(on_found))
    return zc


def _local_ip() -> str:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))  # no traffic sent; just picks the route
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()
