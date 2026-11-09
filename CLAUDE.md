# Repository rules for Claude

This is FontStory, a native Kotlin Android app (no AndroidX/runtime libraries in `app`). Owner: Matin Khamooshi.

## Every change must be shipped like this
1. Work on the default branch `master` unless asked otherwise; keep commits focused.
2. Commit messages: Conventional Commits (`feat:`, `fix:`, `refactor:`, `perf:`, `docs:`, `build:`, `test:`, `chore:`), imperative, English.
3. Any user-visible change → add it under `## [Unreleased]` in `CHANGELOG.md`.
4. Releasing (whenever a new APK is delivered to the user):
   - Bump `VERSION_NAME` in `gradle.properties` (SemVer: fix → patch, feature → minor, breaking/data-format change → major).
   - Move `[Unreleased]` entries into a new `## [X.Y.Z] - YYYY-MM-DD` section and update the compare links at the bottom.
   - Commit `chore(release): vX.Y.Z` and push to `master`. Do NOT create the tag or Release yourself:
     `.github/workflows/release.yml` sees the untagged version, builds + tests, signs with the repo
     secrets, pushes annotated tag `vX.Y.Z` and publishes the GitHub Release with `FontStory-X.Y.Z.apk`
     and this version's CHANGELOG section as notes. (Claude sessions cannot create tags/releases directly.)
5. Run `./gradlew assembleRelease testReleaseUnitTest` locally before pushing a release commit.
6. After pushing, tell the owner the version and that the Release will appear under Releases when CI finishes.

## Never
- Never commit `keystore/`, `keystore.properties`, `*.jks`, `local.properties`, APKs or build output.
- Never add the INTERNET permission or third-party runtime dependencies without the owner asking.
- Never change `applicationId` (`ir.matinkhamooshi.fontstory`) — updates would stop installing over the old app.

## Build notes
- Maven Central may rate-limit (HTTP 429); `settings.gradle.kts` already lists Google's Central mirror first.
- The release key is held by the owner outside the repo. If it is not available in the session, build unsigned/debug only and say so — never generate a new release key for an existing release line.
