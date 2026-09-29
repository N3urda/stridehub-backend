# StrideHub 验收记录

验证日期：2026-09-28。基础版本：Dreeve `11a100cfa4007219d8c87eb04a019c6b1e81c0dc`。运行环境为 PHP 8.5.10、SQLite、锁定的 Composer 依赖与本机 Chromium。

## Android APK 增量验收（2026-09-29）

本次只新增 Android 客户端与交付配置，未修改后端鉴权、业务逻辑或数据库结构。

- JDK 17、Gradle 8.13、AGP 8.13.2、SDK 36 构建通过，最低 SDK 26。`testDebugUnitTest` 的 **2 个 JUnit 入口、109 条断言通过**；`lintDebug` 为 0 errors / 3 warnings（固定工具版本提示、图标资源配置建议）。正式和调试 APK 均可构建，正式包使用独立私钥签名，`apksigner verify` 通过。
- **7 个原生 instrumentation 测试通过**：旧 WebView 的网络/HTTP/渲染退出/文件选择回调不会影响新页面；当前网络错误仍可重试；连续文件选择不会替换先前的下载地址或上传回调。两项文件选择并发问题先在模拟器复现失败，再修复通过。
- `tests/e2e/android-login.cjs` **5 组通过**：2 组真实 HTTP（登录成功进入 Today、错误密码拒绝）；3 组合成页面契约（10 种错误/不可信表单、特殊字符转义、安装子路径）。合成契约不代替实际后端验证。
- `tests/e2e/android-apk.py` 使用最终签名 APK 在 Android 15 / API 35 / arm64 Pixel 7 模拟器完成 **9 组真实场景**：原生 URL/HTTP 校验、错误与正确密码、四个导航入口、系统文件选择后上传并核对 GPX 字节、今日睡眠写入并以独立 HTTP 查询确认、进程终止后会话恢复、断网重试，以及切换/退出后再次要求密码。原生系统选择器和实际后端请求均无 mock；旧上传页面的 accessibility 漏报使用该模拟器已观察坐标作为测试 fallback。全程只使用隔离数据库、DEMO 资料和专属 watch 目录，个人数据库 SHA-256 未变。
- 正式 APK SHA-256：`a56a8528693a5700dd5bae1c57333f469c9ed86ce45a080414b7db11f4c06f0f`。
- 签名正式包：`io.stridehub.app`，版本 `0.1.0` / versionCode `1`，只申请 INTERNET 权限，关闭 Android 备份与迁移，未内置后端地址、密码或 API key。构建产物和签名材料均不进入 Git。

构建与运行方式见 [`android/README.md`](../../android/README.md)。验收记录仅覆盖所列平台和场景，不表示已在用户真机或远程生产服务验收；用户 Zepp 资料未被接入。

## 手机今日安排与多记录对账增量验收

- **367 个定向 PHP 测试、1237 个断言通过**。覆盖本地日期边界、未知状态、适用健康约束、快速反馈字段合并、旧单条关联兼容、多条关联互斥、批量转移/冲突回滚、缺失或删除记录、重叠提示以及 Bearer/CSRF。
- 全库 PHPStan、相关 PHP 的 CS Fixer/Rector、Twig/JavaScript 语法及 Skill 格式验证通过。新迁移使用真实 Doctrine 命令完成 up/down/up；旧关联及多条关联的回填/重做另有测试。
- `tests/e2e/today-browser.cjs` 的 **9 组真实 HTTP/浏览器场景通过**。使用独立数据库内标记为 DEMO 的运动记录，没有模拟 API 响应：390px 手机页面、跑者 UTC 与浏览器洛杉矶时区、天气缺失、健康约束、部分身体状态、补给反馈、草稿保留、延后四小时跑步、热身/主课合并、重复占用拒绝、并发 409，以及旧编辑器保存后保留新字段和空页面。
- `tests/e2e/today-ui-contract.cjs` 是另一个明确模拟接口的浏览器契约测试，验证不确定写入回读、断连/HTTP 503 后不盲目重试、反馈保存后立即关联的版本同步、过期请求不回退状态、课次移出列表和草稿保留。它不替代上一项真实联调证据。
- 独立复查发现并修复：空 `fuel:{}` 清空已存补给、删除后的旧运动阻碍无关反馈保存、快速反馈与关联之间版本不同步。均有先失败后通过的回归用例。
- 隔离验证期间个人数据库 SHA-256 完全一致，测试容器已移除。随后先备份个人数据库，再应用 `Version20260928000000` 迁移并清理缓存；个人运动及训练记录逐项保持不变，实际 Skill 客户端可读取个人实例 Today API，仍无导入活动或真实健康报告。

