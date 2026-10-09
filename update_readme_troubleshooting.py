import re

with open('README.md', 'r') as f:
    content = f.read()

# Add pm clear to App Check troubleshooting
app_check_old = """4. (For emulators) Grab the `DebugAppCheckProvider` debug token printed in Logcat and whitelist it under **Firebase Console > App Check > Apps > Manage debug tokens**."""
app_check_new = """4. (For emulators) Grab the `DebugAppCheckProvider` debug token printed in Logcat and whitelist it under **Firebase Console > App Check > Apps > Manage debug tokens**.
5. If Play Integrity or App Check debug tokens still fail to generate on an emulator, clear the corrupted Google Play Services cache by running:
   ```sh
   adb shell pm clear com.google.android.gms
   ```"""

content = content.replace(app_check_old, app_check_new)

with open('README.md', 'w') as f:
    f.write(content)
