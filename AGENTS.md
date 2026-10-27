# Development

- Follow the OneUI Mods development and release rules confirmed with the existing One UI mods task. Use SDK 37 and support Android 16/17.
- Do not hook, patch, repackage, or change Apple Music. The Vector scope is System UI only. Publish receivers through a system media route provider.
- The user explicitly authorized extending latency work to the Bridge-owned dedicated HAL path. Keep Vector scoped to SystemUI, preserve other routes, and retain the silence restriction. This permission does not authorize global capture-policy or SELinux changes.
- The user additionally authorized HAL modification, explicitly requiring prevention of unintended activation. Keep modifications restricted to authenticated Bridge-owned virtual routes, bounded values, verified builds and reversible changes; do not activate a global latency override.
- In selected-app mode, capture only the selected package's USAGE_MEDIA output. The user also requested a switchable whole-device mode for playback and notification sounds across applications; exclude microphone and call audio in that mode. Never change capture policies globally.
- Keep callbacks, network operations and audio work off the SystemUI main thread. Preserve existing routes on any hook error. Release policies, pipes and sessions on stop or client death.
- The user authorized physical-device operations for this task. Use GalaxyReload for SystemUI reloads; do not substitute a raw process kill or a device reboot.
- The user grants standing authorization for audio-service restarts when needed for this task. Keep the silence restriction and the GalaxyReload-only rule for SystemUI reloads.
- The user now requires command-only operation. Do not launch or operate GUI, use UI automation, or request GUI-based verification. Keep the GalaxyReload-only reload restriction; do not replace it with a raw kill or reboot.
- The application supports 0–100% volume and starts each connection muted. The 5% maximum applies only to volume values the assistant sets during physical-device operation. Do not impose a 5% application/build limit. Never set a device volume above 5% while debugging.
- The user explicitly requested 20% for listening in this session; that specific requested value is authorized. Do not use that request as permission to sweep higher values during debugging.
- The user is sleeping and has requested silence. Until the user explicitly lifts this restriction, keep receiver volume at 0%, do not start audible sources, and do not request listening tests. The earlier 20% listening authorization does not apply during this restriction.
- Java uses google-java-format. Keep user-facing strings in English and Japanese resources, and implementation details in docs/development.md.

# Distribution

- User-visible APKs must be signed release variants. Use tools/package_release.py and verify the signature and package. Never distribute debug or test-only APKs.
- Signing keys and passwords stay outside Git, logs, CI and source archives. CI builds and checks unsigned release APKs without distributing them.
- Manage the project in a public OneUI-Mods repository. Validate tests, lint, release build, licenses and APK structure before publishing.
- Releases include the signed APK, corresponding commit's source ZIP, SHA256SUMS, signature verification and release metadata. Never move existing tags.
- Release descriptions address users: behavior, supported environments, installation/update steps and known limitations. Exclude private device values, internal class names, hook counts and test history.
