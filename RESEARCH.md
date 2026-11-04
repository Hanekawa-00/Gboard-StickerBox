# Gboard / FunBox 研究记录

调查日期：2026-10-27 与 2026-11-04。证据来自 GitHub 连接器实际读取的仓库及源码。作者的设备测试结论与本项目验证严格分开。

## Gboard+

- 模块目录：https://modules.lsposed.org/module/io.github.kvmy666.gboardplus/
- 实际源码：https://github.com/kvmy666/Gboard
- 发布目录 README：https://github.com/Xposed-Modules-Repo/io.github.kvmy666.gboardplus
- `app/src/main/java/io/github/kvmy666/gboardplus/GboardPlusHook.kt`：读取到 `InputMethodService.setInputView(View)` after-hook，用容器增加工具栏；有输入连接包装及富媒体 URI 授权处理。不能把其实现中的假设当作 Android 16 官方保证。
- README 报告在 Android 16 / OxygenOS、Gboard 18.4.1 验证；这是**作者报告**，不是本项目测试。README 的许可证章节仅说 “Personal project. See repository for terms.”，本项目没有复制其源码，不推定专有实现可复用。

设计选择：Hook Framework 稳定方法，保持传入 Gboard 的 View 参数不变，在基类调用后增加独立容器，避免硬编码混淆类或 Google 私有资源 ID。探测 View parent、实际布局和已注入标记；异常隔离，默认关闭。未知版本仅做显式开启后的实验性尝试。

## KeyFlux

https://github.com/NawafCode/KeyFlux ，MIT。调查树 SHA `e1b855422aad3ddefbaac41f3a997215e8149d9f`。

实际阅读 `PluginEntry.kt` 和 `CompatibilityManager.kt`：限制 Gboard 包名与主进程；ContextWrapper.attachBaseContext / Application.attach 初始化；DexKit 查找混淆配置读取方法，结果按 Gboard versionCode 缓存，升级后重新定位；分功能捕获异常。其 Android 版本识别代码不构成本项目在 Android 16 可工作的证据。

StickerBox 首版不需要 DexKit：只有 Framework `setInputView`，不启用未经检测的私有 Hook。未来若加入原生工具栏位置适配，应为每个 Gboard versionCode + APK 摘要缓存“唯一匹配的签名/字符串定位结果”，匹配不唯一则禁用该适配器。

## uSticker

https://github.com/apsun/uSticker ，MIT。调查树 SHA `732ec3c75bb78b566925c92e119b34920165d987`。

README 明确：不兼容 Gboard 10.2 及以上。它通过 Firebase 索引与内容提供器提供本地表情，不是新版 Gboard LSPosed 插件入口。实际 `StickerProvider.kt` 使用 DocumentsContract 访问用户选择的 SAF 树，将 openFile 转交文件提供器。旧 Firebase/Gboard 索引流程不作为本项目依赖。

## QTool

https://github.com/Hicores/QTool ，AGPL-3.0，仓库已归档。调查树 SHA `840188b6a7e2fbd2cd4941e5c9a562f3c94f550f`。

实际阅读 `StickerPanelPlus/Hooker/StickerPanelJHook.java`：QQ 版本范围、方法签名及字符串扫描定位，注入 QQ 自身的面板按钮，调用 QQ 私有消息机制。这属于特定宿主适配，不意味着 Gboard 可以通用调用这些接口。本项目不复制 QTool 源码，不 Hook 微信/QQ，不调用聊天应用私有消息发送 API。

## Android 16 / LSPosed

原始 https://github.com/LSPosed/LSPosed README 的 Supported Versions 写 Android 8.1–14。这不能证明所有以 LSPosed 命名的后续分支均不支持 16，也不能证明用户已安装的版本支持 16。需要记录用户框架的**仓库来源、版本号、构建号**和 ROM，并在真机确认 Hook 加载。

Android 官方富媒体输入：https://developer.android.com/develop/ui/views/touch-and-input/image-keyboard

FileProvider：https://developer.android.com/reference/androidx/core/content/FileProvider

