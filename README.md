# Lectern for Android

A calm audiobook player for Android, and the phone version of [Lectern for Linux](https://github.com/dork40/lectern).

> **This project is vibe coded.** It was written almost entirely by an AI (Claude), directed in plain language by a person who wanted the app to exist. Nobody has reviewed the code line by line. It works for its author, but expect rough edges and read the code before you trust it with anything important.

## Features

- **Library:** pick your audiobooks folder. `Author/Book/` layouts, single-file books and multi-file books are all found, with covers and tags
- **Chapters** from m4b/m4a, MP3 and Ogg/FLAC files. Folder books get one chapter per file
- **Now playing:** chapter scrubber, time left, skip, chapter jumps, speed 0.5–3× with natural pitch, bookmarks with notes, and Undo after a jump
- **Works away from the screen:** notification and lock-screen controls that stay while paused. Bluetooth and headset buttons work, next/previous jump chapters, and Play can wake Lectern after it was closed
- **Sleep timer:** presets, hours/minutes/seconds, or end of chapter. Fades out, rewinds a little, and **shaking the phone resets it**
- **Audio:** volume boost through a compressor and limiter, voice clarity EQ, night mode, skip silence
- **Listening stats:** today, this week, streak, books finished, a 14-day chart
- **Casting** to Chromecast, Google TV and Nest devices, streamed from the phone. Beta: this has not been tested end to end on a real device yet
- **PC sync:** keeps places in step with Lectern for Linux over your home Wi-Fi, with a pairing code. No cloud
- **Smart AudioBook Player sync (optional):** imports your places, then keeps the two apps at the same spot. Needs notification access, used only to reach its playback controls

## Build

You need JDK 17 and the Android SDK (platform 35).

```sh
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`.

## Privacy

No accounts, analytics or servers. Your books, progress, bookmarks and stats stay on your phone, and only reach your own PC if you turn on PC sync. Notification access, if you grant it, is used only to reach Smart AudioBook Player's media controls; Lectern never reads your notifications.

## License

GPL-3.0, like Lectern for Linux. See [LICENSE](LICENSE).
