# SharpLanDiagnostic v0.4 research evidence

## Definitive user-reported physical test (v0.3)
Target: Sharp LC-40UI7352E, 192.168.1.45.
UPnP: Sharp_UHD_TV (78:dd:d9:48:53:2e), MStar / MStarDMR, AwoX/1.1.
TCP 54871: DLNA/media renderer, not verified physical TV control.
RenderingControl altered internal renderer state only; physical volume/mute did not change.
X_ServiceManager: subscription succeeded; one empty ServiceManagerInfo initial event.
Physical remote button presses produced no subsequent event in 30 seconds.
/web: Media Renderer Administration; friendly-name editing and service restart only.
No remote-control JavaScript or TV key map was found in that test.
Prior TCP 80, 443 and 10002: unavailable at test time, not evidence of a control API.

These are the user's reported results. The build environment did not run those TV tests
or inspect an uploaded v0.3 diagnostic ZIP. They are accepted as definitive for that test.
The event experiment and /web administration crawl are disabled in the v0.4 UI/automatic flow.
Port 54871 is connect-tested but excluded from active fingerprinting.
An AwoX renderer discovered at another advertised port is also excluded from active probes.

## Public sources revisited for v0.4
1. Sharp's official model-family manual explicitly includes LC-40UI7352E.
It documents AQUOS NET+ and network media, but no usable TV-key LAN API was
identified in the inspected manual. This is not proof that an undocumented API cannot exist.
https://s3.infra.brandquad.io/accounts-media/SHRP/DAM/origin/e473a4ac-f3ff-11ed-a2e3-c6d521dbb1ee.pdf

2. Original Home Assistant issue #32893 includes Sharp MStarDMR discovery logs.
Those logs show the renderer's advertised port changing after power cycling.
This supports using the current SSDP descriptor, rather than assuming a permanent port.
It does not show physical TV control. The descriptor path /aw is evidenced in these logs.
https://github.com/home-assistant/core/issues/32893

3. Cling's original AwoX test fixture documents the media-renderer service family,
including /web and X_ServiceManager. It supplies no LC-40UI7352E physical remote key map.
The fixture lives under invalidxml and is not treated as a valid production descriptor.
https://github.com/4thline/cling/blob/master/core/src/test/resources/invalidxml/device/unrecoverable/awox.xml

4. Previous research found generic AwoX ServiceManager wrappers forwarding to native JNI,
not a validated physical-TV command map. No unknown IDs are invoked.
https://github.com/Chairshot215/Harmonia_Evo_V_Decompiled/blob/a974a385cf318e241b507aeb261a44f9adc90618/Decompiled_APKs/HtcDLNAMiddleLayer/smali/com/awox/jUPnPCP/X_ServiceManagerControlPointModule.smali

Search scope: exact LC-40UI7352E, Sharp/UMC MStar platform, MStar network/smart TV
remote protocols, MStarDMR, AQUOS NET+, older European Sharp APIs and public code/logs.
No documented, validated physical-TV network control protocol for this exact platform
was identified in the inspected sources. Broad patent descriptions, Android/Roku/modern
Sharp implementations and universal-remote marketing claims are not compatibility proof.

## v0.4 experiment
An explicit user-confirmed scan targets ONLY numeric 192.168.1.45.
TCP connect-only range: 1-65535, 16 workers, maximum 80 new connect attempts/sec,
300 ms connection timeout. No UDP sweep, subnet sweep, passwords or key commands.
The current Sharp identity/UDN is checked again immediately before scanning when available.
If UPnP is unavailable, the user must explicitly confirm the fixed TV IP manually and
confirm the full scan again in its dialog. A conflicting advertised identity blocks the scan.
All sockets bind to the original Wi-Fi/Ethernet Network and abort on loss of the on-link LAN.
Each port is attempted once; rejected, timed-out, error and not-tested states remain distinct.
Timeout/filtered/error results are inconclusive, not definitive closed-port claims.
Cancellation preserves a complete CSV with untested ports marked, plus open-port records.

Fingerprinting occurs after the connect scan, sequentially with a pause between ports.
A passive read comes first. Non-HTTP greetings and known non-web ports receive no HTTP.
Other silent services may receive a bounded HTTP GET / and TLS/HTTPS handshake probe.
No redirects, JavaScript, forms, authentication, pairing or application commands run.
TLS certificate metadata is captured before Android's normal trust validation; untrusted
certificates do not silently pass. A self-signed certificate may therefore be recorded
without a completed TLS handshake or HTTPS root response.
WebSocket upgrade is attempted only when the root response explicitly supplies a same-IP,
same-port, same-scheme WS URL with no query or action-like path. No WebSocket frames follow.
Service descriptions and text can suggest an UNVERIFIED candidate, never enable a key.

Limits: 64 KiB HTTP response, 4 KiB passive banner, bounded socket read timeouts and
per-response deadlines; captured truncation is flagged. All open ports are retained.
The main dashboard shows the first 32 prioritised findings; the export retains every result.
A full TCP scan cannot rule out UDP-only services, firmware settings, standby-only
behaviour or a service that drops probes. An open port cannot prove TV control.

## Verification rule
No physical control has succeeded. Every normal remote key remains locked.
A future test needs a semantically identified command, matching TV state where available,
and the user's confirmation that the physical TV changed.
The APK build/tests/signature checks cannot substitute for that physical test.