InputConnection：https://developer.android.com/reference/android/view/inputmethod/InputConnection#commitContent(android.view.inputmethod.InputContentInfo,int,android.os.Bundle)

上述官方链接为 API 参考入口；本次环境无法通过浏览器/终端实际获取这些网页正文，不将其记为已抓取材料。实现使用 compileSdk 36 的公开 API：EditorInfo.contentMimeTypes、InputContentInfo、ClipDescription、commitContent、ClipData、ACTION_SEND。读取 MIME 声明后再提交；拒绝时用户选择回退。跨应用 URI 由模块 UID 向 Gboard 及当前目标授予只读权限，分享通过 FileProvider + ClipData + READ flag。

## FunBox：附件与证据状态

最初会话中实际看到 `FunBox_v2929.Apk`，文件大小约 6.7 MiB；随后执行环境故障、重建，2026-11-04 的环境中原路径已不可访问。之前未读取 DEX/Manifest/资源，因此**没有完成 APK 静态分析**，不能报告包名、服务器接口、缓存结构、加密或开源许可结论。

`funbox.zip`、`funbox_export_original_formats.zip` 未收到。本项目目前无法对用户真实数据做往返迁移验证。GitHub 搜索出现同名 FunBox 仓库，但没有包名/签名证据将其与用户 APK 关联，故不判定为此 App 的公开源码。

| 域名 | 证据来源 | APK 静态命中 | 实际网络验证 |
|---|---|---|---|
| api.fpfast.top | 用户提供的历史线索 | 未分析 | 未验证 |
| file.funobj.top | 用户提供的历史线索 | 未分析 | 未验证 |
| fun-project.top | 用户提供的历史线索 | 未分析 | 未验证 |
| opt.dtproj.top | 用户提供的历史线索 | 未分析 | 未验证 |

不能把代理连接失败视为这些站点下线。没有使用这些域名的私有 API，也没有上传图片。

## 迁移实现及待补分析

导入器不依赖 FunBox 的名称/扩展名，读取 PNG/JPEG/GIF/WebP/ISO-BMFF HEIF 文件头。原 GIF/WebP 按字节保留，HEIF 经系统解码转 PNG，ZIP 层级末级文件夹作为分类。内容摘要去重，不使用原路径进行文件写入。普通原格式导出库可以用此方式迁移；加密数据、数据库资源映射、私有容器及嵌套归档尚不支持。

后续拿到附件应先做 SHA-256、Manifest/证书、DEX 字符串与调用链、缓存文件魔数/数据库 schema/资源映射分析。网络验证须分别记录时间、DNS/TLS/HTTP 状态及无需鉴权的请求；不把 HTTP 200 或字符串命中等同完整 API 可用。只复用可互操作的数据格式，不将反编译源码视为可复制授权。


## 2026-11-04：用户设备反馈后的 v0.2 修订

用户截图显示 Gboard `18.4.1.985164140-release-arm64-v8a`，原生键盘可用，但 StickerBox 没有按钮，状态为“无心跳”。这不足以区分 LSPosed 未加载与 IPC 失败。进一步阅读参考项目的当前实现，发现全屏 InputView 和实际键盘区域的高度可以不同，外层包裹可能把工具栏放到可见窗口之外；Gboard+ 也针对包可见性使用独立通信途径。

本项目独立实现了有边界的视图结构遍历：只接受唯一具有 keyboard-base-area 语义标记的 FrameLayout 及其垂直内容列；把自己的工具条插入内容列，在同一区域增加可关闭的不透明面板覆盖层，不移动原生视图。仅引用可观测的结构标记，不复制参考项目源代码，不依赖混淆类名；找不到或有歧义则上报状态并保持原键盘。

同时增加 InputMethodService.onCreate / showWindow 生命周期探测、固定诊断 ping、异常类型日志，以及固定非私有标记文件的 URI 引导授权。以上为可验证的代码修订，仍未取得用户手机 LSPosed 日志，不能宣称已确定该手机的最终原因或已通过真机验证。
