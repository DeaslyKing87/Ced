# SharpLanDiagnostic 0.4 — cyberpunk service research

Continues the existing project and package com.ced.sharplan. All TV keys remain locked.
v0.3 proved the AwoX renderer did not control physical TV volume/mute and its event
observer exposed only empty ServiceManagerInfo. That experiment will not be repeated.

## Install and use
Download the GitHub Actions SharpLanDiagnostic-APK artifact, extract it and install
SharpLanDiagnostic.apk on the S24. Connect the phone and powered-on TV to the same Wi-Fi.

The app automatically finds 192.168.1.45, verifies the advertised Sharp/MStar identity,
reads device/services and performs narrow connect-only checks. If identity lookup fails,
Manual / Discovery Options opens. Automatic checks never start a full scan or send keys.

Press DEEP SCAN TV and confirm that 192.168.1.45 is your TV. The app rechecks identity,
then attempts TCP ports 1-65535 only on that address. Default: 16 workers, 300 ms timeout,
at most 80 connects/sec. Allow about 15-25 minutes. Keep the app in the foreground.
STOP SCAN preserves partial results. Leaving the app cancels the scan and saves results.

Open ports appear live. After the connect phase, read-only fingerprinting collects
passive banners, appropriate root HTTP responses, TLS/certificate metadata and
evidence-based WebSocket handshakes. No authentication, redirects, pairing or
unknown action/command values are sent. Known renderer ports are not re-investigated.
The 32 most useful findings are prioritised on the dashboard; exports include every port.

Advanced / Logs contains Export and Share ZIP. Export after completion or cancellation.
Includes research evidence, identity descriptions, raw banners/HTTP/TLS data,
per-service findings, full 65535-port CSV, summary and retained captures from older versions.
Existing log storage/quota and read-only sharing provider are preserved (32 MiB).
Export if the capture quota is reached; earlier captures are not deleted.

## Interface
Dark cyan/purple cards, status indicators, live progress and a lightweight Canvas wallpaper.
The animation responds to idle/scanning/found/error/deep-scan state, stops when paused,
and never blocks input. Animated mode is capped near 24 fps; static and 10 fps battery
saver modes are available under Advanced / Logs and persist locally.
No wallpaper service, accounts, ads, analytics, subscriptions or runtime cloud dependency.

## What results mean
DLNA/media-only is not physical TV control. HTTP 200, an open port, a certificate or
WebSocket upgrade never unlock a remote key. Text matches are unverified research clues.
Timeout/filtered/error does not prove a port closed or network control impossible.
This TCP experiment does not test every possible UDP protocol.
See RESEARCH-v0.4.md (also bundled into each diagnostic export) for evidence and limits.

## Build
JDK17, Gradle8.9, AGP8.7.3, compile/target35, min26.
GitHub Actions runs:
    gradle --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
Then verifies the APK signature/package and generates a SHA-256 checksum.
APK location: app/build/outputs/apk/debug/app-debug.apk
Local install: adb install -r app/build/outputs/apk/debug/app-debug.apk
If a previous debug signing identity differs, export logs before uninstalling the old app.
No physical-TV control is claimed by a successful build.
