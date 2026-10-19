# Historical v0.3 investigation — superseded by v0.4

The v0.3 /web and event experiments are complete and are NOT repeated in v0.4.
See [RESEARCH-v0.4.md](RESEARCH-v0.4.md) for definitive user-reported results and the current experiment.

# AwoX/MStar investigation — 0.3

## What is established

The user's physical-TV test, not protocol acknowledgements, establishes that the
previous RenderingControl path did not control the Sharp's actual volume/mute.
All previous control paths, including legacy Sharp TCP, remain unverified.

The user's device report supplies the MStarDMR/AwoX identity and exact vendor
endpoints/actions. These are inputs to safe GET inspection, not guessed APIs.

## Public source inspected

1. Cling's AwoX descriptor fixture includes /web, the three standard media
   services, and X_ServiceManager at the same MediaRenderer_* endpoint layout.
   It demonstrates this is an AwoX service family, not a Sharp remote key map.
   The fixture is filed as invalid XML; it is not used as a valid TV descriptor.
   https://github.com/4thline/cling/blob/master/core/src/test/resources/invalidxml/device/unrecoverable/awox.xml

2. Public HTC DLNA middleware wrappers include an AwoX ServiceManager control-point
   module and generic command wrappers for GetProperty, PerformAction and
   SetProperty, with string service/property/action/parameter values.
   The inspected wrappers forward to native JNI; they do not supply a validated
   LC-40UI7352E TV-key ID map. No decompiled implementation was copied into this app.
   https://github.com/Chairshot215/Harmonia_Evo_V_Decompiled/blob/a974a385cf318e241b507aeb261a44f9adc90618/Decompiled_APKs/HtcDLNAMiddleLayer/smali/com/awox/jUPnPCP/X_ServiceManagerControlPointModule.smali
   https://github.com/Chairshot215/Harmonia_Evo_V_Decompiled/blob/a974a385cf318e241b507aeb261a44f9adc90618/Decompiled_APKs/HtcDLNAMiddleLayer/smali/com/awox/jUPnPCP/CmdXServiceManagerPerformAction.smali
   https://github.com/Chairshot215/Harmonia_Evo_V_Decompiled/blob/a974a385cf318e241b507aeb261a44f9adc90618/Decompiled_APKs/HtcDLNAMiddleLayer/smali/com/awox/jUPnPCP/jUPnPCPJNI.smali

3. A Home Assistant device-discovery report identifies another Sharp as
   manufacturer MStar/model MStarDMR and shows its UPnP port changing after
   power cycling. It supports keeping discovery/current ports rather than
   assuming 54871 is permanent; it does not establish remote-key control.
   https://github.com/home-assistant/core/issues/32893

4. UPnP Device Architecture defines initial event notifications, SID/SEQ headers,
   temporary subscriptions and cancellation. Initial evented state may help reveal
   ServiceManagerInfo if this firmware actually exposes it.
   https://openconnectivity.org/upnp-specs/UPnP-arch-DeviceArchitecture-v1.1.pdf
   https://openconnectivity.org/developer/specifications/upnp-resources/upnp/

5. Sharp's model-family manual covers LC-40UI7352E and documents AQUOS NET+ and
   network media features. It does not supply a documented TV remote API here.
   https://s3.infra.brandquad.io/accounts-media/SHRP/DAM/origin/e473a4ac-f3ff-11ed-a2e3-c6d521dbb1ee.pdf

Searches for X_ServiceManager, ServiceManagerInfo, MStarDMR, AwoX GetProperty and
PerformAction did not yield a validated Sharp TV-key mapping in the inspected
material. This is a limited research result, not proof that no mapping exists.

## What the next phone capture must determine

- What /web actually serves on this firmware, including redirects/status/body.
- Which static same-origin JS/JSON/XML resources it references.
- Whether resources contain vendor service/action/property identifiers and an
  explicit semantic relationship to TV keys rather than DLNA renderer settings.
- Full SCPD arguments/state variables and any evented ServiceManagerInfo.
- Whether initial or subsequent events reveal identifiers when the physical
  remote changes TV settings.
- Whether a future, identified command changes TV-reported state AND the user
  confirms a matching physical effect.

The cloud session has no route to the user's 192.168.1.45. No local endpoint
response is fabricated or inferred from public AwoX examples. Version 0.3 sends
no vendor GetProperty values, PerformAction or SetProperty calls and all normal
TV buttons remain locked.
