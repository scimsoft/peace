# Peace — Android internet lock / focus app

Peace blocks **all internet access** on an Android phone for a user-set duration and strongly resists being turned off or uninstalled. It does this without firmware flashing by combining:

1. A **blackhole VpnService** that captures traffic and discards it.
2. **Device-owner** mode so the VPN can be set as **always-on with lockdown**, uninstall can be blocked, and user restrictions can disable VPN settings, factory reset, debugging, and safe boot while locked.
3. A **foreground timer + AlarmManager** that keeps the session alive until it expires (or until a delayed emergency unlock fires).

Package ID: `com.scimsoft.peace`  
minSdk: **26** (API 29+ recommended for the most reliable always-on VPN lockdown)  
targetSdk / compileSdk: **35**  
Language: **Kotlin** · Build: **Gradle (Kotlin DSL)**

---

## What it does

| Feature | Behavior |
|---|---|
| Blackhole VPN | `LockVpnService` establishes a TUN, routes `0.0.0.0/0` and `::/0` into it, and drains/discards packets. Nothing reaches the internet while active. |
| Always-on + lockdown | As device owner, calls `DevicePolicyManager.setAlwaysOnVpnPackage(admin, pkg, lockdownEnabled=true)`. |
| Timer | User picks hours/minutes. `LockTimerService` (foreground) + `AlarmManager` exact alarm fire at expiry and call unlock. |
| Allowlist | Optional single package name excluded from the VPN (`Builder.addDisallowedApplication`). Empty = block everything that uses IP networking. |
| Calls / SMS | **Not internet.** They use the telephony stack and keep working regardless of the VPN/allowlist. Noted in the UI. |
| Removal resistance | While locked: `setUninstallBlocked(true)` plus user restrictions `DISALLOW_CONFIG_VPN`, `DISALLOW_FACTORY_RESET`, `DISALLOW_DEBUGGING_FEATURES`, `DISALLOW_SAFE_BOOT`. Cleared on unlock. |
| Emergency unlock | In-app request starts a configurable cooldown (default **24 hours**). When it elapses, the lock lifts even if the focus timer has not finished — so you cannot permanently lock yourself out. |

---

## Honest limitation (read this)

**A recovery-mode / bootloader factory reset performed with hardware buttons will always wipe the app — and the whole phone.** That is a last-resort escape hatch, not an easy bypass. Peace cannot survive a full device wipe from recovery because that path sits below the Android userspace APIs this app uses. While the lock is active, the in-OS “factory reset” path is restricted (device owner), but recovery with volume/power keys is outside that control.

Other limits worth knowing:

- Device-owner provisioning requires a phone with **no accounts** added yet (see setup below).
- Without device-owner, the VPN still blackholes traffic after consent, but always-on lockdown, uninstall block, and user restrictions will not apply — the lock is much weaker.
- Some OEMs behave differently around VPN / battery optimizations; test on your device.
- This is a personal focus tool, not an MDM product for managing other people’s phones without consent.

---

## Project layout

```
peace/
├── README.md
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradlew / gradlew.bat
├── gradle/wrapper/
│   ├── gradle-wrapper.properties   (Gradle 8.9)
│   └── gradle-wrapper.jar
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/scimsoft/peace/
        │   ├── MainActivity.kt
        │   ├── LockVpnService.kt
        │   ├── LockDeviceAdminReceiver.kt
        │   ├── LockTimerService.kt
        │   ├── LockAlarmReceiver.kt
        │   ├── LockController.kt
        │   ├── DevicePolicyHelper.kt
        │   ├── EmergencyUnlock.kt
        │   └── LockPrefs.kt
        └── res/ …
```

---

## Build in Android Studio

1. Install **Android Studio** (recent stable) with Android SDK **35** and a JDK **17** toolchain.
2. **File → Open** the `peace/` directory (the folder that contains `settings.gradle.kts`).
3. Let Gradle sync. Wrapper uses **Gradle 8.9** and Android Gradle Plugin **8.7.2**.
4. Connect a physical device (or use an emulator for UI-only smoke tests — device-owner VPN lockdown is best validated on hardware).
5. **Build → Make Project**, then **Run** `app` (or `./gradlew :app:assembleDebug` from the project root).

If the wrapper jar is missing on a fresh clone, regenerate with a local Gradle install:

```bash
gradle wrapper --gradle-version 8.9
```

---

## Install and provision as device owner

Device-owner mode is what makes the lock strong. It can only be set when the device has **no accounts** (Google or otherwise). The usual workflow:

### 1. Factory-reset the phone

Settings → System → Reset, or recovery wipe. Complete the setup wizard **without** adding a Google account (skip sign-in). Enable **Developer options** → **USB debugging**.

### 2. Install the debug (or release) APK

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 3. Set device owner (exact command)

```bash
adb shell dpm set-device-owner com.scimsoft.peace/.LockDeviceAdminReceiver
```

Expected success message looks like: `Success: Device owner set to package ComponentInfo{com.scimsoft.peace/com.scimsoft.peace.LockDeviceAdminReceiver}`.

If you see an error about accounts present, remove every account and try again (or reset and skip sign-in).

### 4. Use the app

1. Open **Peace**.
2. Confirm the status line says **Device owner active.**
3. Pick a duration, optionally enter one allowlist package (or leave blank), set emergency cooldown hours (default 24).
4. Tap **Start lock**. Grant VPN permission when prompted (once).
5. Internet should drop for non-allowlisted apps. The notification shows time remaining.
6. When the timer ends (or the emergency cooldown completes after you request it), restrictions and the VPN are lifted automatically.

### Removing device owner later (when unlocked)

```bash
adb shell dpm remove-active-admin com.scimsoft.peace/.LockDeviceAdminReceiver
```

Or factory-reset from recovery. Do **not** expect to uninstall normally while a lock session has `setUninstallBlocked(true)`.

---

## Architecture notes

- **`LockController`** — single entry for lock / unlock / boot re-arm.
- **`DevicePolicyHelper`** — all `DevicePolicyManager` calls (always-on VPN, uninstall block, user restrictions).
- **`LockVpnService`** — TUN blackhole; excludes Peace itself and the optional allowlist package.
- **`LockTimerService`** — foreground countdown notification; also unlocks if AlarmManager was delayed.
- **`LockAlarmReceiver`** — `ACTION_LOCK_EXPIRED`, `ACTION_EMERGENCY_UNLOCK_DUE`, `BOOT_COMPLETED`.
- **`EmergencyUnlock`** — schedules the delayed safety-valve alarm.

---

## License / intent

Personal focus / self-control tool. Use only on devices you own. Device-owner is a powerful privilege — understand the recovery limitation above before you rely on a long lock.
