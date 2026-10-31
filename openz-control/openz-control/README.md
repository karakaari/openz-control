# OpenZ Control

An open-source Android app to control Nikon Z cameras over USB: live view monitor, exposure
settings, record start/stop, and a clip browser/downloader.

> **Status: experimental, untested on real hardware.** The protocol layer is covered by unit tests
> against a simulated camera, but it has never talked to an actual Z8. The Nikon-specific parts are
> written from the public PTP spec and public reverse-engineering notes (libgphoto2). Expect to
> debug on first contact; the Debug tab is built for exactly that.
>
> Unofficial. Not affiliated with or endorsed by Nikon, and not related to the ZineControl app.

## What it does

| Feature | How |
|---|---|
| Live view monitor | Nikon `StartLiveView` / `GetLiveViewImg` over PTP, JPEG frames decoded on the phone |
| Exposure controls | Mode, ISO, shutter, aperture, EV, white balance, from the options the camera reports |
| Record start/stop | Nikon `StartMovieRecInCard` / `EndMovieRec` |
| Clip gallery | Browses the card (all folders), thumbnails, chunked download to `Movies/OpenZControl` etc. |
| Debug tab | Dumps device info and every property the camera advertises, shareable as text |

## Layout

```
core/   Pure Kotlin/JVM. PTP codec, session, Nikon layer. No Android dependencies. Unit-tested.
app/    Android app: USB transport (bulk endpoints), ViewModel, Jetpack Compose UI.
```

## Build and run

1. Open this folder in Android Studio (it supplies Gradle and the Android SDK), or run
   `gradle wrapper` once and then `./gradlew :app:assembleDebug`.
2. Run the core tests: `./gradlew :core:test`
3. Install on an Android 10+ phone with USB host support.
4. On the camera: **Setup menu > USB connection > MTP/PTP**.
5. Plug the camera into the phone with a USB-C **data** cable, switch the camera on, and tap
   **Connect camera**. For video, set the camera's photo/video switch to video first.

Plugin and library versions in the Gradle files (AGP 8.7.3, Kotlin 2.1.0, Compose BOM 2024.12.01)
are conservative; bump them if Android Studio suggests it.

## What is verified and what is not

Verified by tests (`core/src/test`): PTP container and string encoding, session handling
(including "session already open"), reassembly of responses split across reads, device-info and
property-descriptor parsing, setting properties, live-view JPEG extraction, folder walking, chunked
download, value formatting.

**Not verified, most likely to need fixing on a real Z8:**

- Nikon vendor codes: live-view selector (`0xD1A6`) and status (`0xD1A2`), movie start/stop
  (`0x920A`/`0x920B`), 64-bit partial read (`0x95C1`) and object size (`0x9421`). All are in
  `PtpConstants.kt` marked "believed".
- **Video-mode exposure settings.** Stills use the standard PTP properties
  (`0x5007`, `0x500D`, `0x500F`, ...). Newer bodies may expose separate movie-mode properties. Run
  **Debug > Run diagnostics**, look for extra properties around the values you see on the camera,
  and put the real codes into a `PropertyMap`.
- Live-view payload header size. The app scans for the JPEG start/end markers instead of assuming
  one, so it should tolerate this, but frame rate is untested.
- Android compile: the app module was not built in the environment it was written in (no Android
  SDK, Maven blocked). It passes a parse-level check only. Expect a small number of compile fixes.

## Known limitations

- USB only. Wi-Fi (PTP/IP) is not implemented; pairing on Z-series is poorly documented.
- Bulk transfers are capped at 16 KiB per call for compatibility, so large downloads are slower
  than they could be. Queued `UsbRequest` transfers would fix this.
- No focus control, AF-area selection, histogram, or LUT/false-colour tools yet.
- Live view pauses while you use the Gallery or Debug tab.

## Contributing

Real-hardware reports are the most valuable contribution: attach the Debug tab output and the
camera model and firmware. Pull requests that add a verified `PropertyMap` per camera model are very
welcome.

## License

MIT. See `LICENSE`.