复现真实浏览器验收：准备独立空库 `var/runtime/today-e2e-database/dreeve.db`，用本项目应用监听 `http://localhost:8082` 并执行迁移，再设置 `STRIDEHUB_TEST_URL=http://localhost:8082`、`PLAYWRIGHT_MODULE` 和 `CHROMIUM_EXECUTABLE` 运行 `node tests/e2e/today-browser.cjs`。脚本在该独立库准备 DEMO 活动，保留合成身体状态/约束供检查，不能对个人数据库运行。当前开发服务仍只绑定本机，验收未进行远程部署或手表下发。

## HTTP API + Skill 增量验收

- 合并健康接口后的同一组 PHP 回归：**335 个测试、1029 个断言通过**，其中新增健康接口 37 个测试、158 个断言。覆盖鉴权、健康响应禁止缓存、版本冲突、部分更新、数组替换、来源/日期/引用完整性和载荷边界。
- Python API 客户端：**25 个测试通过**。真实临时 HTTP 服务覆盖 Bearer、UTF-8 JSON、配置覆盖、401/409、写入后丢失响应、5xx、无效/超大响应、拒绝重定向、路径校验与凭据脱敏。写结果不确定时要求回读，不自动重复写入。
- 全库 PHPStan、新增 PHP 的 CS Fixer/Rector、Skill 格式验证通过。客户端只需要 Python 标准库；Skill 格式校验器的 PyYAML 安装在被忽略的独立验证环境中，不改变应用依赖。
- 独立行为评估使用虚构资料：无 Skill 时确认缺少长期健康背景接口；加载 Skill 后验证“409 改期保留并发备注/补给”“保存新报告保留旧报告与偏好，并处理与医生限制冲突的间歇请求”“历史记录为空时不编造训练基线”三个场景。评估发现的 status 查询默认值与空生效日期说明已修正；最终复查补充了按未来课次日期判断健康约束生效时间的规则。
- 本机 `stridehub-coach` 已安装；连接配置只引用本地凭据文件。实际客户端读取个人开发实例的健康背景成功，仍为 version 0 的空背景，没有真实体检数据。
- `tests/e2e/skill-api.py` 的 **11 个真实 HTTP 场景通过**：三份鉴权 OpenAPI、健康摘要保存/追加/冲突回读，以及两节课表的批量保存、冲突回滚和删除。测试使用独立数据库与容器；个人数据库前后 SHA-256 一致，临时容器已移除，个人开发实例仍健康。

复现客户端测试：`PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests/skill -p test_stridehub_api.py`。真实 HTTP 验收须先启动独立空实例，显式设置 `STRIDEHUB_TEST_URL=http://localhost:8082` 后运行 `python3 tests/e2e/skill-api.py`；脚本会在该临时库留下标记为 DEMO 的健康资料，不能对个人实例运行。Skill 文件位于 `skills/stridehub-coach/SKILL.md`。以下训练/看板浏览器验收仍对应已有功能，不代表网页新增了健康档案编辑器。

## 已验证

| 范围 | 结果 |
|---|---|
| 训练、天气、提醒、跑步分析，以及既有 API/鉴权/调度/运动列表回归 | **298 个测试，871 个断言通过** |
| 全库 PHPStan | 无错误 |
| 新功能 PHP CS Fixer、Rector | 无待修复项 |
| 新增/修改 Twig、JavaScript 语法、容器编译 | 通过 |
| 训练表迁移 | 独立 SQLite 上 up → down → up 通过 |
| Docker 构建上下文 | 两份 OpenAPI 和使用指南可进入镜像，未被 `.dockerignore` 排除 |
| 训练助手真实 HTTP/浏览器 | 15 组场景通过，无 mock 路由 |
| 跑步看板真实 HTTP/浏览器 | 独立数据库的 561 条演示记录、完整传感器与缺失传感器场景通过 |

