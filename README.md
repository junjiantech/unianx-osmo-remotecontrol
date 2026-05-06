# Unianx Osmo Remote Control

这是一个基于 Android + Kotlin + Jetpack Compose 的 Osmo 相机遥控项目，主要通过 BLE 与设备通信，提供基础远程控制与 GPS 同步能力。

## 项目简介

当前版本聚焦于手机侧控制台能力，主要包括：

- 扫描附近可用的 DJI/Osmo 蓝牙设备
- 建立连接并查看连接状态
- 发送拍照、开始/停止录像等控制指令
- 切换拍照 / 录像模式
- 采集手机 GPS，并在录像过程中同步记录轨迹
- 展示最近的 GPS 记录会话摘要

## 技术栈

- Kotlin
- Android SDK
- Jetpack Compose
- Android BLE
- Kotlin Coroutines

## 运行要求

- Android 10 及以上设备（`minSdk = 29`）
- 设备支持 BLE
- 已授予蓝牙与定位权限
- 已开启手机蓝牙

## 本地运行

在项目根目录执行：

```bash
./gradlew assembleDebug
```

如需运行单元测试：

```bash
./gradlew testDebugUnitTest
```

Android Studio 打开项目后，也可以直接运行 `app` 模块到真机进行调试。

## 项目结构

```text
app/src/main/java/com/unianx/osmo/remotecontrol
├── ble/        蓝牙扫描、连接、协议收发
├── data/       控制端标识、GPS 会话与本地存储
├── location/   手机定位采集
├── ui/         Compose 界面
├── MainActivity.kt
└── MainViewModel.kt
```

## 说明

- 当前仓库更偏向一个可运行的移动端控制原型。
- 实际兼容的 Osmo / DJI 设备能力取决于 BLE 协议匹配情况。
- GPS 轨迹会话为本地记录，便于后续联动录像状态做轨迹留存。
