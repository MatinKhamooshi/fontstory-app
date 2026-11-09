# Changelog

All notable changes to this project are documented here.
Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) · Versioning: [SemVer](https://semver.org/).

## [Unreleased]

## [1.2.0] - 2026-11-09

### Changed
- Updates now follow Google Play policy: the app only checks GitHub Releases and opens the signed APK link in the browser; Android's own installer installs it. The app no longer downloads or installs code itself (this was why Play Protect flagged 1.1.0).
- Target Android 16 (API level 36), as now required by Google Play.

### Added
- Two builds from the same code: **github** (sideloaded APK with update check) and **play** (Google Play bundle with no network access and no update code — Play delivers updates).
- Privacy policy (`PRIVACY.md`) for the Play Console listing.

### Removed
- `REQUEST_INSTALL_PACKAGES` permission and the in-app installer. The Play build has no permissions at all; the GitHub build only has `INTERNET` for the update check.

## [1.1.0] - 2026-11-09

### Added
- In-app updates: on launch the app checks GitHub Releases and offers «به‌روزرسانی / فعلاً نه». Updating downloads the APK from GitHub and installs it through the system installer. Manual check under ⋮ → «بررسی به‌روزرسانی».
- «درباره‌ی ما» in the ⋮ menu: version, developer (Matin Khamooshi) and that the app was built with Claude.

### Security
- Update traffic is HTTPS-only (cleartext disabled app-wide, system CAs only); only this repository's `FontStory-X.Y.Z.apk` release assets are accepted and every redirect is re-validated.
- Downloaded APKs must match GitHub's size and SHA-256, have this app's package name and the announced version, and be signed with the same certificate as the installed app before they are handed to the system installer.
- New permissions: `INTERNET` (update check/download only) and `REQUEST_INSTALL_PACKAGES`.

## [1.0.0] - 2026-11-09

### Added
- Bulk font import: multiple TTF/OTF/TTC files, ZIP archives, whole folders (recursive), and Share/Open-with from other apps.
- Automatic family + weight detection from the font's own `name`, `OS/2`, `head` and `fvar` tables; falls back to the style name when `usWeightClass` is wrong (common in Persian fonts).
- Variable-font support: one selectable weight per named instance (or 100–900 steps across the `wght` axis), marked "VF".
- Artboard editor (1080×1920 story, 1080×1350, 1080×1080) with multiple text layers: drag, centre-snapping guides, pinch-to-resize, duplicate, delete.
- Per-range styling: font family, weight, size and colour apply to the selected part of the text, or to the whole layer when nothing is selected.
- Right / centre / left alignment and line-spacing control; correct RTL shaping for Persian.
- PNG export at 1×/2×/3× with transparent background and smart crop to the exact ink bounds plus 1 px margin; saved to `Pictures/FontStory` with a share action.
- Auto-save of the current design.
- Defensive font parser (bounds-checked, size-limited), no permissions, no internet access, no third-party runtime libraries.

[Unreleased]: https://github.com/MatinKhamooshi/fontstory-app/compare/v1.2.0...HEAD
[1.2.0]: https://github.com/MatinKhamooshi/fontstory-app/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/MatinKhamooshi/fontstory-app/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/MatinKhamooshi/fontstory-app/releases/tag/v1.0.0
