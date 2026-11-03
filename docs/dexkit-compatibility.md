# Cached target discovery

Each application contains its own DexKit 2.3.0 dependency, target profiles, provider and private cache. It works independently: neither OneUI Mods Manager, another module nor a shared companion service is needed. The provider is part of this APK and only passes that module's results to its authenticated hook-host process. No network lookup or shared discovery database is used.

## Discovery lifetime

The first application/provider launch prepares installed targets on a background worker. Each module/target generation has one durable attempt. Generation keys include the profile schema and digest, module version, Android SDK and build fingerprint, target version and update time, and base/split APK paths, lengths and modification times. Package replacement events prepare new generations without polling. An unchanged launch reads the cache without hashing the APK, loading DexKit or opening a native bridge. Host callbacks only use an immutable in-memory reflection index. Feature-specific call-graph queries, where present, persist their own results with the same generation policy.

Failures, absent matches, native loading failure, corruption and interrupted searches are terminal for that generation. A claim is written before native exploration; file locks prevent duplicate concurrent attempts. Updating a module/target enables one new attempt. Clearing application storage explicitly resets the cache and settings. Never automatically delete a failed marker in a crash handler.

## Proof and compatibility boundaries

Android 16 and 17 profiles remain separate. APK hashes retain reviewed exact descriptors. On an unknown APK, resolution requires a unique semantic-string match, a meaningful unchanged class name with multiple matching method contracts, or a proven owner/type relationship. Methods also require parameter, return and static shapes; fields require types plus names or matched readers/writers. Ambiguous or missing bindings disable their feature. Aliases are scoped by APK and defining ClassLoader, not by an obfuscated name alone.

Unknown host versions wait asynchronously for preparation; profile preparation does not block the main thread. Existing attach-time call-graph contracts in Home Tweaks are evaluated once on their first component startup, then reused without native exploration. A failed proof retains the original application behavior. Existing model, SDK, firmware, resource and native-code guards remain. Exact application-version gates are lifted only when the complete recorded contract is proven. Discovery does not guarantee compatibility with arbitrary UI, resources, OS internals, native offsets, protocols or behavior changes.

## Rebuilding and verification

Run the repository's tests, lint, release build and license checks. Cache regression tests cover reuse, negative results, updates, concurrency, interruption, corruption and encoding. Offline tests additionally exercised real DexKit against renamed/ambiguous fixture APKs and the reviewed Android 16/17 APKs. Native arm64/x86_64 load segments use 16 KiB alignment. Tests do not establish future firmware behavior.

Profiles contain bounded selectors and signatures, not APKs, credentials, application databases, ROM images or user content. The external update-resilience test suite and local device evidence must remain separate from distribution. See [dependency licensing and replacement](dexkit-licensing.md).
