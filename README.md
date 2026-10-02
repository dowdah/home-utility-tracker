# Home Utility Tracker

Home Utility Tracker is a household utility-meter recording and synchronization project. It combines a FastAPI service for data synchronization with a Kotlin Android application for meter readings and day-to-day use.

## Components

- [Utility Sync backend](utility-sync/README.md) — the FastAPI service and its local operations.
- [Utility Tracker Android app](utility-tracker/README.md) — the Kotlin Android client project.

## Acceptance

- [V1.2 acceptance and operations handoff](RELEASE-1.2-ACCEPTANCE.md) — local checks and backend deployment completed; mailbox confirmation and production write acceptance remain pending.
- [V1.1 acceptance](RELEASE-ACCEPTANCE.md) — historical release evidence.

## Contributing

Choose the component you intend to work on and follow its README for prerequisites, local commands, and component-specific guidance. Keep configuration containing credentials, tokens, or production paths out of version control.
