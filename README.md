<p align="center">
  <img src="icon.png" width="128" alt="BiliSponsorSkip 图标">
</p>

<h1 align="center">BiliSponsorSkip</h1>

<p align="center">在 Android 版哔哩哔哩中提示并自动跳过社区标记的特殊片段。</p>

## 功能

- 打开视频后查询“小电视空降助手 / BilibiliSponsorBlock”的公开片段数据。
- 在视频详情标题、竖屏播放器下方标题和横屏播放器上方标题前显示分类标签，并以网页端分类颜色标识。
- 在普通播放器和 Story 播放器的竖屏、横屏进度条中，用分类颜色标出特殊片段区间。
- 检测到已选择的片段时显示 Toast；实际跳过后显示分类和大致时长。
- 默认只自动跳过“赞助/恰饭”，可在模块设置中启用其他分类：
  - 无偿/自我推广、三连/互动提醒、过场/开场动画、鸣谢/结束画面；
  - 回顾/概要、离题闲聊/玩笑、填充内容/前黑/后黑、音乐中的非音乐部分。
- 支持总开关、自动跳过、提示开关、请求失败提示、最短片段时长，以及“快进到片段中间时仍跳过”。
- 片段数据仅缓存在 B 站进程内存中，不收集账号、设备标识或观看历史。
- 可在设置中开启播放器控制栏的提交按钮：在菜单中记录片段起止点并提交，也可对当前分 P 的已有片段赞同或反对；普通播放器与短视频模式均会按各自控制栏布局注入。
- 首次打开模块设置会生成随机的 36 位提交者 ID，也可自行替换；该 ID 仅用于空降助手 API 的提交和投票。

## 工作原理

1. Hook `PlayerMoss` 请求/响应，读取当前分 P 的 `bvid` 和 `cid`。
2. 请求 `https://www.bsbsb.top/api/skipSegments/{SHA256(bvid)[0..4]}`，保留当前分 P、`actionType=skip` 且分类已启用的片段。
3. 使用 DexKit 按稳定字符串动态定位播放器的状态、进度、时长和 seek 方法，不依赖易变化的混淆类名。
4. 播放器进入片段范围时调用原生 seek，跳转至片段末尾。
5. 通过资源名、标题文本和播放器控件类型定位详情/竖屏/横屏标题及进度条；标题标签使用 CompoundDrawable，进度色块使用 ViewOverlay，不拦截宿主触摸事件。

DexKit 的 native library 必须在 APK 中保持未压缩和页对齐，供 LSPosed 模块 ClassLoader 直接加载；请勿将 `jniLibs.useLegacyPackaging` 改回 `true`。

## 兼容性

| 项目 | 要求或状态 |
| --- | --- |
| Android | 7.0（API 24）及以上 |
| Hook 框架 | LSPosed，Xposed API 82 及以上 |
| 模块包名 | `com.retrsoft.bilisponsorskip` |
| 已真机验证 | 粉版 `tv.danmaku.bili` 9.4.0、9.5.0 |
| 声明作用域 | 粉版、概念版、Play 版、HD 版 |

其他版本虽然包含在作用域内，但播放器实现可能随客户端更新而变化。遇到问题请附带下方诊断日志。

## 安装与使用

1. 从 Releases 下载 APK 并安装。
2. 在 LSPosed 中启用模块，勾选所用的 B 站客户端。
3. 强制停止并重新打开 B 站。
4. 点击桌面上的“哔哩空降助手”图标进入设置；修改设置后重新打开视频即可生效。

默认仅启用“赞助/恰饭”。建议先保持默认设置，确认跳过行为符合预期后再启用更激进的分类。

## 日志与排错

模块同时输出 LSPosed 日志和一份持久诊断文件。粉版路径为：

```text
/sdcard/Android/data/tv.danmaku.bili/files/BiliSponsorSkip.log
```

通过 ADB 查看最新日志：

```shell
adb shell tail -n 200 /sdcard/Android/data/tv.danmaku.bili/files/BiliSponsorSkip.log
```

其他客户端请将路径中的包名替换为对应作用域包名。日志可能包含 BVID、CID、播放器混淆类名和异常堆栈，但不包含登录凭据。

常见问题：

- 只有“检测到片段”但不跳过：检查日志中是否出现 `player hook installed`、`first player position received` 和 `skipped`。
- 出现 `couldn't find libdexkit.so`：确认 APK 未被二次打包或压缩 native library。
- 设置没有变化：重新打开视频；必要时强制停止并重新启动 B 站。
- 标题有标签但进度条没有色块：轻点视频展开播放控制栏；查看日志是否出现 `progress marker attached`。

## 本地构建

要求 JDK 17 和 Android SDK 35：

```shell
./gradlew testDebugUnitTest assembleDebug
```

产物位于 `app/build/outputs/apk/debug/app-debug.apk`。Release 构建若未提供签名参数会生成未签名 APK。

## 开发与贡献

提交改动前请运行单元测试和 Debug 构建，并避免提交 `local.properties`、签名文件或构建产物。详细约定见 [CONTRIBUTING.md](CONTRIBUTING.md)，版本记录见 [CHANGELOG.md](CHANGELOG.md)。

## 来源、隐私与许可

- 广告跳过流程和 BV/AV 转换代码提取、改写自 [BiliRoaming](https://github.com/yujincheng08/BiliRoaming)。
- API 行为和分类参考 [BilibiliSponsorBlock](https://github.com/hanydd/BilibiliSponsorBlock)。
- 片段数据由第三方公共服务提供，本项目不保证其准确性、可用性或持续运营。
- 本项目与哔哩哔哩、SponsorBlock 官方均无隶属关系。

上述项目与本项目均采用 GPL-3.0，详见 [LICENSE](LICENSE)。DexKit 使用其自身的 Apache-2.0 / LGPL-3.0 双许可证。
