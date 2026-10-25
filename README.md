# ChronoPlay

Small Android app: pick a local music file, choose a duration, press Play —
playback stops automatically when the timer runs out.

- Kotlin + Jetpack Compose (Material 3), minSdk 26, targetSdk 35
- Playback runs in a foreground service (`PlaybackService`) so it keeps going
  with the screen off; the notification shows a live countdown and a Stop action.
- Optional looping so a short track keeps playing until the timer ends.
- The last file, duration and loop setting are remembered.

## Build

Open the folder in Android Studio, or from a terminal with the Android SDK installed:

    ./gradlew assembleDebug
    ./gradlew installDebug
