# BJJ TV

An unofficial Android TV app for watching the BJJ Fanatics instructionals you already
own, on a Chromecast with Google TV or another Android TV device, using the remote.

> **Unofficial.** This project is not affiliated with, endorsed by, or supported by
> BJJ Fanatics. It needs your own BJJ Fanatics account and only shows courses that
> account has purchased. It is for personal viewing: it does not download, copy, or
> share videos. Check the BJJ Fanatics terms of service before using it; you use it at
> your own risk, and it may stop working whenever their site changes.

## Features

- Your library as a grid of covers, with search, an instructor filter, and sorting
- Volumes and chapters for each course, with watched and in-progress marks
- Plays a course from the chapter you pick through to the end, and resumes where you left off
- Sign in from your phone by scanning a QR code, or with the remote

Watched marks and resume positions are kept on the TV device only. They do not sync
with the BJJ Fanatics website.

## Install

There is no Play Store listing; the app is installed by sideloading.

1. On the TV device: **Settings > System > About**, then click **Android TV OS build**
   seven times to unlock developer options.
2. **Settings > System > Developer options**: turn on **USB debugging** (and
   **Wireless debugging** if shown).
3. Build and install from source (next section), or install a release APK with
   `adb install bjjtv.apk`.

## Build from source

1. Install [Android Studio](https://developer.android.com/studio) and open this folder.
   Accept the prompts to sync Gradle and download the SDK.
2. Connect to the TV device: **Device Manager > Pair Devices Using Wi-Fi**, or
   `adb connect <device-ip>:5555`. Approve the prompt on the TV.
3. Select the device and press **Run**.

Or from a terminal, with the Android SDK installed and `ANDROID_HOME` set:

```
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Signing in

The first launch offers two ways to sign in:

- **Phone:** scan the QR code on the TV with a phone on the same Wi-Fi. The TV serves a
  small sign-in page to the phone over the local network, then types what you enter
  into the BJJ Fanatics sign-in page on the TV.
- **Remote:** use the BJJ Fanatics sign-in page directly on the TV.

## Privacy

- The app talks only to BJJ Fanatics and its video host. It has no analytics, ads, or
  servers of its own.
- Your password is never stored. After sign-in the app keeps the site's session token in
  its private storage on the device; **Sign out** removes it.
- With phone sign-in, your email and password travel unencrypted over your local Wi-Fi
  from the phone to the TV. Use it only on a network you trust, or sign in with the remote.

## Limitations

- The app relies on how the BJJ Fanatics website works today and can break without notice.
- It has been tested on one account and one Chromecast with Google TV.
- Bundles that spread videos across several hosts are handled on a best-effort basis.

## License

MIT. See [LICENSE](LICENSE). The license covers this app's code only; BJJ Fanatics
content, names, and artwork belong to their owners.
