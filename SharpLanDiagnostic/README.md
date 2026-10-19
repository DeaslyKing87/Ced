# SharpLanDiagnostic 0.3 — MStar/AwoX research

Continues the existing Android Studio project, package com.ced.sharplan.
The original project ZIP and previous source history are preserved.

## Physical-TV results take precedence

The user tested version 0.2 and did not successfully control ANY physical TV.
On the Sharp LC-40UI7352E, RenderingControl changed DLNA renderer volume from
35 to 42 and returned HTTP 200, but physical TV volume/mute did not change.
These were false positives for TV remote control.

Every normal TV button remains locked in 0.3: Power, Volume, Mute, D-pad, OK,
Back, Home, Input, Channel, Menu and numbers. Legacy Sharp TCP read replies,
HTTP success, UPnP replies, vendor action names and observed events do not unlock
anything. The research build contains no TV write path.

Future verification requires an identified command sent by the app, a matching
TV state change where available, AND user confirmation of the physical effect.
The manual IP checkbox confirms device identity only, never command efficacy.

## Confirmed user report (not a guess from the model name)

- TV: Sharp LC-40UI7352E
- IP at test time: 192.168.1.45
- friendlyName: Sharp_UHD_TV (78:dd:d9:48:53:2e)
- manufacturer: MStar
- modelName: MStarDMR
- server: AwoX/1.1
- port at test time: 54871
- presentationURL: /web
- X_ServiceManager control: /MediaRenderer_X_ServiceManager/control
- event: /MediaRenderer_X_ServiceManager/event
- SCPD: /MediaRenderer_X_ServiceManager/scpd.xml

Discovery distinguishes the exact reported MStar/friendlyName/MAC profile from
a TV whose UPnP modelName actually advertises LC-40UI7352E. MStarDMR alone is
not treated as proof of a particular Sharp model.

## Workflow on the phone

1. Connect powered-on TV and S24 to the same Wi-Fi/LAN.
2. Discover TVs and select the reported Sharp/MStar device, or enter its IP.
3. Confirm identity if manually selected.
4. Run read-only inspection. Renderer state is labelled:
   "DLNA renderer controls — not verified TV remote".
5. Check the port field against current discovery; 54871 is the reported default,
   not an assertion that the TV always uses that port.
6. Run "Inspect /web + AwoX SCPD — GET only".
7. Run "Observe ServiceManager events for 30 seconds".
8. During observation, press buttons on the PHYSICAL TV remote. The app sends no keys.
9. Export/share the ZIP containing logs, raw resources, metadata and findings.

The cloud build cannot access the user's private TV IP. These local GET/event
tests must run on the phone. No live /web response or ServiceManager IDs are
claimed until the resulting capture is examined.

## GET-first resource inspection

Fetches /web and exact reported/advertised SCPD endpoints.
Reads same-origin referenced HTML, JS/MJS, JSON, XML and CSS without executing
JavaScript, running a WebView, submitting forms or following arbitrary actions.
Only static resources and narrowly allowed cache/version/language query parameters
are followed. Different hosts, ports, schemes, action queries and action-like
paths are rejected. Same-origin safe redirects are followed within depth limits.

Limits: 16 resources, depth 2 (bounded redirect allowance), 512 KiB per entity,
4 MiB per crawl. Truncation is recorded. Gzip entities are decoded within the
same size bound. Unsupported dynamic endpoints are not guessed or brute-forced.

Captures SCPD action arguments, related state variables, evented variables and
allowed values in raw XML. Extracts keyword contexts and candidate ServiceID,
ActionID, Property/PropertyID values with source URLs/offsets. Text matches are
labelled UNVERIFIED and are never invoked.

GetProperty is not called with unknown identifiers. PerformAction and SetProperty
are never called. No IDs are enumerated through guesses or numeric sweeps.

## Event observation

One temporary GENA SUBSCRIBE is sent to the inspected X_ServiceManager event URL.
A callback binds only to the phone's matching private LAN IPv4 address.
Notifications require the selected TV's source IP, a random callback path,
event headers, a valid SID matching the subscription and an unsigned sequence.
Initial notifications arriving before the SUBSCRIBE response are queued and
validated against its SID afterward.

Observation lasts at most 30 seconds, requests a 45-second lease, never renews,
and attempts UNSUBSCRIBE in a finally block. Activity destruction closes the
listener. HTTP framing, bodies, connection attempts and event counts are bounded.
Raw event XML and decoded property values (including ServiceManagerInfo if
present) are preserved. A missing event is not proof of missing TV control.
Event correlation with physical remote use is not proof of an app command mapping.

## Preserved diagnostics and safety

SSDP, manual IP, advertised descriptions/SCPDs, narrow relevant TCP port checks
and known standard read-only SOAP queries remain. Normal controls never unlock.
The SOAP transport only allows GetVolume/GetMute, GetTransportInfo and
GetProtocolInfo in their standard service namespaces. Legacy TCP is query-only.

Private on-link IPv4 Wi-Fi/Ethernet destinations only. Sockets and HTTP bind to
that network; HTTP proxies and redirects are bypassed for ordinary SOAP/description
requests. Web redirects are validated individually. External entities and DTDs
are rejected. No accounts, cloud runtime endpoints, ads, analytics or subscriptions.

## Logs and raw captures

UI text log persists across restarts and is bounded.
Raw captures are stored separately in app-private captures/session directories
with source, response status/headers, truncation flag and SHA-256 metadata.
Findings are retained in FINDINGS.txt. The 32 MiB capture quota stops new saves
without deleting previous captures; export if the quota is reached.

Export creates a ZIP with all preserved captures and the diagnostic text log.
Share uses a read-only content URI grant for the generated ZIP rather than a huge
text Intent. The provider is not exported and cannot write or expose arbitrary
app-private paths. Android backup is disabled. Choose local storage if you want
exports to remain on the phone; sharing occurs only when explicitly requested.

## Build and install

Retains JDK 17, Gradle 8.9, AGP 8.7.3, SDK 35, minSdk 26.
GitHub Actions runs unit tests, Android lint, compilation, signature/package
verification and checksum generation before uploading SharpLanDiagnostic.apk.
A cached development keystore is used when available for subsequent updates.

Local:
    gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug

APK:
    app/build/outputs/apk/debug/app-debug.apk

Install:
    adb install -r app/build/outputs/apk/debug/app-debug.apk

If Android rejects an update because a previous debug APK has a different
signing key, export its logs, uninstall that old build, then install this APK.

See RESEARCH.md for sources and the remaining unknowns. Build/test success
does not establish physical TV control.
