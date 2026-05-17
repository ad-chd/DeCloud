# Changelog

All notable changes to DeCloud are documented here.
This project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Added
- Multi-select storage filter in global search — pick any combination of Internal Storage, SD Card, etc.
- Brand tagline ("Transfer. Protect. Repeat.") shown on home screen and splash, with neon-green "Protect."
- About sheet — tap the "DeCloud" title to see developer info and copy the support email.
- Search icon on the home screen — launches global search across all storage at once.
- Byte-based transfer progress (replaces file-count-based percentage).

### Changed
- Removed splash transparency flash on the desktop app.
- Logo in the Electron header now matches the title-bar logo (one consistent brand mark).
- Storage filter in search defaults to "All storages" instead of "Current folder".

### Removed
- SMS / Call-log backup categories (Google Play restricted permissions; will be added back via separate declaration in a later release).

---

## [1.0.0] — *unreleased*

Initial public release.

### Features
- Phone → PC file and folder transfer over WiFi (hotspot or shared) and USB / ADB
- PC → Phone file and folder send
- Global file search with type / size / date / depth filters
- Quick categories: Images, Videos, Audio, Documents, Downloads, Apps
- Contacts export to VCF
- Real-time byte-accurate transfer progress
- Dark / Light themes with circular-reveal transition
- 100% local — no internet, no servers, no analytics, no ads
