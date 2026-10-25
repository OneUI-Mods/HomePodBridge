# Implementation

`HomePodRoutes` publishes `_raop._tcp` HomePod discoveries through `MediaRoute2ProviderService`. A route keeps a stable hashed device identity through name/address changes. Unsupported password-protected receivers and IPv6-only endpoints are omitted.

`VectorModule` runs only in `com.android.systemui`. It adds this provider's routes to the system media route manager's lists, preserving existing routes. It does not modify any music application, media files, Google Cast SDK or system_server. ROM-specific differences still require device testing.

`SystemAudioBridge` uses the existing SystemUI CAPTURE_AUDIO_OUTPUT and MODIFY_AUDIO_ROUTING permissions. Its audio policy matches USAGE_MEDIA and the selected package's UID, with LOOP_BACK without RENDER. It does not override capture restrictions or open a microphone. A private PCM pipe transfers stereo signed 16-bit little-endian 44.1 kHz audio to the app. The system Binder accepts only the UID and APK path of the installed module. The registration provider accepts only the actual SystemUI UID. Client death and closed pipes release recording and audio policies.

The capture service runs as a visible mediaPlayback foreground service in system mode. Standard MediaProjection is an optional fallback restricted to Apple Music. Projection consent is never cached for reuse. Streaming and capture use a serialized background worker; generation checks discard stale connection results. The app does not persist PCM or receiver credentials.

The JNI adapter feeds a bounded SPSC ring in the pinned Apache-2.0 sender. It enables strict SRP/receiver proof checks, uses HomePod transient pairing and runs the sender/network loop on its own thread. Receiver volume commands are queued to that thread. Failed handshakes never report a created route session.

## Verification

- Java tests exercise receiver identity changes, unsupported auth/endpoints, fragmented PCM reads, byte order and truncated frames.
- Upstream `raop_core_tests` exercises protocol handshakes, encryption, authentication failure, RTP/timing and retransmission against an in-process fake receiver.
- Upstream `raop_loop_tests` exercises real loopback TCP/UDP connections, refusal, disconnect and reconnect.
- APK checks inspect the non-debuggable manifest, ABI libraries, module entrypoint, notices and absence of bundled Xposed implementation classes.
- Physical-device playback and One UI's picker must be checked separately; passing a fake-receiver test is not proof of sound from a HomePod.

## Device operations

The user initially prohibited physical-device operations through the existing One UI mods task, then explicitly authorized them in this task. The user specified GalaxyReload for SystemUI reloads because it preserves the unlocked state. Do not replace it with `kill`, `pkill`, or a reboot.

Development APKs are signed release variants. Device identifiers, addresses, screenshots, installed application data and private signing material belong in ignored local research files, not the repository or release text.
