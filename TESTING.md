# 测试状态与真机验收

## 状态原则

本文件随源码交付。**GitHub Actions 的具体 commit、绿色结果与报告才是编译/单元测试/Lint 通过的证据**；工作流文件存在不代表已运行。未连接用户的 Android 16 Root 真机，下方真机场景仍待验证；远程 Android 16 模拟器结果见下节。

## v0.2 最终修订的本地验证（2026-11-04）

代码 commit：`f4d1afd100afae6d0abf49f6cc5840c1eac38d3d`。

- 本地使用 JDK 21、Gradle 8.13、Android SDK 36，Debug APK 构建成功。
- 7 项核心 JUnit：0 failures / 0 errors；Android Lint 通过阻断检查。
- Android instrumentation 测试 APK 编译成功；签名、打包 Xposed 入口与最终 Manifest 检查通过。
- 本地 APK 为 v0.2.0-mvp / versionCode 2，v2 签名。
- 最终修订增加了显式面板高度约束及回归断言。截至本次交付时，其完整模拟器任务仍在 GitHub 排队：https://github.com/Hanekawa-00/Gboard-StickerBox/actions/runs/41862521466 。不能把测试 APK 编译成功记成用例已执行。
- 前一修订 `2e2142717959d132c50f6d7d548903df2a6f3042` 已在 Android 16 模拟器执行 7 项 instrumentation 测试并通过，包括合成视图挂载、触摸隔离、导入备份及 GIF 检查；最终高度断言是在该次之后补充的。历史运行：https://github.com/Hanekawa-00/Gboard-StickerBox/actions/runs/41861001911 。
- 用户提供的 Gboard 版本为 18.4.1.985164140；没有连接该手机，LSPosed 是否加载及实际入口显示仍待用户反馈。

## v0.1 已完成的实际验证（2026-11-04，历史结果）

被测代码 commit：`0a082b4b04c509af3ac90f7f02259ff8bd02e66b`。

工作流：https://github.com/Hanekawa-00/Gboard-StickerBox/actions/runs/41841680732 ，`build` 与 `emulator-smoke` 两个 job 均成功。

| 检查 | 实际结果 |
|---|---|
| Debug APK / Kotlin 编译 | 通过，JDK 17 / Gradle 8.13 / compileSdk 36 |
| 核心规则 JUnit | 7 项通过 |
| Android Lint | 通过阻断检查；不代表零 warning，见报告 |
| 原 Manifest / 最终 APK Manifest | 通过；无网络、广泛存储、root 权限申请，仅 AndroidX 自身 signature 接收器权限 |
| Xposed assets 入口 / DEX | 打包存在，结构检查通过；未验证 LSPosed 实际加载 |
| APK 签名 | apksigner 验证通过，v2 签名 |
| Android 16 / API 36 x86_64 instrumentation | 3 项通过：管理界面启动；导入/去重/搜索/SQLite/备份往返；GIF 字节保留与动画解码/本 UID 导出读取 |
| 独立 adb 安装 / Activity 启动 | 安装 Success，启动 Status: ok |
| shell UID 访问限制 | Bridge call 与未授权 FileProvider read 均抛 SecurityException；不替代普通目标 App 间转授测试 |
| 界面截图 | 已生成 manager-emulator.png，见模拟器报告 |
| Android 16 Root / LSPosed / Gboard / 微信 / Telegram / QQ | **未测试** |

交付包：https://github.com/Hanekawa-00/Gboard-StickerBox/actions/runs/41841680732/artifacts/13328333540

构建报告：https://github.com/Hanekawa-00/Gboard-StickerBox/actions/runs/41841680732/artifacts/13328288620

模拟器报告与截图：https://github.com/Hanekawa-00/Gboard-StickerBox/actions/runs/41841680732/artifacts/13328164341

本节是构建完成后的文档补记，不改变已测试的应用代码。产物中的 BUILD-RESULTS.md 记录构建 job 的结果，模拟器测试结果见独立报告。

v0.2 增加 RECEIVE_BOOT_COMPLETED 权限，仅用于重建对 Gboard 的固定非私有引导 URI 授权；FileProvider 新增 files/bridge-bootstrap/ 这个只含 marker.txt 的目录，仍不暴露表情源目录或数据库。诊断 ping 只返回阶段及版本元数据，不提供配置写入或表情访问功能。

CI 工作流 `.github/workflows/android.yml` 执行：结构检查 → Debug 构建 → JUnit → Android Lint → 最终 Manifest / APK 签名 / 打包入口检查 → 上传交付物及报告。失败时查看 Actions 日志，不生成伪 APK。测试失败不能被忽略。交付包的 BUILD-RESULTS.md 记录该次构建 commit、运行链接和单元测试计数。

另一个 `emulator-smoke` job 会在 Android 16 / API 36 x86_64 模拟器运行 instrumentation 测试：管理 App 启动；SQLite 索引、伪扩展名导入、去重、搜索、备份与恢复；GIF 原始字节与系统动画解码、生成导出 URI 的本 UID 可读性。测试后重新安装 App，捕获管理界面截图，并检查 shell UID 访问 BridgeProvider / 未授权 FileProvider URI 被拒绝。它不验证普通应用 UID 间的授权转授，也没有 Root、LSPosed、Gboard 或微信。报告和截图在 `android16-emulator-reports`；必须检查 job 实际结果，不能因配置存在就声称通过。测试仅用于可丢弃模拟器，会清空测试表情库。

