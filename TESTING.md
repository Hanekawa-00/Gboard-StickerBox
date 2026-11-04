# 测试状态与真机验收

## 状态原则

本文件随源码交付。**GitHub Actions 的具体 commit、绿色结果与报告才是编译/单元测试/Lint 通过的证据**；工作流文件存在不代表已运行。当前会话未连接任何 Android/Root 设备，所有以下设备场景均为待验证。

CI 工作流 `.github/workflows/android.yml` 执行：结构检查 → Debug 构建 → JUnit → Android Lint → 最终 Manifest / APK 签名 / 打包入口检查 → 上传交付物及报告。失败时查看 Actions 日志，不生成伪 APK。测试失败不能被忽略。交付包的 BUILD-RESULTS.md 记录该次构建 commit、运行链接和单元测试计数。

另一个 `emulator-smoke` job 会在 Android 16 / API 36 x86_64 模拟器运行三个 instrumentation 测试：管理 App 启动；SQLite 索引、伪扩展名导入、去重、搜索、备份与恢复；GIF 原始字节与系统动画解码、生成导出 URI 的本 UID 可读性。测试后重新安装 App，捕获管理界面截图，并检查 shell UID 访问 BridgeProvider / 未授权 FileProvider URI 被拒绝。它不验证普通应用 UID 间的授权转授，也没有 Root、LSPosed、Gboard 或微信。报告和截图在 `android16-emulator-reports`；必须检查 job 实际结果，不能因配置存在就声称通过。测试仅用于可丢弃模拟器，会清空测试表情库。

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
- FileProvider 仅暴露 outgoing cache，非导出、只授予读；检查目标 UID 能读取被选文件，不能访问数据库/源目录/未选文件，无法写入。
- prepare 对模块调用清除 Binder identity，再由模块 UID 生成 URI 授权及不可变 PendingIntent；私有分享 Activity 不能被普通 App 直接启动。
- 明确授权在存活进程 10 分钟后撤销。**进程提前死亡时，Handler 定时任务不会执行，陈旧导出在下次 prepare 才清理。** 接收端已复制的文件无法撤回；需要更严格保证时后续加入持久化清理任务。
- 检查日志仅包含固定事件码、版本和心跳；不得出现输入文本、密码、聊天内容或剪贴板历史。剪贴板回退只写入用户选中的一张图片。

## 附件与构建环境限制

2026-10-27 的 FunBox APK 在执行环境重建后不可读；两个数据 ZIP 未收到，实际数据迁移未测。本地只有 JRE、无 Android SDK/Gradle 缓存且代理连接失败，因此将构建放到 GitHub Actions；不是 Android 源码编译失败的证据。
