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

1. Bump `versionCode` (must be higher than every build riders have installed, or Android and
   the app's own check will refuse the update) and `versionName` in `app/build.gradle.kts`.
   `versionName` must be a semantic version (`2.0.0`, `2.1.0-rc.1`); the in-app updater
   ignores a release whose tag does not parse as one.
2. `./gradlew :app:testDebugUnitTest :app:assembleRelease`
3. Tag the commit with the same version (`git tag v2.0.0 && git push --tags`). The tag is what
   the updater compares with the installed `versionName`.
4. Create a GitHub Release for the tag (not a draft or pre-release: the updater asks for the
   latest full release) and attach exactly one signed `*.apk`. Names containing `debug` or
   `unsigned` are ignored, and the updater refuses a release where it cannot tell which APK to use.
5. Attach `<apk name>.sha256` for the APK, made in the folder holding it:
   `sha256sum app-release.apk > app-release.apk.sha256`. If it is missing the updater falls
   back to a line in the release notes that has the APK's name (or the word `sha256`) and a
   64-hex hash; with no checksum, or two different ones, the update is not offered.

### What the updater does and does not guarantee

It downloads only from `https` GitHub hosts, streams to disk, verifies the SHA-256, then checks
package name, a higher `versionCode` and the same signing certificate before opening the
system installer. The checksum lives beside the APK, so it detects corruption, not a
compromised release; the signing key is the real protection. Keep the key safe and never
rotate it casually: a rotated key cannot be updated in place.

## Debug builds

CI uploads the debug APK as a workflow artifact on every push. It is for testing only.
