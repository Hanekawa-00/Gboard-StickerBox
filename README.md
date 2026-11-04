# Gboard StickerBox

本地表情管理 App + 独立 LSPosed 模块。目标 Android 16 / 新版 Gboard；**目前是需要真机核验的 MVP**，不保证所有 Gboard/ROM/聊天应用组合可用。无网络权限、无输入内容/密码/聊天内容/剪贴板历史读取。

## 构建与下载

GitHub → Actions → Android Debug → 选择最近成功的运行 → Artifacts → `Gboard-StickerBox-debug`。其中包含 Debug APK、源码 ZIP、README、研究报告、测试步骤和带 commit 的 BUILD-RESULTS.md。`verification-reports` 包含实际的测试与 Lint 报告，`android16-emulator-reports` 包含管理 App 的模拟器测试报告和截图。请分别检查 build / emulator-smoke job 的结果。可以在 Actions 页面手动 Run workflow。

本地要求 JDK 17、Android SDK Platform 36；Android Studio 打开根目录后同步 Gradle。项目含标准 Gradle 8.13 wrapper。执行：

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
python3 scripts/check_project.py app/build/outputs/apk/debug/app-debug.apk
```

Debug APK：`app/build/outputs/apk/debug/app-debug.apk`。CI 的 debug 签名可能随运行变化：覆盖安装失败时先备份，再卸载重装。不要向陌生人提供私钥。此项目不需要仓库 Secrets。

## v0.2 修订

修复全屏 InputView 外层工具栏可能落到可见键盘之外的问题：现在只在唯一匹配的 `keyboard-base-area` 区域内安装，保留原生视图父子关系；未知或歧义结构不注入。设置页通过独立只读 ping 区分模块未加载、开关关闭、通信失败和布局未匹配。提供一个仅包含固定文本的引导 URI 读取授权，帮助 Android 11+ 的 Gboard 获得模块包可见性；重启接收器仅重建这项授权，不采集任何输入或数据。

升级 CI Debug APK 如遇签名不一致，先在旧版备份 ZIP，再卸载安装并恢复；不要先清除数据。

## 安装与启用

1. 安装 APK，打开 StickerBox，用“导入图片 / ZIP”多选文件，或选择“导入文件夹”。
2. LSPosed 中启用模块，作用域**仅勾选 Gboard**：`com.google.android.inputmethod.latin`。不要勾选 Android 系统或聊天应用。
3. 管理 App → 设置 / 状态 → 启用 Gboard 面板。默认关闭，未知版本启用属于实验性兼容尝试。
4. 在系统“Gboard 应用信息”中强制停止 Gboard，再打开聊天输入框；必要时重启设备。
5. 键盘上方出现 StickerBox 行。点击 StickerBox 浏览，点击“键盘”恢复原键盘。
6. 若出现安全降级，管理 App 中重置标记，关闭其他键盘修改模块后重启 Gboard 再试。先保留另一款可用输入法。

## 已实现的操作

- 多选图片、SAF 文件夹与 ZIP 导入；以内容签名识别 PNG/JPEG/GIF/WebP/HEIF，忽略文件扩展名；HEIF 通过系统 ImageDecoder 转为不超过 2048 边长的 PNG，解码不支持会报告失败。
- SHA-256 去重；SQL 索引；按文件夹分组；编辑名称/分类/排序数字；收藏、最近使用；管理 App 全文子串搜索（名称及分类）。不使用未经验证的 FunBox API。
- 每页 120 张，RecyclerView 复用、Coil 缩略图及 GIF 预览。Hook 内采用系统控件、异步缩略图和内存 LRU，避免依赖 Gboard 内部资源。
- 管理 App 点击图片打开系统分享，长按进入预览和编辑。Hook 点击尝试 `commitContent`，失败后由用户选择复制单张图片或系统分享。
- 备份 ZIP 含原格式图片（HEIF 已转换）、名称、分组、收藏和排序；用导入 ZIP 恢复。恢复合并进现有库，不清空旧数据。
- 内部目录或外部应用专属目录可选；切换只影响新导入，不移动旧文件。**不支持任意自定义 SAF 目录作为实时数据库根目录。** 卸载 App 会删除两处文件，请先备份。

## 发送边界

`commitContent()` 返回 true 只表示输入接口接受请求，不能证明图片显示、GIF 动画保留或消息已发送。最终请在目标应用确认并点击发送。微信/QQ 可能不声明可接受的图片 MIME，或拒绝来自输入法的内容；此时用系统分享或复制后手动粘贴。剪贴板图片也不保证目标应用支持。

不会静默把 GIF 转为静态 PNG。静态 JPEG/WebP 在目标只接受 PNG 时可以转换；WebP 动画在此分支可能变为首帧，想保留动画请从管理 App 分享原文件。GIF 通过系统分享是否被压缩或转为视频由目标应用决定。

## 当前限制

- 尚无 Android 16 Root/LSPosed 真机测试、未取得用户当前 Gboard APK；不能声称兼容已验证。
- Hook 面板支持分类、收藏和最近；**Hook 内文字搜索尚未实现**，可通过“管理 / 搜索”进入管理 App 搜索再分享，不会劫持原键盘按键。
- Framework Hook 加结构标记检测是入口尝试，不是所有浮动、分离、横屏键盘布局的保证。结构检查失败时不注入。
- 单文件 32 MiB，单次导入解压后 512 MiB，10,000 个条目（含目录），库容量 10,000 张。ZIP64 由 Java 解压支持，但仍受此限额；不递归解压 ZIP。导入可部分成功，请查看结果；保持 App 前台，旋转/退出可能中断导入。
- 备份恢复保留收藏、分类、排序和最近使用时间。数据库不支持降级迁移。
- 导出 URI 在进程存活时 10 分钟撤销读取授权；进程被杀时下次准备分享才清理陈旧导出。此限制见 TESTING.md。
- 没有 FunBox 在线分类/搜索/下载功能，不复制专有实现。当前执行环境恢复后未保留原 APK，两个数据 ZIP 也未收到，实际 FunBox 格式映射仍待附件分析。

研究证据见 [RESEARCH.md](RESEARCH.md)，检查方法和未验证项见 [TESTING.md](TESTING.md)。原创代码采用 MIT；依赖遵守各自许可。Gradle wrapper 来自 Gradle 8.13（Apache-2.0）。
