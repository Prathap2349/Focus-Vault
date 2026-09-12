# Release Signing

To ensure consistent APK signatures across different build machines and prevent "App not installed" errors, this project uses a dedicated release keystore (`app/release.keystore`).

## Keystore Details
- **File:** `app/release.keystore`
- **Alias:** `release`
- **Store Password:** `android`
- **Key Password:** `android`

## Generating or Rotating the Keystore
If you ever need to generate a new release keystore, navigate to the `app/` directory and run:

```bash
keytool -genkey -v -keystore release.keystore -alias release -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android -dname "CN=StayFocused, OU=App, O=StayFocused, L=Unknown, ST=Unknown, C=US"
```

**Note:** If you rotate the keystore, existing users will not be able to install the update without first uninstalling the old version (due to signature mismatch). Only do this if the keystore is compromised or lost.

## Other Build Inconsistencies
The `versionCode` in `app/build.gradle` is currently hardcoded to `1`. If different developers manually bump this version number inconsistently across branches or local machines (e.g., Developer A builds `versionCode 2` and Developer B builds `versionCode 1`), users attempting to install the lower version over the higher version will get an "App not installed" error due to Android's downgrade protection. Always ensure the `versionCode` is monotonically increased and synced via version control before distributing APKs.
