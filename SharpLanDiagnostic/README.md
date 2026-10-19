# Sharp LAN Diagnostic

Continues the uploaded SharpLanDiagnostic Android Studio project for Samsung Galaxy S24
and the European Sharp LC-40UI7352E (AQUOS NET+ / Sharp SMART).
The original ZIP is preserved in the repository. The extracted project is now tracked
so fixes are reviewable and builds do not overwrite them by re-extracting the ZIP.

## Compatibility and research

No control of the actual TV has been demonstrated. Install the APK and collect results.
No Android TV, Roku, Google TV, guessed HTTP key APIs or guessed RCKY codes are used.

Sharp's user manual explicitly lists LC-40UI7352E and documents AQUOS NET+, network
configuration, Miracast and browsing home-network digital media servers.
The manual does not document an IP remote-control API or an AQUOS Remote Control
network setting for this model. This is absence of documentation, not proof of
impossibility. Its media-server browsing feature does not establish that the TV
advertises a UPnP MediaRenderer or RenderingControl service.

Primary manual, English pages 8–10 (PDF page indexes 7–9):
https://s3.infra.brandquad.io/accounts-media/SHRP/DAM/origin/e473a4ac-f3ff-11ed-a2e3-c6d521dbb1ee.pdf

Sharp Consumer support:
https://www.sharpconsumer.com/support/

Open-source examples of the legacy Sharp interface describe TCP 10002,
four-character operations, four-character parameters, CR termination, and
optional configured username/password. They do not establish LC-40UI7352E support:
https://github.com/benburkhart1/sharp-aquos-remote-control
https://github.com/thehappydinoa/aquosRemote

## Read-only detection

- SSDP M-SEARCH on the Wi-Fi/Ethernet LAN, with a multicast lock.
- Lists device names, model, manufacturer and IP. Exact LC-40UI7352E matches
  are distinguished from other Sharp/UMC candidates.
- Manual IPv4 entry; unidentified devices require explicit target confirmation.
- Manual inspection retries SSDP filtered to the selected IP for descriptions.
- Reads advertised device descriptions and SCPDs, including namespaced XML.
- Checks TCP 80, 443, 10002 and a bounded set of advertised endpoint ports.
- Tests legacy Sharp POWR/VOLM state queries on the selected confirmed TV.
- Stops at banners/authentication prompts; no credential guessing.
- Uses standard read-only SOAP actions only when advertised in the SCPD:
  GetVolume, GetMute, GetTransportInfo and GetProtocolInfo.
- Records requests, responses, faults and connection errors.
- Open ports and service-name words never unlock buttons.

## Controls

Legacy power and volume unlock only after valid power and volume state replies.
Mute requires a valid mute reply.

UPnP volume/mute require the matching read action to respond with valid state
and the corresponding set action to be advertised. This supports only the
capabilities actually exposed, not a claim of full TV remote control.

Navigation, OK, Back, Home, Input, channel and number keys remain locked because
a model-compatible key protocol is not established.

Discovery and inspection never send write commands. Writes happen only when
the user presses an enabled control. Volume changes are one step with advertised
or conservative bounds. Acknowledgements do not prove physical TV effects.
Standby network wake is not guaranteed.

## LAN restrictions

IPv4 only, restricted to a connected private Wi-Fi/Ethernet subnet.
Sockets and HTTP bind to that network, not cellular/VPN routes.
SSDP LOCATION must match its UDP sender IP. Service URLs must match the selected
IP. Redirects, URL credentials, DTDs and external entities are rejected.
Responses, scans and logs are bounded. No broad subnet/port sweep.

SSDP filtering/client isolation may prevent discovery. Manual IP does not reveal
unadvertised UPnP URLs. Failure is not proof that all control is impossible.

## Logs

Logs persist in app-private storage across restarts. Android backup is disabled.
Export writes a text report to a selected file. Choose local phone storage to
keep the export local. Share opens Android's share sheet only when requested.
Reports contain local device identities, addresses and protocol responses.

No account, cloud endpoint, ads, analytics, subscriptions or external runtime
library dependency is required.

## Build

JDK 17, Gradle 8.9, Android Gradle Plugin 8.7.3, SDK 35; minSdk 26.
The existing supported Gradle/SDK pairing is retained.

GitHub Actions builds and uploads the APK after unit tests, Android lint, and
signature/package verification. SDK setup omits the obsolete package "tools",
which caused the original failure.

Local build with installed Gradle 8.9:
    gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug

Optional standard wrapper:
    gradle wrapper --gradle-version 8.9

APK:
    app/build/outputs/apk/debug/app-debug.apk

USB install:
    adb install -r app/build/outputs/apk/debug/app-debug.apk

This is an installable debug APK, not a Play Store production release.
If an earlier APK uses the same package with a different signing key, export
its logs and uninstall it before installing this build.

## Real-TV test

1. Put powered-on TV and S24 on the same LAN; avoid isolated guest Wi-Fi.
2. Discover TVs, select the exact model/candidate, or select and confirm its IP.
3. Run read-only inspection.
4. Check the reported protocol, service actions and responses.
5. Use only controls that actually unlock; verify physical effects.
6. If no compatible interface responds, export the report for investigation.

LAN discovery and physical TV control remain unverified until tested on the
user's actual network and TV.
