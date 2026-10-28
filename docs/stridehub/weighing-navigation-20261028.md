# 称重一级导航调整与验证

2026-10-28，基于 `codex/native-android` / `495a14a54`，实现位于原生 Android 工作树。

## 页面行为

- 主导航显示「总览、身体、称重、运动、计划」，称重居中且始终带文字标签，移除原小型悬浮入口。
- 称重页复用主界面的 Material 3 顶栏、主导航、主题和系统边距；测量、趋势、记录及称重设置继续使用现有原生视图。
- 在称重页选择其他主栏目会返回原 MainActivity 并选中目标栏目；系统返回回到此前栏目。原栏目 ID 和各自详情路径不变。
- 登录表单之前展示完整离线称重卡片；桌面快捷方式继续支持无登录进入。
- 统一称重启动入口并防止重复启动。普通跳转保持主页面 composition，保留连接输入、筛选及表单状态；冷启动快捷方式仍延迟健康页面请求。
- BLE 生命周期、权限与文件选择回调、本机数据库及同步队列实现继续复用；没有数据库迁移。

## 验证结果

| 范围 | 结果 |
| --- | --- |
| `testDebugUnitTest` | 51 项通过，0 失败 |
| `lintDebug`、debug 与 androidTest APK | 构建通过 |
| `verify-native-apk.py` | 检查 10 个 DEX，无 WebView 构造/导航或 CookieManager 调用 |
| API 35 模拟器定向仪器测试 | 19 项通过 |
| 两个 Python E2E 脚本的旧入口选择器 | 已更新，语法检查通过；本次未运行完整 NAS E2E |

仪器测试使用专用 `stridehub_native` / `emulator-5556`，安装独立包 `io.stridehub.app.weighingtests.debug`。没有覆盖原正式版或原调试包。主导航测试使用空凭据及 `127.0.0.1:9` 的本地仓库夹具，不访问真实后端。

覆盖实际主导航进入称重并返回运动、五个栏目顺序与选中态、重复点击不叠加页面、系统返回恢复此前栏目、登录输入往返保留、旋转恢复称重子页、桌面快捷方式、手工输入校验与保存、手工草稿、实时读数与历史区分、主题与系统栏。

已人工检查默认竖屏、约 375dp 小屏、浅色/深色、两倍字号、关闭动画以及横屏的导航布局。显示大小、字号、旋转、动画与系统主题在检查后恢复。未执行实体秤 BLE 连接、TalkBack 或平板设备验收。

## 本地产物

证据目录：`var/native-artifacts/weighing-navigation-20261028/`。

- `portrait.png`、`small-phone.png`、`dark.png`、`large-font.png`、`landscape.png`、`login.png`：模拟器截图。
- `build-final.log`、`instrumentation-final.log`：构建与 19 项设备测试结果。
- `apks/StrideHub-weighing-debug.apk`：常规调试包，包名 `io.stridehub.app.debug`。
- 调试 APK SHA-256：`bb5ecbc40dbd213892257b03c79b8463873d23bc669d1e7911e22d949aeb0775`。

本轮没有递增正式版本、签名发布、部署 NAS 或推送/合并分支。调试包与模拟器验证不代表用户手机已更新，也不替代正式签名覆盖安装验收。
