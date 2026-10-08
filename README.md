# Home Utility Tracker

Home Utility Tracker is a household utility-meter recording and synchronization project. It combines a FastAPI service for data synchronization with a Kotlin Android application for meter readings and day-to-day use.

## Components

- [Utility Sync backend](utility-sync/README.md) — the FastAPI service and its local operations.
- [Utility Tracker Android app](utility-tracker/README.md) — the Kotlin Android client project.

## Acceptance

- [V1.3.2 acceptance](RELEASE-1.3.2-ACCEPTANCE.md) — immediate saved-reading visibility and adaptive water/electricity icons, two AVDs and isolated Xiaomi 13 acceptance.

- [V1.3.1 acceptance](RELEASE-1.3.1-ACCEPTANCE.md) — statistics ranges/order and predictive-back layout, two-AVD regression and isolated Xiaomi 13 acceptance.
- [V1.3 acceptance](RELEASE-1.3-ACCEPTANCE.md) — balance forecasts, daily local reminders, two-AVD upgrade/backup/offline notification checks and production notification recovery fix.

- [V1.2 acceptance and operations handoff](RELEASE-1.2-ACCEPTANCE.md) — local and production checks passed, including user-confirmed SMTP delivery and two-AVD periodic synchronization.
- [V1.1 acceptance](RELEASE-ACCEPTANCE.md) — historical release evidence.

## Contributing

Choose the component you intend to work on and follow its README for prerequisites, local commands, and component-specific guidance. Keep configuration containing credentials, tokens, or production paths out of version control.
