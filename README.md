# 阿福沃莱体重秤

面向 `AFU-WL-TZ-A1` 的原生 Android 应用。自动连接体脂秤，读取体重和阻抗，在本地估算身体组成，并可写入 Health Connect。

## 功能

- 打开或从后台返回应用即自动测量；权限或蓝牙开启弹窗返回后继续当前尝试。
- 自动扫描并保存设备，后续优先直接连接；10 秒连接不上时自动重新扫描。
- 离开应用立即暂停并释放蓝牙连接；扫描 20 秒、整次测量 60 秒超时后可手动重试。
- 暂停、失败或测量完成后不会在同次访问中自行重新开始。
- 实时显示体重，测量完成后展示 BMI、体脂、脂肪量、肌肉、骨骼肌、体水分、蛋白质、骨量和皮下脂肪。
- 本地保存完整历史，可查看详情、单条删除或清空。
- 授权后自动补同步历史，并在每次测量完成后自动同步；确定性记录 ID 可避免重复数据。
- 只要求年龄、性别和身高，体重与阻抗由设备测量。
- 保存精简诊断日志，支持复制、清空和自动限制体积。
- Material 3 Expressive 界面、动态颜色、深色模式；自适应桌面图标及 Android 13+ 单色主题图标。

## 页面

- **测量**：优先显示实时体重和暂停／重试按钮，等待读数时不混入历史结果；历史结果显示明确的测量日期。
- **记录**：本地历史、前后体重变化和可滚动的指标详情，单条删除与清空均需确认。
- **设置**：个人资料验证后显式保存，测量期间禁止修改；设备管理、Health Connect 状态、诊断与版本信息。

## 环境

- Android 8.0 或更高版本（API 26+）。
- Android Studio 或 JDK 17+。
- 蓝牙低功耗和定位权限。
- Health Connect 为可选功能。

## 构建与验证

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

调试构建输出位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

安装到已连接设备：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 技术结构

- Kotlin、Jetpack Compose、Material 3 Expressive。
- Android BLE GATT，FFB0/FFB1/FFB2 服务与特征。
- Health Connect 写入体重、体脂率、去脂体重、体水分量和骨量。
- JSONL 本地测量记录和诊断日志。

Material 3 Expressive 目前使用 `androidx.compose.material3:material3:1.5.0-alpha24`，升级时需要重新运行测试和 Lint。

## 数据与隐私

- 应用不会读取 Health Connect 中的其他健康数据。
- 年龄、性别、身高、设备地址、测量记录和日志只保存在应用本地。
- 测量记录与日志不参与云备份或设备迁移。
- 蛋白质、骨骼肌和皮下脂肪没有对应的 Health Connect 标准记录，只在本地展示。
- 身体组成来自本地 BIA 公式估算，适合观察趋势，不用于医疗诊断。

诊断时可通过界面复制日志，也可以使用：

```bash
adb shell run-as io.github.afuwellandscale cat files/app-log.jsonl
adb shell run-as io.github.afuwellandscale cat files/measurements.jsonl
```

## GitHub CI 与手机直接安装

推送到 `main` 或手动运行 **Android builds** 会执行单元测试及 Debug / Release Lint，生成两个 APK：

- `afu-scale-release.apk`：关闭调试、压缩代码和资源，适合日常使用。
- `afu-scale-debug.apk`：保留调试能力，适合诊断。

构建成功后，两个 APK 和 `SHA256SUMS` 会同时保存在 Actions 构建产物中，并发布到标记为 `ci-构建序号-重试次数` 的 GitHub 预发布页面。手机可直接打开仓库的 **Releases** 页面下载 APK，无需通过电脑传输。两个版本使用相同包名和签名，可以保留数据相互覆盖安装。

CI 签名使用仓库 Actions Secrets，不提交到 Git：

- `ANDROID_SIGNING_KEYSTORE`：现有安装签名密钥库的 Base64 内容。
- `ANDROID_SIGNING_STORE_PASSWORD`、`ANDROID_SIGNING_KEY_ALIAS`、`ANDROID_SIGNING_KEY_PASSWORD`：对应的密码和别名。

保留此签名密钥，后续更新必须使用相同签名。拉取请求构建不注入签名密钥、不发布预发布版本；其 Release APK 未签名，仅用于构建验证。

本地构建签名 Release 时，设置 `AFU_SIGNING_STORE_FILE`、`AFU_SIGNING_STORE_PASSWORD`、`AFU_SIGNING_KEY_ALIAS` 和 `AFU_SIGNING_KEY_PASSWORD` 后执行 `./gradlew :app:assembleRelease`。未配置时输出未签名的 Release APK。
