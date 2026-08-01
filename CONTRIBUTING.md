# 贡献指南

感谢参与 BiliSponsorSkip。提交 Issue 或 Pull Request 前，请先确认问题可在最新版模块复现。

## 开发环境

- JDK 17
- Android SDK 35
- Android Studio 或项目自带的 Gradle Wrapper

验证改动：

```shell
./gradlew clean testDebugUnitTest assembleDebug --warning-mode all
```

涉及播放器 Hook 时，请至少在一个真实 B 站客户端中验证，并检查诊断日志里是否出现：

```text
player hook installed
first player position received
skipped
```

## 提交问题

请提供：

- Android、LSPosed 和 B 站客户端版本及包名；
- 模块版本、启用的分类和简要复现步骤；
- `BiliSponsorSkip.log` 中相关时间段的内容；
- 是否经过二次打包、修改签名或压缩 APK。

日志可能包含 BVID、CID 和混淆类名。公开上传前可自行检查和脱敏，但请保留完整异常堆栈。

## 代码约定

- 优先使用稳定类名、方法签名或字符串特征，避免依赖短期混淆名。
- Hook 回调中不要执行网络请求或其他阻塞操作。
- Toast 和诊断信息必须去重，避免影响正常观看。
- 不得提交 keystore、密码、`local.properties`、APK 或构建目录。
- 新增可独立测试的解析或转换逻辑时，请补充单元测试。
