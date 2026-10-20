# Pickixo VPN

Free Android VPN that connects to the volunteer-run public servers of the
[VPN Gate Academic Experiment Project](https://www.vpngate.net/) over **MS-SSTP**, with AdMob ads.

- **Bangla guide to publishing on Google Play:** [`docs/PLAY_STORE_GUIDE_BN.md`](docs/PLAY_STORE_GUIDE_BN.md)
- Privacy policy to host: [`docs/privacy-policy.html`](docs/privacy-policy.html)
- Permission email to VPN Gate: [`docs/email-to-vpngate.txt`](docs/email-to-vpngate.txt)
- Store graphics and ASO texts: [`store/`](store/), [`docs/ASO_STORE_LISTING.md`](docs/ASO_STORE_LISTING.md)

## Build

Open the folder in Android Studio (AGP 8.13, Gradle 8.13, Kotlin 2.2.20, compileSdk/targetSdk 36, minSdk 26) and press Run.
Android Studio generates the Gradle wrapper scripts. Debug builds always use Google's test ad units.
A release build refuses to start until the real AdMob IDs and privacy-policy URL are set in `gradle.properties`.

## How it works

```
Other apps ─► TUN (VpnService) ─► PickixoVpnService ─► SstpClient ══ PPP over SSTP over TLS :443 ══► VPN Gate server ─► Internet
Pickixo's own traffic (ads, server list) ─► normal network (addDisallowedApplication)
```

| File | Role |
|---|---|
| `sstp/SstpClient.java` | SSTP client: TLS, HTTP `SSTP_DUPLEX_POST`, Call Connect/Connected with SHA-256 crypto binding, PPP LCP, PAP / CHAP-MD5 / MS-CHAPv2, IPCP (IP + DNS), echo keepalives |
| `sstp/MsChapV2.java`, `sstp/Md4.java` | MS-CHAPv2 (RFC 2759) and MPPE keys (RFC 3079); MD4 because Android has none |
| `sstp/VpnGateParser.java` | Parses `http://www.vpngate.net/api/iphone/`, rejects private/invalid IPs, derives SSTP ports |
| `ServerRepository.kt` | Downloads, caches (30 min) and groups servers by country |
| `PickixoVpnService.kt` | Tries the best servers for a country, builds the TUN interface, pumps packets, reconnects without leaking, switches countries |
| `MainActivity.kt` | Connect button, location picker, timer, menu |
| `ConsentActivity.kt` | Prominent disclosure required by Play's VpnService policy |
| `Ads.kt` | UMP consent, adaptive banner, interstitial (only after Connect, at most every 3 minutes) |

The engine is plain Java with no Android dependencies. It was tested on a desktop JVM against RFC 2759/3079
test vectors and an independent Python SSTP/PPP test server (MS-CHAPv2, PAP and CHAP-MD5 sessions,
option reject/nak paths, protocol reject, crypto binding MAC, echo, IP round trips).

## Known limitations

- VPN Gate servers use self-signed certificates, so the server's identity is not verified (traffic is still TLS-encrypted).
- The crypto-binding key for MS-CHAPv2 follows MS-SSTP as implemented here. SoftEther-based servers are known to be lenient,
  but this has not been checked against a live VPN Gate server yet.
- IPv4 only; IPv6 is captured and dropped to prevent leaks.
- Server availability and speed depend on VPN Gate volunteers.
