# DexKit dependency and replacement

DexKit 2.3.0 is used without upstream modifications. Its native core is dynamically loaded as `libdexkit.so`; the wrapper is compiled from the published AAR. The application sources, dependency version and build procedure are public. To replace or modify DexKit, substitute the dependency in `app/build.gradle` (or build the AAR from the exact upstream tag), then build the application using [development instructions](development.md). A personal build can use its own signing key; the distributed application does not verify the DexKit binary against a vendor key. Replacing the installed APK may require exporting settings and uninstalling the previous signer.

- [Upstream source and complete build scripts, tag 2.3.0](https://github.com/LuckyPray/DexKit/tree/2.3.0)
- [Apache wrapper/root license](https://github.com/LuckyPray/DexKit/blob/2.3.0/LICENSE)
- [LGPL core license](https://github.com/LuckyPray/DexKit/blob/2.3.0/Core/LICENSE)

Both notices, the GNU GPL v3 referenced by LGPL v3, FlatBuffers notices and libc++ notices are bundled in the app license UI. This application does not restrict reverse engineering for debugging modifications of the library. No Samsung APKs or user data are bundled.
