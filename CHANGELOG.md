# 更新日志

本项目遵循[语义化版本](https://semver.org/lang/zh-CN/)，发布日期以 GitHub Release 为准。

## Unreleased

- 模块包名由 `me.retr0.bilisponsorskip` 迁移为 `com.retrsoft.bilisponsorskip`。
- 完善 README、贡献指南和版本记录。
- 新增由 `v1.0.0` 格式标签触发的签名构建与 GitHub Release 工作流。

## 0.2.4

- 修复 LSPosed 无法从压缩 APK 加载 `libdexkit.so`，native library 改为未压缩并按 16KiB 页面对齐。
- 经粉版哔哩哔哩 9.4.0 真机验证，播放器定位、进度监听和自动跳过均正常工作。
- 新增写入 B 站外部文件目录的持久诊断日志。

## 0.2.2

- 新增 Hook、播放器实例、播放进度和 seek 四阶段失败诊断 Toast。

## 0.2.1

- 根据稳定字符串动态定位播放器状态方法，并加入主动播放进度轮询。
- 设置读取改用 `XSharedPreferences`，兼容 MIUI 应用可见性限制。
- 接入应用图标。

## 0.2.0

- 新增模块设置入口、发现片段 Toast 和跳过结果 Toast。
- 新增最短片段时长、“快进到片段中间时仍跳过”和九种片段分类设置。

## 0.1.0

- 首个可构建版本：获取 BVID/CID、请求特殊片段数据并尝试通过播放器 Hook 自动跳过。
