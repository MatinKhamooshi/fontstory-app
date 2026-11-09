# فونت استوری (FontStory)

اپ اندروید سبک و بدون تبلیغ برای نوشتن متن با فونت‌های دلخواه و گرفتن خروجی PNG شفاف برای استوری اینستاگرام.

A small, ad-free Android app: import your own fonts in bulk, compose text on an artboard, and export a transparent, tightly-cropped PNG for Instagram stories.

## Features

- **Bulk import** — pick many `.ttf/.otf/.ttc` files, a `.zip`, or a whole folder; or share fonts into the app.
- **Weight detection** — fonts are grouped by family; the second dropdown lists only the weights you've imported for that family. Variable fonts expose each weight (marked *VF*).
- **Editor** — multiple text layers, drag with centre snapping, pinch to resize, alignment, line spacing.
- **Partial styling** — select part of the text to change its font, weight, size or colour; with no selection the change applies to the whole layer.
- **Export** — PNG at 1×/2×/3× (base width 1080), transparent, smart-cropped to the exact text pixels + 1 px.
- **Private & safe** — no permissions, no internet, no third-party runtime libraries; fonts are validated and stored in app-private storage.

Requires Android 10 (API 29) or newer.

## Project layout

```
app/src/main/java/ir/matinkhamooshi/fontstory/
├── font/    FontParser (sfnt/TTC/fvar), FontImporter (files/ZIP/folders), FontLibrary (storage + typeface cache)
├── model/   TextLayer + Marks (per-range styles), Document/ProjectStore, Exporter (render + smart crop + MediaStore)
├── ui/      ArtboardView, ColorPicker, adapters, insets helpers
├── MainActivity.kt    editor
└── FontsActivity.kt   font library
```

## Build

Requirements: JDK 17+, Android SDK (platform 35, build-tools 35).

```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleDebug                 # debug build
./gradlew assembleRelease               # release build (signed only if keystore.properties exists)
```

### Release signing

Signing secrets are **not** in the repository. Create `keystore.properties` in the project root (git-ignored):

```properties
storeFile=keystore/release.jks
storePassword=…
keyAlias=fontstory
keyPassword=…
```

Always sign updates with the same keystore — Android refuses to update an app signed with a different key.

### Tests

```bash
mkdir -p testfonts && (cd testfonts && npm pack vazirmatn@33 && tar xzf vazirmatn-*.tgz)
./gradlew testReleaseUnitTest
```

Parser tests run on the JVM; render/export tests use Robolectric native graphics. Tests are skipped when `testfonts/` is missing.

## Versioning & releases

- Version lives in `gradle.properties` → `VERSION_NAME` (SemVer). `versionCode` is derived as `major*10000 + minor*100 + patch`.
- Every release: update `CHANGELOG.md`, bump `VERSION_NAME`, commit and push to `master`.
- CI (`.github/workflows/release.yml`) builds and tests every push. When `VERSION_NAME` has no tag yet, it signs the APK, pushes tag `vX.Y.Z` and publishes a GitHub Release with the APK and the changelog notes.
- CI signing secrets (Settings → Secrets and variables → Actions): `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Without them CI refuses to publish a release.
- Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, `refactor:`, `docs:`, `build:`, `test:`, `chore:`).
