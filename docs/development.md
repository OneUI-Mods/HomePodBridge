# Implementation

`HomePodRoutes` publishes `_raop._tcp` HomePod discoveries through `MediaRoute2ProviderService`. A route keeps a stable hashed device identity through name/address changes. Unsupported password-protected receivers and IPv6-only endpoints are omitted.

`VectorModule` runs only in `com.android.systemui`. It adds this provider's routes to the system media route manager's lists, preserving existing routes. It does not modify any music application, media files, Google Cast SDK or system_server. ROM-specific differences still require device testing.

`SystemRouteTransfer` intercepts only this provider's transfers inside SystemUI. The UID-checked control provider asks `HomePodRoutes` to create an unsolicited session with `REQUEST_ID_NONE`. Android notifies route managers without handing that foreign-provider session to the source application's router, which otherwise immediately releases it. SystemUI receives its transfer callback after the sender acknowledges initial mute. Switching back to a system output releases the bridge session and the capture policy. Other providers retain the original framework path. Pending SystemUI transfers have a bounded failure timeout.

`SystemAudioBridge` uses the existing SystemUI CAPTURE_AUDIO_OUTPUT and MODIFY_AUDIO_ROUTING permissions. Its audio policy matches USAGE_MEDIA and the selected package's UID, with LOOP_BACK without RENDER. It does not override capture restrictions or open a microphone. A private PCM pipe transfers stereo signed 16-bit little-endian 44.1 kHz audio to the app. The system Binder accepts only the UID and signing certificate of the installed module. The expected public certificate is read from the loaded module archive. The registration provider accepts only the actual SystemUI UID. A rate-limited registration broadcast lets the app reacquire that Binder after process death without restarting SystemUI. Client death and closed pipes release recording and audio policies.

The capture service runs as a visible mediaPlayback foreground service in system mode. Standard MediaProjection is an optional fallback restricted to Apple Music. Projection consent is never cached for reuse. Streaming and capture use a serialized background worker; generation checks discard stale connection results. The app does not persist PCM or receiver credentials.

The JNI adapter feeds a bounded SPSC ring in the pinned Apache-2.0 sender. It enables strict SRP/receiver proof checks, uses HomePod transient pairing and runs the sender/network loop on its own thread. Receiver volume commands are queued to that thread. Failed handshakes never report a created route session.

Every session starts at mute. The sender must receive the successful initial volume acknowledgement before reporting readiness or arming the audio pacer. The application always supports 0–100% in the UI, Java policy and native sender. The user's 5% constraint applies only to values the assistant sets while operating the physical device; it is not an application/build cap. The upstream sender resets a pre-start setVolume() and otherwise defaults to 0 dB, so configuring it before start was insufficient. Regression tests cover that failure, delayed or refused mute acknowledgement, bounded native volume policies and the application's full-range policy.

The app first tries Android's process-local Wi-Fi binding. When a forced VPN prevents that, the root helper owns a temporary route matching only this application's UID and the discovered receiver's private IPv4 address. It removes the exact rule on stop, helper termination or parent-process death. It does not change a VPN profile or other applications' routes. This fallback requires granting root access to HomePod Bridge.

The application uses Material Components 1.14.0 with a Material Design 3 day/night theme, dynamic colors, Material buttons/cards/dialogs and a Material volume slider.

HomePod transient authentication now derives the event-channel keys from the full SRP session key. The upstream path derived them only for pair-verify, silently discarded transient event requests and lost the receiver after roughly 30 seconds. The transient test now validates fragmented encrypted requests and the encrypted keep-alive reply.

## Verification

- Java tests exercise receiver identity changes, unsupported auth/endpoints, fragmented PCM reads, byte order and truncated frames.
- Upstream `raop_core_tests` exercises protocol handshakes, encryption, authentication failure, RTP/timing and retransmission against an in-process fake receiver.
- Upstream `raop_loop_tests` exercises real loopback TCP/UDP connections, refusal, disconnect and reconnect.
- APK checks inspect the non-debuggable manifest, ABI libraries, module entrypoint, notices and absence of bundled Xposed implementation classes.
- Physical-device playback and One UI's picker must be checked separately; passing a fake-receiver test is not proof of sound from a HomePod.
- On the tested Android 17 / One UI 9 device, the picker uses `MediaRouter2.ProxyMediaRouter2Impl`. Both the legacy manager and that proxy are supported inside SystemUI. A standard-picker selection now creates an unsolicited session, survives the earlier immediate source-router release and stays connected beyond the former transient-event timeout. Switching to the phone releases the session and removes the scoped network rule. Earlier device runs used a 5%-capped package; the user clarified that only the assistant's operations should be limited. Current packages support 0–100%, and assistant-operated checks use 0–5%. Listening feedback is distinct from protocol readiness.

## Device operations

The user initially prohibited physical-device operations through the existing One UI mods task, then explicitly authorized them in this task. The user specified GalaxyReload for SystemUI reloads because it preserves the unlocked state. Do not replace it with `kill`, `pkill`, or a reboot.

Development APKs are signed release variants. Device identifiers, addresses, screenshots, installed application data and private signing material belong in ignored local research files, not the repository or release text.
