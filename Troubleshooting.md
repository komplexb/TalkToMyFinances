# Troubleshooting Guide – Talk to My Finances

This guide covers common issues and resolutions when developing, building, and running **Talk to My Finances**.

---

## ⚡ Quick Reference: Standard / Lightweight Fixes First!

Always try lightweight fixes **before** considering nuclear cache deletions. Deleting `.gradle/caches` forces Gradle to re-download all project dependencies, SDK transforms, and libraries, taking significant time and bandwidth.

| Symptom | Lightweight Solution | Command |
|---|---|---|
| **App not appearing / updated on emulator** | Build, deploy & launch latest code | `npm run fresh-android` |
| **Stale preferences / Schema mismatch crashes** | Clear local app storage | `adb shell pm clear com.talktomyfinances.android` |
| **Stale Gradle build errors / lock files** | Stop active Gradle daemons | `./gradlew --stop` |
| **Gradle IDE out of sync** | Sync Gradle in Android Studio | `File > Sync Project with Gradle Files` |

---

## 📱 Emulator & App Launch Issues

### App Missing from the Emulator
If the app is not appearing on the emulator screen or launcher (for example, after wiping emulator data or restarting the virtual device):

1. **Deploy & Launch (Lightweight)**:
   ```sh
   npm run fresh-android   # or `make fresh-android`
   ```
   *This incrementally builds, installs the APK, and starts `MainActivity` instantly.*

### Emulator Connection Failure ("Emulator failed to connect within 5 minutes" / Freeze)
If the Android emulator fails to boot, freezes on launch, or gives a `failed to connect within 5 minutes` error:

1. **Clear Stuck Background Emulator Processes**:
   ```sh
   pkill -9 qemu-system
   pkill -9 emulator
   adb kill-server && adb start-server
   ```
2. **Wipe Data and Cold Boot the Virtual Device**:
   - In Android Studio, open **Device Manager** (right sidebar).
   - Click the **three dots (`⋮`)** next to your emulator $\rightarrow$ select **Wipe Data**.
   - Click **three dots (`⋮`)** $\rightarrow$ select **Cold Boot Now**.

---

## 🔐 Firebase & App Check Issues

### App Freezes / Network Errors After Pay Date Onboarding Step
If the app errors or hangs after submitting your pay date on the onboarding screen:
- This is caused by Firebase App Check blocking requests because your local emulator's unique debug token has not been registered in the Firebase Console yet.
- Look in **Logcat** (filter: `DebugAppCheckProvider`) for your secret UUID:
  ```text
  DebugAppCheckProvider: Enter this debug secret into the allow list
  in the Firebase Console for your project: XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX
  ```
- In [Firebase Console > App Check > Apps](https://console.firebase.google.com/project/_/appcheck/?useAutoProject=true), click `com.talktomyfinances.android` $\rightarrow$ **Manage debug tokens** $\rightarrow$ **Add debug token**, paste the UUID secret, and save.

---

## 🚨 Nuclear Option: Wiping Gradle Caches (`.gradle/caches`)

**Nuclear Option — Use Only As a Last Resort!**
Wiping `.gradle/caches` deletes all downloaded dependencies, transformed Android SDK libraries, and Kotlin compiler artifacts. It will force Gradle to re-download everything from the internet on your next build, which can take several minutes.

### When to Try Nuclear Reset:
- **ONLY** when you encounter persistent, unrecoverable transformed cache corruption (e.g. `FileNotFoundException` in `.gradle/caches/transforms/` or corrupted JAR transforms) **AND** all of the following lightweight steps have failed:
  1. Running `./gradlew --stop` to kill stale daemon memory references.
  2. Performing **File > Sync Project with Gradle Files** in Android Studio.
  3. Running **File > Invalidate Caches... > Invalidate and Restart**.

### How to Safely Execute a Nuclear Reset:
1. **Stop Stale Daemons**:
   ```sh
   ./gradlew --stop
   ```
2. **Remove Only Transformed Artifact Caches (Targeted Reset)**:
   *Rather than deleting all downloaded dependencies, delete only the transformed cache directory:*
   ```sh
   rm -rf ~/.gradle/caches/transforms-* ~/.gradle/caches/9.*/
   ```
3. **Re-sync and Rebuild**:
   In Android Studio, click **File > Sync Project with Gradle Files**, then run:
   ```sh
   ./gradlew assembleDebug
   ```
