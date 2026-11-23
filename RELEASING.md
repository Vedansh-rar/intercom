# Releasing

APKs are **not** stored in the repository. They are built, signed and published to
[GitHub Releases](../../releases); the repo only holds source.

## Signing

Release builds are signed with a key that never enters git. Provide it one of two ways:

1. `keystore.properties` in the repo root (gitignored):

   ```properties
   storeFile=/path/to/release.jks
   storePassword=...
   keyAlias=...
   keyPassword=...
   ```

2. Environment variables: `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`,
   `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`.

With neither, `assembleRelease` still builds but the APK is unsigned and Gradle prints a
warning. Keep a backup of the key: Android refuses to update an app signed with a
different one.

## Cutting a release

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. `./gradlew :app:testDebugUnitTest :app:assembleRelease`
3. Tag the commit (`git tag v1.2.3 && git push --tags`).
4. Create a GitHub Release for the tag and attach `app/build/outputs/apk/release/*.apk`.
5. Attach a `.sha256` file for each APK (`sha256sum app-release.apk > app-release.apk.sha256`)
   so riders and the in-app updater can verify the download.

## Debug builds

CI uploads the debug APK as a workflow artifact on every push. It is for testing only.
