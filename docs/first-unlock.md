# First-unlock startup boundary

Each APK contains its own `FirstUnlockGate` and `UnlockStartup`; it does not depend on a manager app or another module. Hook bootstrap/lifecycle capture remains separate from CE-dependent feature setup.

The gate checks `UserManager.isUserUnlocked`, coalesces duplicate initialization requests while locked, subscribes to `ACTION_USER_UNLOCKED` once and removes the receiver after delivery. `UnlockStartup` covers unlock during listener registration and duplicate delivery. Already-unlocked startup stays synchronous, so native compatibility setup retains its ordering. Normal keyguard re-lock does not change UserManager’s unlocked state or repeat setup.

Deferred callbacks run outside the Xposed protective boundary. Their initialization exceptions are logged without crashing the host process; unsupported paths retain native behavior. Existing per-feature failure handling, cached misses, crash protection and scope restrictions are preserved. There are no polls, alarms, jobs, services or cross-app startup coordinators.

Before this change, CE-dependent startup could fail before the module’s credential-encrypted provider/cache became available, leaving a host inactive until its next process start. Existing app boot receivers waiting for unlock did not necessarily protect the independently injected host.

Validation uses behavioral one-shot delivery tests (already unlocked, locked waiting, duplicate broadcasts, unlock during registration and synchronous signal delivery), existing project tests, release lint and signed-APK checks. Physical cold boot/credential changes are not performed as part of this update. App/host restart requirements and scope remain unchanged.
