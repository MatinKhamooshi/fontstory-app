# Repository rules for Claude

This is FontStory, a native Kotlin Android app (no AndroidX/runtime libraries in `app`). Owner: Matin Khamooshi.

## Every change must be shipped like this
1. Work on the default branch `master` unless asked otherwise; keep commits focused.
2. Commit messages: Conventional Commits (`feat:`, `fix:`, `refactor:`, `perf:`, `docs:`, `build:`, `test:`, `chore:`), imperative, English.
3. Any user-visible change → add it under `## [Unreleased]` in `CHANGELOG.md`.
4. Releasing (whenever a new APK is delivered to the user):
   - Bump `VERSION_NAME` in `gradle.properties` (SemVer: fix → patch, feature → minor, breaking/data-format change → major).
   - Move `[Unreleased]` entries into a new `## [X.Y.Z] - YYYY-MM-DD` section and update the compare links at the bottom.
   - Commit `chore(release): vX.Y.Z`, create annotated tag `vX.Y.Z`, push commit and tag.
   - Create a GitHub Release for the tag with the changelog section as notes and attach `FontStory-X.Y.Z.apk` (signed release build).
5. Run `./gradlew assembleRelease testReleaseUnitTest` before releasing.

## Never
- Never commit `keystore/`, `keystore.properties`, `*.jks`, `local.properties`, APKs or build output.
- Never add the INTERNET permission or third-party runtime dependencies without the owner asking.
- Never change `applicationId` (`ir.matinkhamooshi.fontstory`) — updates would stop installing over the old app.

## Build notes
- Maven Central may rate-limit (HTTP 429); `settings.gradle.kts` already lists Google's Central mirror first.
- The release key is held by the owner outside the repo. If it is not available in the session, build unsigned/debug only and say so — never generate a new release key for an existing release line.
