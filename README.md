# SpectralCheck

Android app that detects **fake FLACs** — files that claim to be lossless but were
transcoded from a lossy source (MP3/AAC). Lossy encoders low-pass the audio at a
bitrate-dependent frequency; that brick-wall cutoff survives re-encoding to FLAC
and is visible in the spectrum. SpectralCheck finds it and renders a Spek-style
spectrogram.

## Features

- **Single file analysis** — pick a FLAC, get a verdict, a zoomable spectrogram
  with the detected cutoff marked, and stream info (sample rate, bit depth,
  channels, duration).
- **Folder scan** — recursively analyzes every FLAC in a folder (30 s sample per
  track), sorted worst-first with verdict badges.
- **Verdicts** — `Transcode` (sharp shelf < 17 kHz, typical MP3 ≤192k),
  `Suspicious` (sharp shelf 17–20.5 kHz, typical MP3 256–320k / AAC),
  `Likely authentic` (energy to Nyquist or gradual rolloff), `Inconclusive`
  (too quiet to judge).
- **Evidence list** — each verdict is backed by independent signals shown in
  the detail view:
  - *Frequency cutoff* — where the energy stops and how abruptly (30 dB drop
    width; a brick wall < 1.5 kHz wide is the lossy signature).
  - *Cutoff stability* — an encoder lowpass sits at the same frequency in
    every frame (IQR < 1 kHz); natural bandwidth follows the music.
  - *Joint-stereo artifact* — lossy encoders band-limit the side (L−R)
    channel below the mid; a genuine FLAC never does.
  - *Upsample detection* — a ≥88.2 kHz file whose content stops near 22 kHz
    was upsampled from CD/48k.
  - *Fake 24-bit* — samples of a 24-bit file all sitting on the 16-bit grid
    mean padded 16-bit content (needs a device codec that outputs float PCM).

- **Tags & cover art** — the detail view shows title/artist/album/year/genre/
  track and the embedded cover, parsed natively from the FLAC Vorbis-comment
  and PICTURE blocks (MediaMetadataRetriever as fallback).
- **PNG export** — "Export spectrogram image" on the detail screen writes a
  SoX-spectrogram-style annotated PNG (SoX palette, frequency/time axes, dBFS
  legend bar, cutoff marker, verdict and stream info) to a location you pick.

Everything runs on-device: MediaExtractor/MediaCodec decode, pure-Kotlin FFT/STFT
(Hann 4096 / hop 2048), no network, no NDK.

## Build

Requires JDK 17 and the Android SDK (platform 35). With `sdk.dir` set in
`local.properties`:

```sh
./gradlew assembleDebug          # APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # DSP + detector unit tests (JVM)
./gradlew assembleRelease        # signed release APK (needs keystore, below)
```

## Release signing

`assembleRelease` signs with the keystore referenced by `keystore.properties`
in the project root (gitignored):

```properties
storeFile=keystore/release.jks
storePassword=…
keyAlias=spectralcheck
keyPassword=…
```

**Back up `keystore/release.jks` and `keystore.properties` somewhere safe.**
Android only installs updates signed with the same key — if the keystore is
lost, users must uninstall/reinstall the app to update it.

## Real-audio pipeline test

`RealAudioPipelineTest` exercises the full STFT → cutoff → verdict chain on real
encoded audio. Generate fixtures with ffmpeg and point the env var at them:

```sh
D=/tmp/spectralcheck-audio; mkdir -p $D; cd $D
ffmpeg -f lavfi -i "anoisesrc=color=pink:duration=15:sample_rate=44100:amplitude=0.5" \
       -af volume=6dB -ac 2 -sample_fmt s16 real.flac
ffmpeg -i real.flac -b:a 128k fake128.mp3 && ffmpeg -i fake128.mp3 -sample_fmt s16 fake128.flac
ffmpeg -i real.flac -b:a 320k fake320.mp3 && ffmpeg -i fake320.mp3 -sample_fmt s16 fake320.flac
for f in real fake128 fake320; do ffmpeg -i $f.flac -ac 1 -f f32le -ar 44100 $f.pcm; done
# padded "24-bit" fixture — no -ac 1 here: ffmpeg's downmix scales by sqrt(2)
# and would knock the samples off the 16-bit grid
ffmpeg -i real.flac -sample_fmt s32 fake24.flac && ffmpeg -i fake24.flac -f f32le fake24.pcm
# tagged fixture with embedded cover art, for the metadata parser test
ffmpeg -f lavfi -i "color=c=orange:size=300x300:duration=0.1" -frames:v 1 cover.png
ffmpeg -i real.flac -i cover.png -map 0:a -map 1:v -c:a copy -c:v png -disposition:v attached_pic \
       -metadata title="Test Song" -metadata artist="Test Artist" -metadata album="Test Album" \
       -metadata date=2024 -metadata genre=Electronic -metadata track=7 tagged.flac

SPECTRALCHECK_AUDIO_DIR=$D ./gradlew testDebugUnitTest \
    --tests com.spectralcheck.analysis.RealAudioPipelineTest
```

Expected: `real → AUTHENTIC`, `fake320 → SUSPICIOUS (~20.1 kHz)`,
`fake128 → TRANSCODE (~16.7 kHz)`.

## License

[MIT](LICENSE)
