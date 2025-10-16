# Changelog

## [Unreleased]
### Added
- Health monitoring flow that normalises backend responses and surfaces latency, status and messages on the status screen.
- WorkManager-driven upload queue with foreground notification summary and retry/cancel controls in the queue UI.
- Centralised navigation host covering onboarding, viewer, queue, status, pairing and settings flows.
- File-based logging infrastructure with optional Timber planting plus pairing-specific log capture and export helpers.
- Jetpack Compose UI for photo review, queue/status dashboards and shortcuts to diagnostics from the viewer.

### Testing
- Unit tests validating health status mapping from the API.
- Compose instrumentation coverage for the viewer screen happy path.
