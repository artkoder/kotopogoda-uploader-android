# Changelog

## [Unreleased]
### Added
- Health monitoring flow that normalises backend responses and surfaces latency, status and messages on the status screen.
- WorkManager-driven upload queue with foreground notification summary and retry/cancel controls in the queue UI.
- Centralised navigation host covering onboarding, viewer, queue, status, pairing and settings flows.
- File-based logging infrastructure with optional Timber planting plus pairing-specific log capture and export helpers.
- Jetpack Compose UI for photo review, queue/status dashboards and shortcuts to diagnostics from the viewer.
- **Native NCNN module** with Vulkan acceleration and CPU NEON fallback for image enhancement:
  - JNI integration with Zero-DCE++ and Restormer models
  - Tiled inference (384×384 tiles, 16px overlap for Restormer / 64px for Zero-DCE++, Hann window blending) for large images
  - SHA256 model verification with one-time process logging
  - Telemetry reporting (timings, memory usage, Vulkan availability, cancellation status)
  - Configurable quality profiles (BALANCED/QUALITY) and thread count (4-8)
  - Support for arm64-v8a and x86_64 architectures

### Changed
- Enabled application logging by default with a one-time migration that turns it on for existing installs while keeping the settings toggle available for opt-out.
- Removed the Wi-Fi only upload switch — the app now uses any available network when scheduling uploads.

### Testing
- Unit tests validating health status mapping from the API.
- Compose instrumentation coverage for the viewer screen happy path.
