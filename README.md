<p align="center">
  <img src="icon.png" width="128" alt="BiliSponsorSkip 图标">
</p>

<h1 align="center">BiliSponsorSkip</h1>

<p align="center">在 Android 版哔哩哔哩中提示并自动跳过社区标记的特殊片段。</p>

> [!IMPORTANT]
> 本项目基于 Xposed/LSPosed Hook 技术实现，目标应用更新可能导致 Hook 点变化，从而出现功能异常或无法加载。
>
> 如果遇到版本不兼容问题，请提交 Issue，并提供对应版本的 B 站客户端安装包（APK）、Android 版本、Hook 框架信息以及相关日志，方便进行适配测试。

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
- 可在设置中开启播放器控制栏的提交按钮：
  - 在菜单中记录片段起止点并提交；
  - 对当前分 P 的已有片段赞同或反对；
  - 普通播放器与短视频模式均会按各自控制栏布局注入。
- 首次打开模块设置会生成随机的 36 位提交者 ID，也可自行替换；
  - 该 ID 仅用于空降助手 API 的提交和投票。

## 工作原理

1. Hook `PlayerMoss` 请求/响应，读取当前分 P 的 `bvid` 和 `cid`。
2. 请求：

```text
https://www.bsbsb.top/api/skipSegments/{SHA256(bvid)[0..4]}

保留当前分 P、actionType=skip 且分类已启用的片段。

3. 使用 DexKit 按稳定字符串动态定位播放器的状态、进度、时长和 seek 方法，不依赖易变化的混淆类名。


4. 播放器进入片段范围时调用原生 seek，跳转至片段末尾。


5. 通过资源名、标题文本和播放器控件类型定位详情/竖屏/横屏标题及进度条；

标题标签使用 CompoundDrawable；

进度色块使用 ViewOverlay；

不拦截宿主触摸事件。




DexKit 的 native library 必须在 APK 中保持未压缩和页对齐，供 LSPosed 模块 ClassLoader 直接加载。

请勿将：

jniLibs.useLegacyPackaging

改回 true。

兼容性

项目	要求或状态

Android	7.0（API 24）及以上
Hook 框架	LSPosed，Xposed API 82 及以上
模块包名	com.retrsoft.bilisponsorskip
已真机验证	粉版 tv.danmaku.bili 9.4.0、9.5.0
声明作用域	粉版、概念版、Play 版、HD 版


其他版本虽然包含在作用域内，但播放器实现可能随客户端更新而变化。

遇到问题请附带下方诊断日志。


---

安装与使用

本项目支持三种运行方式：

方式一：LSPosed 框架加载（需要 Root）

适用于已经安装 Magisk / KernelSU 等 Root 环境，并配置 LSPosed 框架的设备。

安装步骤：

1. 从 Releases 下载模块 APK 并安装。


2. 打开 LSPosed 管理器。


3. 在「模块」列表中启用 BiliSponsorSkip。


4. 在作用域（Scope）中选择需要支持的 B 站客户端：

粉版；

概念版；

Play 版；

HD 版。



5. 强制停止 B 站客户端并重新打开。



首次启动后，可以点击桌面上的“哔哩空降助手”图标进入设置。


---

方式二：LSPatch 加载（无需 Root）

适用于没有 Root 权限，但希望使用 Xposed 模块功能的设备。

安装步骤：

1. 安装 LSPatch。


2. 准备：

BiliSponsorSkip 模块 APK；

对应版本的 B 站客户端 APK。



3. 使用 LSPatch 修补 B 站客户端：

添加本项目作为模块；

完成重新打包。



4. 安装生成后的 APK。


5. 启动 B 站客户端。



注意：

LSPatch 兼容性可能受到目标应用签名校验、完整性检测影响。

部分 B 站版本可能需要关闭应用自动更新。

如果重新打包失败，请确认 LSPatch 版本与 Android 系统版本兼容。



---

方式三：内嵌模块版本（无需 Root）

本项目支持将模块直接内嵌到 B 站客户端中运行。

适用于：

没有 Root；

不希望安装 LSPatch；

希望直接安装使用的用户。


获取方式：

1. 自行使用 LSPatch 将模块嵌入目标版本 B 站客户端；


2. 从项目 Telegram 发布频道获取已经打包好的版本。



内嵌版本与 LSPatch 方式原理一致，仅提前完成模块注入。


---

默认仅启用“赞助/恰饭”。

建议首次使用保持默认设置，确认跳过行为正常后，再开启其他分类。


---

日志与排错

模块同时输出 LSPosed 日志和一份持久诊断文件。

粉版路径：

/sdcard/Android/data/tv.danmaku.bili/files/BiliSponsorSkip.log

通过 ADB 查看最新日志：

adb shell tail -n 200 /sdcard/Android/data/tv.danmaku.bili/files/BiliSponsorSkip.log

其他客户端请将路径中的包名替换为对应作用域包名。

日志可能包含：

BVID；

CID；

播放器混淆类名；

异常堆栈。


但不会包含：

登录凭据；

Cookie；

账号信息。


常见问题：

只有“检测到片段”但不跳过

检查日志中是否出现：

player hook installed
first player position received
skipped


---

出现：

couldn't find libdexkit.so

请确认：

APK 未被二次打包；

native library 未被压缩。



---

设置没有变化

尝试：

1. 重新打开视频；


2. 强制停止 B 站；


3. 重新启动应用。




---

标题有标签但进度条没有色块

尝试：

展开播放控制栏；

查看日志是否出现：


progress marker attached


---

本地构建

要求：

JDK 17；

Android SDK 35。


执行：

./gradlew testDebugUnitTest assembleDebug

产物：

app/build/outputs/apk/debug/app-debug.apk

Release 构建若未提供签名参数，会生成未签名 APK。


---

开发与贡献

提交改动前请运行：

./gradlew testDebugUnitTest

并完成 Debug 构建。

请避免提交：

local.properties

签名文件

构建产物


详细约定见：

CONTRIBUTING.md

版本记录见：

CHANGELOG.md


---

来源、隐私与许可

广告跳过流程和 BV/AV 转换代码提取、改写自：

BiliRoaming


API 行为和分类参考：

BilibiliSponsorBlock


片段数据由第三方公共服务提供，本项目不保证其准确性、可用性或持续运营。

本项目与哔哩哔哩、SponsorBlock 官方均无隶属关系。



---

安全性声明

本项目遵循：

GNU General Public License v3.0（GPL-3.0）

为了保证项目透明性与可验证性：

项目完整源代码公开；

所有构建流程通过 CI 自动完成；

Release 发布版本由 CI 自动构建生成；

不包含隐藏代码、闭源模块或无法审计的第三方组件。


用户可以自由查看、修改、编译和分发本项目代码，但必须遵守 GPL-3.0 协议要求。

请注意：

请尽量从项目官方 Release 或可信渠道获取 APK；

不建议安装来源不明的二次修改版本；

第三方重新打包版本的安全性由发布者自行负责。


本项目不会：

收集账号信息；

收集设备唯一标识；

上传观看历史；

后台运行额外服务。


模块运行所需数据仅用于本地功能实现。


---

版本适配反馈

由于 B 站客户端持续更新：

内部类结构；

资源名称；

播放器实现；


均可能发生变化。

如果出现：

模块无法加载；

视频播放无效果；

自动跳过异常；

进度条标记消失；


请提交 Issue，并附带：

B 站客户端版本；

对应 APK 文件；

Android 系统版本；

LSPosed / LSPatch 版本；

LSPosed 日志；

模块诊断文件。


如果条件允许，请直接提供出现问题版本的安装包，方便进行本地适配测试。


---

上述项目与本项目均采用 GPL-3.0 协议，详见：

LICENSE

DexKit 使用其自身的 Apache-2.0 / LGPL-3.0 双许可证。