# Release procedure

1. Increment versionCode and versionName, and update README with verified support and limitations.
2. Run tests, lint, release build, native protocol tests, dependency/license and APK checks.
3. Commit all source changes. The packaging script requires a clean checkout and matching source ref.
4. Once, generate a private per-app signing key using `python tools/package_release.py --initialize-key`. Subsequent packages use `python tools/package_release.py --source-ref HEAD`. The application supports 0–100% volume and all sessions start muted. The assistant must keep every volume value it sets during physical-device operation at or below 5%; this is an operating constraint, not an application or build restriction.
5. The script stores the key/password only in ignored `.signing/`, restricts filesystem permissions, runs release checks, aligns native libraries for 16 KB pages, signs and verifies the APK, checks the stable public certificate, and archives the same commit.
6. Test that signed release APK on the device. Use GalaxyReload to load the module. Never install an unsigned or debug APK on the user's device.
7. Push to the public OneUI-Mods repository and wait for the same commit's CI checks to pass.
8. Publish a new experimental tag with signed APK, source ZIP, signature verification, release-metadata.json and SHA256SUMS. Do not move tags or upload signing keys. Do not publish unsigned CI artifacts.

User-facing release text describes behavior, supported systems, installation/update actions and known limitations. Internal details and test records stay in development documentation.
