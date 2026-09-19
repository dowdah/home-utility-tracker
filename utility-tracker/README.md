# Utility Tracker

Utility Tracker is the Kotlin Android client for Home Utility Tracker. It is the mobile application for recording household utility-meter readings and working with the synchronization backend.

## Requirements

- JDK 11
- Android SDK with the project compile SDK installed
- Android Studio or a compatible Gradle environment

## Build and test

From this directory, use the Gradle wrapper:

```sh
./gradlew test
./gradlew assembleDebug
```

Open this directory as an Android Studio project when working on the application.

## Contributing

Keep Android application changes within the app module, use the Gradle wrapper for local verification, and add relevant unit or instrumented tests with behavior changes. Do not commit device-specific configuration, credentials, or generated build outputs.
