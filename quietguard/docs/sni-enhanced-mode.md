# Experimental SNI Filtering

The normal mode keeps the narrow DNS-only VPN route and remains available from Android 8. The optional SNI mode requires Android 10 or later because the pinned HevSocks5Tunnel 2.19.3 AAR declares minSdk 29. It routes all IPv4 and IPv6 traffic from selected network-filter apps through the Android VPN. Hev sends TCP and UDP connections to a loopback SOCKS5 server in the same process. The proxy opens protected direct sockets, applies the existing domain rules to UDP and TCP DNS, and inspects the TLS ClientHello SNI before connecting TCP/443 upstream.

The SOCKS proxy does not install a certificate and does not decrypt HTTPS. It reads only the initial TLS ClientHello bytes, forwards them unchanged when allowed, and closes a matching connection. UDP/443 is dropped to encourage fallback from QUIC/HTTP/3 to TCP TLS. Apps without fallback can lose network functions. The UI therefore leaves SNI mode off by default and requires explicit confirmation.

SNI filtering cannot see encrypted SNI (ECH), app DoH inside other TLS connections, direct-IP content that does not expose a matching SNI, cached ads, or ads served from a shared host. A blocked network request can still leave an app waiting or showing an empty slot. Real-device compatibility and ad-effectiveness testing are required before describing it as generally effective.

Dependency: official `hev-socks5-tunnel.aar` release 2.19.3, pinned SHA-256 `ab6c581e6157680cfe5687ee7bbdcf95405c006c2db3701879ec44b9e5f83836`. It includes native libraries for arm64-v8a, armeabi-v7a, x86_64, and x86. The MIT license is included beside the AAR and in the APK as `sni-tunnel-license.txt`.