v0.2 新增三个独立的 Android View 层级测试，验证全屏根视图下工具条位于底部键盘区域、原生父子关系不变、未知/歧义结构不修改，以及一个引导 FileProvider 范围测试。它们是合成视图测试，不是 Gboard 或 LSPosed 真机测试。v0.2 结果以对应工作流为准。

单元测试 `MediaRulesTest`：七项 JUnit 测试覆盖媒体魔数、截断/伪装输入、ZIP 路径穿越、真实解压字节上限、MIME 匹配及不静默扁平化 GIF。它们不验证 Android 解码器、Binder 权限、数据库或 Gboard 运行时。

`scripts/check_project.py`：Manifest 无权限申请、禁用系统备份、私有 ShareActivity、FileProvider 非导出且仅 outgoing cache 路径、Xposed 元数据/入口文件、XML 可解析及 Hook 没有输入/剪贴板读取调用。提供 APK 参数时验证打包入口及 DEX。此检查不替代 Lint、编译或安全审计。

## 真机前置记录

记录 Android 版本/API、ROM、机型、LSPosed 来源与版本、Gboard versionName/versionCode，以及微信/Telegram/QQ 版本。保留备用输入法。作用域只勾选 Gboard；关闭其他 Gboard 修改模块以排除冲突。

## 管理与性能

1. 分别导入 PNG/JPEG/GIF/WebP/HEIC，含错误扩展名、无扩展名、中文分类、多级文件夹、重复图片、损坏文件。
2. 检查原 GIF/WebP 摘要和动画；HEIC 必须显示转换结果或可理解的失败，不得伪装成功。
3. ZIP 含路径穿越、绝对路径、大文件、超过 512 MiB 的展开数据、超过 10,000 条目及嵌套 ZIP，应限额拒绝且无越界写入。导入可部分成功。
4. 导入 1,000 张，滑动、换页、搜索名称/分类、切换收藏/最近；使用 Android Studio Profiler 验证内存、耗时、旋转和进程重启。
5. 改名、分组、排序、删除、收藏；备份后在空库恢复，核对内容、收藏、分类、排序和最近使用时间。
6. 外部专属目录不可用时应失败而不丢失原库；切换只影响新文件。

## Hook 与降级

1. 默认关闭时键盘无新增 UI；启用并重启 Gboard 后应出现入口及带版本信息的心跳。
2. 进入/退出面板、多次切换编辑框和应用、切换横竖屏/主题/语言、折叠/浮动/分离键盘；检查原键盘文字、剪贴板、翻译等操作。
3. 升级 Gboard 重跑。结构不兼容时不注入，原键盘应工作；不得将旧版本结果延伸到新版本。
4. 禁用模块时应隐藏按钮，若异常用系统设置停止 Gboard 或 LSPosed 禁用后重启。注入后 15 秒内进程被杀可能触发保守安全标记；在管理 App 重置。
5. Hook 面板目前无文字搜索，管理 / 搜索会切到管理 App。此路径仅分享，不承诺回到原输入框自动提交。

## 发送矩阵（全部需真机）

| 应用 | PNG/JPEG | GIF 动画 | WebP | 判定标准 |
|---|---|---|---|---|
| 微信 | 待测 | 待测 | 待测 | 编辑器 MIME / commit 返回 / 附件显示 / 授权 / 最终接收端动画分别记录 |
| Telegram | 待测 | 待测 | 待测 | 同上，注意发送为图片、贴纸、文件或视频的区别 |
| QQ | 待测 | 待测 | 待测 | 同上 |
| 普通聊天/自建 MIME 接收器 | 待测 | 待测 | 待测 | 含拒绝、抛异常、不声明 MIME、只接受 PNG 等分支 |

操作：在真实聊天编辑框中点击贴纸；若接口接受，仍确认缩略图和最终发送后的接收端；若不支持，分别测试复制图片、手动粘贴及 ACTION_SEND。不得把 true 返回值或出现分享页面记成“消息发送成功”。未实现应用私有 API 自动发消息。

## URI 与隐私

- 模块无 INTERNET、存储大范围访问、AccessibilityService 或 root shell 请求。
- BridgeProvider 虽导出，但每个入口检查 Binder UID（自身或 Gboard）。应使用独立测试 App 验证其他 UID 的 call/openFile/query 均拒绝；adb shell 的拒绝不是所有权限场景的充分证据。
- FileProvider 仅暴露 outgoing cache 和固定引导标记目录，非导出、只授予读；检查目标 UID 能读取被选文件，不能访问数据库/源目录/未选文件，无法写入。
- prepare 对模块调用清除 Binder identity，再由模块 UID 生成 URI 授权及不可变 PendingIntent；私有分享 Activity 不能被普通 App 直接启动。
- 明确授权在存活进程 10 分钟后撤销。**进程提前死亡时，Handler 定时任务不会执行，陈旧导出在下次 prepare 才清理。** 接收端已复制的文件无法撤回；需要更严格保证时后续加入持久化清理任务。
- 检查日志仅包含固定事件码、版本和心跳；不得出现输入文本、密码、聊天内容或剪贴板历史。剪贴板回退只写入用户选中的一张图片。

## 附件与构建环境限制

2026-10-27 的 FunBox APK 在执行环境重建后不可读；两个数据 ZIP 未收到，实际数据迁移未测。本地只有 JRE、无 Android SDK/Gradle 缓存且代理连接失败，因此将构建放到 GitHub Actions；不是 Android 源码编译失败的证据。
