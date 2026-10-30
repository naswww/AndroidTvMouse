# TVMouse · TV Remote Mouse

[中文](README.md) · **English**

Control the on-screen mouse pointer with your TV's original IR remote — **no root, no accessibility permission**.

TVMouse uses the TV's built-in adb debugging to translate IR remote keys into mouse actions.

It was developed against a Hisense Vidda TV remote, to make Android tablet apps for video, reading, and stocks usable on a TV.

Built with AI-assisted development: the approach, technical path, and code were explored and written with WorkBuddy and DeepSeek.

![Main UI](docs/screenshots/01-main.png)

## Key Mapping

| Remote Key | Default Action |
| --- | --- |
| D-Pad | Move cursor (tap to nudge, hold to accelerate) |
| OK | Left click |
| Back | Right click |
| Live | Wheel up |
| Setup | Wheel down |
| Voice | Toggle mouse / remote mode |

All mappings can be rebound in the app.

![English UI](docs/screenshots/02-main-en.png)

## Install

1. Enable "USB debugging / ADB debugging" on the TV (Hisense: Settings → About → tap Build Number repeatedly to unlock Developer options).
2. Install `tvmouse-v2.1.6-release.apk`.
3. Open the app → tap **Start Mouse**. On first connection the TV shows an "Allow USB debugging" prompt; check "Always allow".
4. To stop: tap **Release Remote**, or press the toggle key to switch back to remote mode.

## Build from Source

Requires JDK 17 and the Android SDK (build-tools 34 / platform 34), Windows + Git Bash:

```bash
bash scripts/setup-toolchain.sh
bash scripts/build-release.sh 2.1.6 18 tvmouse-v2.1.6-release
```

The build script generates an adb key under `mousepad2/assets/` if missing (never committed), and runs a privacy check after building.

> `mousepad2/assets/tvmouse.bin` is the prebuilt daemon shipped with the package (v2.0.0, reliable two-way toggle); `native/tvmouse.c` is the reference source. Verify the toggle behavior before rebuilding it.

## Version

- Current: **v2.1.6**
- The release build uses a dedicated adb key; authorize it once on the TV at first install.

## Compatibility

- Tested on: Hisense VIDAA (model CN3V75).
- Other brands / models: the ADB address is configurable; remote key codes can be rebound with the app's learn mode.

## License

GNU GPL v3.0. See [LICENSE](LICENSE).
