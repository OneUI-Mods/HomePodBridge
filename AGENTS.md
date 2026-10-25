# Development

- Follow the OneUI Mods development and release rules confirmed with the existing One UI mods task. Use SDK 37 and support Android 16/17.
- Do not hook, patch, repackage, or change Apple Music. The Vector scope is System UI only. Publish receivers through a system media route provider.
- Capture only the selected package's USAGE_MEDIA output. Never capture microphones or change capture policies globally.
- Keep callbacks, network operations and audio work off the SystemUI main thread. Preserve existing routes on any hook error. Release policies, pipes and sessions on stop or client death.
- The user authorized physical-device operations for this task. Use GalaxyReload for SystemUI reloads; do not substitute a raw process kill or a device reboot.
- Java uses google-java-format. Keep user-facing strings in English and Japanese resources, and implementation details in docs/development.md.

# Distribution

- User-visible APKs must be signed release variants. Use tools/package_release.py and verify the signature and package. Never distribute debug or test-only APKs.
- Signing keys and passwords stay outside Git, logs, CI and source archives. CI builds and checks unsigned release APKs without distributing them.
- Manage the project in a public OneUI-Mods repository. Validate tests, lint, release build, licenses and APK structure before publishing.
- Releases include the signed APK, corresponding commit's source ZIP, SHA256SUMS, signature verification and release metadata. Never move existing tags.
- Release descriptions address users: behavior, supported environments, installation/update steps and known limitations. Exclude private device values, internal class names, hook counts and test history.