训练流程覆盖管理员登录、Bearer 鉴权、CSRF、创建与修改比赛/课次/结构化步骤/补给计划、身体状态、补给记录、刷新持久化、真实 API 与浏览器的版本冲突、保留未保存草稿、改期/跳过/反馈、删除清理、390px 手机布局。只提供部分反馈字段的 Codex 更新，经网页再次编辑后不会生成未填写的 RPE 或冷热感受。上海下一日预报实际请求 Open-Meteo 成功，返回覆盖完整课次的两小时预报；这是当次联网验证，不保证外部服务持续可用。

跑步流程覆盖全部历史统计与每页 50 条记录的分离、超过 500 条记录、跑步类型筛选、最佳片段、24 个公里分段、联动曲线和缩放、时间/距离横轴、缺失心率、XSS 文本转义、390px 手机布局及原生运动列表/详情入口。数值测试覆盖不等采样间隔、记录断档、暂停、无效时间戳、缺失值、压缩流解码、圈记录和跨分段的等距离半程比较。长记录抽样仍保留断档，统计使用完整有效区间。

提醒通过受控投递器验证四类提醒、逐渠道重试与去重、跨时区/夏令时、失效窗口和 dry-run；**没有向真实通知渠道发送验收消息**。开启实际推送还需配置用户自己的通知渠道，并启用个人提醒和 daemon 任务。

## 上游快照限制

另运行 `tests/Domain/Activity/ActivityFragmentResolverTest.php`：17 项中 4 项出现既有 HTML 快照差异，内容为 `filters%5Bgear%5D` / `filters%5Bdevice%5D` 与 `filters[gear]` / `filters[device]` 的表示不同。将未修改的基础提交导出到独立目录，在相同 PHP 容器和依赖中复测，得到完全相同的 4 项差异；两份输出除耗时与内存外相同。本次只更新新增看板入口对应的快照片段，保留原有 URL 编码预期。实际浏览器的入口跳转已通过。

因此上述结果是指定范围的验收，**不表示整个上游测试套件全部通过**。

## 复现

在已安装依赖的 PHP 8.5 环境运行：

```bash
APP_ENV=test APP_DEBUG=1 TEST_TOKEN=stridehub vendor/bin/phpunit \
  tests/Domain/Training tests/Domain/Running \
  tests/Console/Notification/TrainingReminderConsoleCommandTest.php \
  tests/Controller/Api/V1 tests/Infrastructure/Security \
  tests/Infrastructure/Http/Gate tests/Infrastructure/Daemon/Cron \
  tests/Domain/Settings/DaemonSettingsTest.php \
  tests/Controller/Admin/Activity/ManageActivityOverviewRequestHandlerTest.php \
  tests/Domain/Activity/ActivitiesFragmentTest.php
vendor/bin/phpstan analyse --no-progress
node --check public/js/stridehub-training.js
node --check public/js/stridehub-running.js
```

浏览器脚本位于 `tests/e2e/training-browser.cjs`、`tests/e2e/running-browser.cjs`，需要 Playwright/Chromium 和独立测试实例。通过 `TRAINING_CREDENTIALS_FILE` 指定本机 JSON 凭据文件（`username`、`password`、`apiKey`）；文件不得提交。训练脚本使用 `TRAINING_BASE_URL`，分析脚本使用 `RUNNING_BASE_URL`。仅在空的临时数据库上执行训练验收；脚本会暂时修改个人设置并在结束后恢复。分析数据脚本 `tests/e2e/seed-running.php` 仅写指定的 `var/runtime/analytics-e2e-database/dreeve.db`，需先对该独立数据库执行迁移，且已有运动记录时拒绝播种。浏览器中不能同时进行人工编辑。

## 数据与交付边界

- 本地个人开发实例位于 `http://localhost:8081`，已确认健康，未残留验收课次或演示运动；通知保持关闭。
- 561 条记录只属于独立验收库，不代表用户运动。用户的 Zepp 账号及真实历史数据尚未接入；导入 FIT/TCX/GPX 或配置 Zepp Connector 后，看板读取实际数据。
- API、界面、迁移和镜像文档打包均已实现；这次没有执行远程生产部署或实际通知渠道验收。
- 天气、传感器缺失及指标估算边界见 [使用指南](README.md)。
