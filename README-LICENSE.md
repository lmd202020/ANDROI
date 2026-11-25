# MINHDUC ANDROID ELITE 4.0 — GitHub KEY integration

## Files changed
- `app/src/main/java/com/minhduc/elite/LicenseManager.kt` — GitHub Contents API license verification and first-device binding.
- `app/src/main/java/com/minhduc/elite/MainActivity.kt` — KEY activation gate, 60-second re-check, logout.
- `app/src/main/AndroidManifest.xml` — Internet permission.

## Configure a NEW GitHub token
Open `app/src/main/java/com/minhduc/elite/LicenseManager.kt` and replace:

`PASTE_NEW_GITHUB_TOKEN_HERE`

with a newly created GitHub token. It needs access to repository `lmd140809/MinhDux`, with Contents read and write permission (fine-grained token). Do not reuse any token previously pasted into chat or committed to source control; revoke exposed tokens.

**Security warning:** A token embedded in an APK can be extracted by anyone who obtains the APK. Android Keystore does not solve this because the token must still be present in the app to call GitHub. Use a dedicated token with the narrowest possible repository scope and rotate it if exposed. The safer production design is a server-side Worker/proxy.

## Behavior
- Uses `keys.json` on branch `main` in `lmd140809/MinhDux`.
- Activates an unbound key by writing the Android `ANDROID_ID` to `deviceID`.
- Rejects missing, expired, or differently-bound keys.
- Checks a saved key every 60 seconds.
- Allows up to 3 days of offline grace after the last successful check.
- Deleting a key from `keys.json` revokes it.

## Important
- The JSON schema should contain a top-level `keys` array. Each key object uses `key`, `deviceID`, `expiresAt`, and optionally `note`. `expiresAt` is expected as `yyyy-MM-dd`.
- The app stores the activated license key locally in private SharedPreferences. This project does not modify Free Fire or another app.
- ZIP was inspected and edited, but a real Android build has not been run in this environment. Run the repository's GitHub Actions workflow to confirm the build.
