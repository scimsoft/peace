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
| Owner master unlock | Product owner can unlock early via **ECDSA P-256 challenge–response** (public key only on device) or a **PBKDF2 master password**. See below. |

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
├── owner-tool/                 # offline owner unlock CLI (Python)
│   ├── peace_owner.py
│   ├── requirements.txt
│   └── README.md
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradlew / gradlew.bat
├── gradle/wrapper/
└── app/src/main/java/com/scimsoft/peace/
    ├── MainActivity.kt
    ├── OwnerUnlockActivity.kt
    ├── OwnerUnlockConfig.kt      # paste public key + password hash HERE
    ├── OwnerUnlockCrypto.kt
    ├── OwnerChallenge.kt
    ├── LockVpnService.kt
    ├── LockDeviceAdminReceiver.kt
    ├── LockTimerService.kt
    ├── LockAlarmReceiver.kt
    ├── LockController.kt
    ├── DevicePolicyHelper.kt
    ├── EmergencyUnlock.kt
    └── LockPrefs.kt
```

---


## Owner master unlock

When a locked user calls the product owner, the owner can authorize an **early unlock** without waiting for the timer or the 24h emergency cooldown. Two mechanisms are supported; the user picks which UI to use on the **Owner unlock** screen.

### 1. Challenge–response (primary, most secure)

Uses **ECDSA P-256** (secp256r1). Only the owner's **public** key is embedded in the app (`OwnerUnlockConfig.OWNER_PUBLIC_KEY_X509_HEX`). The **private** key stays on the owner's computer under `owner-tool/keys/private.pem` (gitignored).

Flow:

1. Locked user opens **Owner unlock** → **Challenge–response**. The app shows a short Crockford-Base32 **challenge** (random nonce + timestamp), valid ~10 minutes and single-use.
2. User reads the challenge to the owner over the phone.
3. Owner runs the generator (see command below) and reads back the **response** code (Base32 of a 64-byte P1363 signature — longer; paste-friendly).
4. User enters the response; the app verifies the signature against the embedded public key and the stored challenge, consumes the nonce, and calls the normal unlock path (VPN off, restrictions cleared, timer cancelled).

**Owner setup (once):**

```bash
cd owner-tool
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
python3 peace_owner.py keygen
```

Paste the printed hex into `OwnerUnlockConfig.kt` → `OWNER_PUBLIC_KEY_X509_HEX`, then rebuild the app.

**Generate a response when a user calls:**

```bash
python3 owner-tool/peace_owner.py respond 'XXXX-XXXX-XXXX-…'
```

(Or omit the argument to be prompted.)

### 2. Master password (secondary fallback)

A password only the owner knows. The app stores **PBKDF2-HMAC-SHA256** (600 000 iterations) salt + hash — never the plaintext.

```bash
python3 owner-tool/peace_owner.py password-hash
```

Paste `MASTER_PASSWORD_SALT_HEX` and `MASTER_PASSWORD_HASH_HEX` into `OwnerUnlockConfig.kt`. On the phone, choose **Master password** and enter it to unlock immediately.

### Security trade-offs

| | Challenge–response | Master password |
|---|---|---|
| Secret on phone? | Public key only | Salt + hash (not plaintext) |
| After APK decompile | Cannot forge signatures without private key | Hash can be offline-brute-forced — use a long random password |
| Phone-call UX | Challenge short; response longer (paste helps) | Short to dictate |
| Recommendation | Prefer this | Emergency/simpler fallback |

Leaving either config field empty disables that method. Both empty → owner unlock is unavailable until configured.

More detail: [`owner-tool/README.md`](owner-tool/README.md).

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
- **`OwnerUnlock` / `OwnerUnlockActivity`** — challenge–response (ECDSA P-256) and master-password unlock for the product owner.
- **`OwnerUnlockConfig`** — paste site for owner public key and PBKDF2 salt/hash.

---

## License / intent

Personal focus / self-control tool. Use only on devices you own. Device-owner is a powerful privilege — understand the recovery limitation above before you rely on a long lock.
