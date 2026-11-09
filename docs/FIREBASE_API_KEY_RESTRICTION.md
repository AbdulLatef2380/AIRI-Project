# Restricting the Firebase API key (part 4, B8)

`app/google-services.json` is committed and holds a Firebase Android API key (it starts with `AIzaSy`).
This is normal for Android: the key is a public client identifier, not a secret. It still must be
restricted in the Google Cloud console, otherwise anyone who copies it can call the enabled APIs
against your quota. **This is a console setting; it cannot be changed from this repository.**

Values to use (taken from the committed config):

| Setting | Value |
|---|---|
| Project | `fir-96852` (project number `93237507759`) |
| Application restriction | Android apps |
| Package name | `com.airi.assistant` |
| SHA-1 fingerprint | `6c6efe1241eafe28748e036ae1472dc93d7d911d` (the one registered for the OAuth Android client) |

Checklist:

1. Google Cloud console > APIs & Services > Credentials > open the Android API key.
2. Application restrictions: **Android apps**. Add package `com.airi.assistant` with the SHA-1 above.
   Add the SHA-1 of every other signing key you ship with (release, Play App Signing, CI debug).
   `scripts/verify_google_oauth_config.py --expected-sha1 <apk sha1>` (already run in CI) checks the APK key
   against the config.
3. API restrictions: **Restrict key** and allow only the APIs the app really uses (for example Firebase
   Installations, Identity Toolkit, Firestore, FCM). Remove the rest.
4. Confirm `firestore.rules` denies unauthenticated access, because the rules, not the key, protect the data.
5. If the key was ever pasted anywhere public, rotate it and download a fresh `google-services.json`.

Known limit: a restricted key still works from any build signed with an allowed SHA-1, so keep the
signing keystores private.
