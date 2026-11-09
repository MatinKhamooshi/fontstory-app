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
5. Run `./gradlew assembleGithubRelease bundlePlayRelease testGithubReleaseUnitTest testPlayReleaseUnitTest` locally before pushing a release commit.
6. After pushing, tell the owner the version and that the Release will appear under Releases when CI finishes.

## Never
- Never commit `keystore/`, `keystore.properties`, `*.jks`, `local.properties`, APKs or build output.
- Stay compliant with Google Play policy: the app must never download/install APKs or executable code itself (no `REQUEST_INSTALL_PACKAGES`, no PackageInstaller). Updates = GitHub flavor shows a notice and opens the release APK URL in the browser; Play flavor has no update code.
- Keep `targetSdk` at the level Google Play currently requires (API 36 from Aug 31 2026; check yearly).
- Never add third-party runtime dependencies or new permissions without the owner asking. INTERNET exists only in the `github` flavor for `Updater` (HTTPS, this repo's releases) — no analytics, ads or other endpoints; the `play` flavor has no network access.
- Never weaken the HTTPS-only config or the host/URL allow-list in `Updater`.
- The repository is public: never commit secrets, personal data or credentials.
- Never change `applicationId` (`ir.matinkhamooshi.fontstory`) — updates would stop installing over the old app.

## Flavors
- `github` (default): sideloaded APK, published to GitHub Releases by CI.
- `play`: Google Play bundle, CI keeps it as an artifact; owner uploads it in Play Console.
- Flavor-specific code lives in `app/src/github/` and `app/src/play/` (same class names, e.g. `update/AppUpdates`).

## Build notes
- Maven Central may rate-limit (HTTP 429); `settings.gradle.kts` already lists Google's Central mirror first.
- The release key is held by the owner outside the repo. If it is not available in the session, build unsigned/debug only and say so — never generate a new release key for an existing release line.
