<div align="center">

# AdbStayAwake

**Keep the screen on while ADB debugging is connected.**

English · [简体中文](README.zh-CN.md)

<sub>An LSPosed module.</sub>

</div>

---

## Features

- The screen stays on while USB debugging is enabled.
- Your normal screen timeout returns as soon as USB debugging is turned off.
- No UI and no launcher icon — everything is managed from LSPosed.

## Install

1. A rooted device with **LSPosed** (API 102).
2. Download and install the APK from the [Releases](https://github.com/GeneralPeople1970/AdbStayAwake/releases) page.
3. In **LSPosed Manager → Modules**, enable the module and set its scope to **System Framework** only.
4. Reboot.

## Build

Requires JDK 17+ and the Android SDK (Platform 35).

```bash
./gradlew assembleRelease
```

## License

Released under the [MIT License](LICENSE).
