# Build

Prerequisites: Android Studio with JDK 17, Android SDK 35, Build Tools 35.x, and Gradle available to resolve the dependencies declared in `app/build.gradle.kts`.

Open `android/smartscore` in Android Studio and run `assembleDebug` or `assembleRelease` after configuring a release signing key outside the repository.

The repository intentionally does not contain a signing key, API key, service-role key, or database password.
