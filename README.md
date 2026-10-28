# 手机去广告 Android 客户端

这是 [android-ad-cleaner](https://github.com/rddmwmddr/android-ad-cleaner) 的移动端二次开发版本。原项目主要提供连接电脑的桌面客户端；本仓库聚焦纯 Android 端实现，在手机本地通过 Shizuku 完成组件扫描、挂起和营销通知渠道处理，不启动原项目的电脑端 Python 服务。

此 Android 工程独立保存在 `mobile-app/`，没有混入上游仓库。应用通过 Shizuku UserService 在手机本地扫描、挂起广告组件，并筛选和操作营销通知渠道；不启动电脑端 Python 服务，也不需要 USB。

## 手机上使用

1. 安装并启动 Shizuku。Android 11 及以上可用系统无线调试在手机本地配对并启动，无需电脑或 USB；重启手机后通常需要重新启动 Shizuku。
2. 安装 `app/build/outputs/apk/debug/app-debug.apk`，启动后点击“连接 / 授权 Shizuku”。
3. 扫描上游清单里的广告及快应用包（包含 vivo / iQOO 快应用），核对后勾选并确认。快应用会显示近期打开事件计数和后台进程状态，帮助你判断是否仍在使用。只使用 `pm suspend`，不会卸载或停用应用；快应用挂起后会尝试 force-stop。
4. 通知渠道需在系统无障碍设置中单独开启“通知渠道辅助操作”。扫描只列名称匹配营销关键词、当前开启的候选；服务通知、找机位通知、系统锁定项、已关闭渠道会排除。每次逐个处理，页面标题与唯一的“允许通知”开关都匹配后才点击，之后用 `dumpsys notification` 回读验证。
5. 每次挂起组件或更改通知渠道前，应用都会先把设备型号、相关系统开关、上游清单组件的挂起状态和目标渠道状态保存为本地快照。首页“生成 / 查看本地改动前快照”也可手动生成快照并复制报告。快照保存在应用私有目录；实际成功改动另存恢复记录。在应用中可恢复成功挂起的包、vivo 快应用开关、msa 网络许可和通知渠道。

扫描包名和渠道候选策略取自上游 `tool/run.py`、`tool/chan_fast.py`。未实测的快应用包会在界面标注。挂起小米 msa 时，会探测系统网络控制接口，先保存原网络许可状态，再尝试断网并回读；接口不可用会明确报告，包挂起仍可单独恢复。

上游按机型编写的系统设置 UI 自动化脚本、应用内广告开关和开发者选项收尾尚未移植到此 APK。通知快照是本地审阅记录；恢复列表只记录本应用确认改动成功的项目。渠道页面结构不能明确识别时会跳过，需核对实机效果。

## 当前测试记录

已在 Redmi K100 Pro Max、澎湃 OS 4.0.4 上完成以下测试：

1. Shizuku 获取并使用授权。
2. 扫描和挂起广告 / 快应用组件。
3. 扫描营销通知渠道，并自动跳转到对应的关闭入口；关闭开关仍需用户手动确认执行。

以上记录是当前实机验证结果，不代表所有品牌和系统版本均已验证。

## 构建

工程使用 minSdk 24、targetSdk 34。构建需要 JDK 17、Android SDK Platform 34 和 Gradle 8.2+：

```sh
gradle :app:assembleDebug
```

产物为 `app/build/outputs/apk/debug/app-debug.apk`。普通 x86_64 Android 开发环境无需额外设置。当前 ARM64 开发容器的构建命令为：

```sh
gradle :app:assembleDebug -Pandroid.aapt2FromMavenOverride=/opt/ad-cleaner/mobile-app/tools/aapt2
```

该 ARM64 构建覆盖参数需要 QEMU user mode 和 x86_64 兼容运行库。APK 使用 Gradle 自动生成的 debug 签名。
