# Utility Tracker UX Contract

| Operation | Trigger | Feedback and recovery |
| --- | --- | --- |
| Sync | Pull down on Home or Records | Stable refresh indicator; success/error message; Room remains the rendered source. |
| Add/edit reading or tariff | Picker-based form | Local entity and outbox write atomically; queued work syncs when connected. |
| Delete reading or tariff | Overflow menu, then confirmation | Unsynced entity is removed locally; synced entity becomes a tombstone mutation. |
| Resolve conflict | Conflict center | Keep server discards local draft; override creates a new operation using server revision. |
| Switch endpoint | Card/radio selection | Health and backend identity validate before activation. |
| Export CSV | Export page | System document picker selects destination; export errors remain visible in-app. |

The application must preserve draft form values, filters, date ranges, and current navigation across configuration changes. Token values are masked, Keystore-backed, never shown in messages, and controlled only from the endpoint-page overflow menu.
