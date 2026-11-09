# Changelog

All notable changes to this project are documented here.
Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) · Versioning: [SemVer](https://semver.org/).

## [Unreleased]

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

[Unreleased]: https://github.com/MatinKhamooshi/fontstory-app/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/MatinKhamooshi/fontstory-app/releases/tag/v1.0.0
