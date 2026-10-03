# Signing Focus Vault

To build the release APK for Focus Vault, you need the release keystore.
Set the following properties in `local.properties` (which is ignored by Git) or as environment variables:

```properties
RELEASE_STORE_FILE=/path/to/release.keystore
RELEASE_STORE_PASSWORD=<password>
RELEASE_KEY_ALIAS=release
RELEASE_KEY_PASSWORD=<password>
```

Then run `./gradlew assembleRelease`
