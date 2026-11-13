# RetroAmp

A music player for Android laid out like the desktop players of the late 1990s: a compact main window, a 10-band graphic equalizer and a playlist, all drawn at a fixed logical size and scaled to fit the screen.

RetroAmp is an independent project. It is not affiliated with, endorsed by, or derived from Winamp or Llama Group, and contains none of their code or artwork.

## Status

Early. The app builds and the package verifies, but it has had very little testing on real devices. Expect rough edges and please report what you find.

## Download

Signed APKs are on the [Releases](https://github.com/manishbyatroy/retroamp/releases) page. Android will ask you to allow installing from your browser or file manager.

## Features

- Plays local audio files picked through the system file picker (single files or a whole folder)
- 10-band equalizer (60 Hz – 16 kHz) with preamp and presets
- Spectrum analyzer and oscilloscope
- Volume, balance, seek, shuffle and repeat
- Playlist that is saved between launches
- Background playback with notification and headset controls
- Layout that adapts to phones, tablets and landscape: windows stack on tall screens and sit side by side on wide ones

No permissions are needed for storage; files are accessed only through the picker. The app has no network access.

## How it works

- `AudioEngine` decodes with `MediaExtractor` and `MediaCodec`, runs the PCM through the equalizer, volume and balance stage, and writes it to an `AudioTrack`.
- `Equalizer10` is a bank of peaking biquad filters.
- `PlayerService` is a foreground service that owns the engine, the playlist and the media session.
- `AmpView` is a single custom view that draws all three windows on a `Canvas` and handles touch.

The app is written in Kotlin and has no library dependencies beyond the Android framework.

## Building

Requirements: JDK 17 and the Android SDK (platform 34).

Point Gradle at your SDK by setting `ANDROID_HOME` or creating `local.properties`:

```
sdk.dir=/path/to/android/sdk
```

Then build a debug APK:

```
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Requires Android 7.0 (API 24) or later.

To build a signed release APK, create `keystore.properties` in the project root with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`, then run `./gradlew assembleRelease`. Without that file the release APK is left unsigned.

## Controls

- Tap the time display to switch between elapsed and remaining time.
- Tap the visualizer to cycle spectrum, oscilloscope and off.
- `EQ` and `PL` show or hide the equalizer and playlist windows.
- The eject button replaces the playlist; `+FILE` and `+DIR` add to it.
- Double-tap a playlist row to play it.

## License

MIT. See [LICENSE](LICENSE).
