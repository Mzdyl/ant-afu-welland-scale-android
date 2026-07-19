# 阿福沃莱体重秤 Android 应用

一个面向 `AFU-WL-TZ-A1` 的 Android 应用，用于读取蚂蚁阿福沃莱体重秤，并把可对应的数据写入 Health Connect。

## 功能

- 自动扫描并保存体重秤设备，下次优先直连。
- 读取体重、BMI、阻抗/ADC。
- 本地估算体脂率、脂肪量、肌肉率、体水分、蛋白质、骨量、骨骼肌和皮下脂肪。
- 写入 Health Connect：体重、体脂率、去脂体重、体水分量、骨量。
- 保存测量记录到 `measurements.jsonl`。
- 保存连接、最终测量、同步和错误等关键过程到 `app-log.jsonl`。
- 重启应用后恢复最近一次完整测量，可继续同步到 Health Connect。
- 界面内可复制或清空日志。

## 构建

```bash
./gradlew :app:assembleDebug
```

生成文件：

```text
app/build/outputs/apk/debug/app-debug.apk
```

安装到已连接手机：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 使用

1. 打开应用，填写年龄、性别、身高。
2. 授权蓝牙和定位权限。
3. 轻踩体重秤唤醒设备。
4. 点击“开始测量”。
5. 首次连接成功后会保存设备，后续优先直连。
6. 点击“授权并同步到 Health Connect”后，测量完成会自动同步。

如果扫描不到设备，点击“重新扫描设备”，并确认系统蓝牙和定位服务已开启。

## 日志

界面底部显示最近日志，可直接点击“复制日志”。

调试时也可以用 adb 读取：

```bash
adb shell run-as io.github.afuwellandscale cat files/app-log.jsonl
adb shell run-as io.github.afuwellandscale cat files/measurements.jsonl
```

日志不会记录每一次广播和重量波动，以免快速膨胀。扫描成功时记录设备与广播数据；扫描超时时只记录汇总统计。日志超过 256 KiB 后会自动保留最近 300 条。

## Health Connect

应用只申请写入权限，不读取其他健康数据。当前写入字段为：

- Weight
- Body fat
- Lean body mass
- Body water mass
- Bone mass

蛋白质、骨骼肌、皮下脂肪目前只在本地显示和记录，因为 Health Connect 没有一一对应的标准字段。
