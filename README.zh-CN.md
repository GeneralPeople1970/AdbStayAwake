<div align="center">

# ADB调试常亮

**ADB 调试连接时保持屏幕常亮。**

[English](README.md) · 简体中文

<sub>一个 LSPosed 模块。</sub>

</div>

---

## 功能

- 开启 USB 调试时,屏幕保持常亮。
- 断开 ADB 后,自动恢复原有的屏幕超时设置。
- 无界面、无桌面图标,仅在 LSPosed 中管理。

## 安装

1. 已 Root 且安装了 **LSPosed**(API 102)的设备。
2. 从 [Releases](https://github.com/GeneralPeople1970/AdbStayAwake/releases) 下载并安装 APK。
3. 在 **LSPosed 管理器 → 模块** 中启用本模块,作用域仅勾选「**系统框架 (system)**」。
4. 重启设备。

## 构建

需要 JDK 17+ 与 Android SDK(Platform 35)。

```bash
./gradlew assembleRelease
```

## 许可证

基于 [MIT 许可证](LICENSE) 发布。
