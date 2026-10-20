# StrideHub 验收记录

## 量化饮食、减重与统一运动工作台（2026-10-20）

页面围绕数据与计划展开：新增饮食、减重页面，训练七日日历和统一运动统计；外部 AI 通过 API/Skill 读取聚合上下文、保存结构化计划。本次不新增站内聊天。

- **236 个定向 PHP 测试、1574 条断言通过**：健康/训练/跑步领域及 API、首次使用。新增覆盖营养缺失与零值、原子批量回滚、版本与历史、删除后 ID 不复用、计划引用、七日均值的日历窗口、目标与实测分离、云端跑步对账、合并后别名互斥及批量冲突回滚。
- 完整 PHPUnit 执行 **4548 个测试、147293 条断言**，无错误，剩余 **7 个平台快照失败**。在相同 PHP 8.5.11/macOS 环境，对干净主分支 `31bff634` 单独执行 4537 个测试，7 个失败的测试名及快照差异完全相同，均为预期 `Linux` 与实际 `Darwin`。本次导航引起的 10 份 HTML 快照已检查并更新。
- 全库 PHPStan、Rector、CS Fixer 检查通过；最终改动另经定向静态分析。Composer 严格验证、PSR-4、Twig/JavaScript 语法、OpenAPI 本地引用及 operationId 唯一性检查通过。
- `tests/e2e/workspace-browser.cjs` 使用专用数据库与真实 HTTP，通过 **8 组浏览器验收**：空状态及个人时区、API 餐食计划与网页实际摄入联动、文本转义、真实并发 409 与草稿保留、目标/测量分别持久化、七日训练日历、五个数据页面的 320/390px 布局，以及刷新失败保留旧数据与无未捕获异常。只有最后的刷新失败使用明确的 503 拦截；其他写入均由服务端处理。已人工查看桌面和移动端截图。

复现浏览器验收需要 PHP/Composer 依赖、Playwright 和 Chrome：

```sh
PLAYWRIGHT_MODULE=/absolute/path/to/playwright \
CHROMIUM_EXECUTABLE=/absolute/path/to/chrome \
node tests/e2e/workspace-browser.cjs
```

脚本只监听 `127.0.0.1:8091`，只重建 `tests/dreeveworkspace-browser.db`，使用 DEMO 资料；截图与日志保存在被忽略的 `var/runtime/workspace-browser/`。测试没有访问个人 Zepp 账户，也不代表远程服务已部署。完整 PHPUnit 另需按仓库 CI 配置生成 countries 资源、编译 `fit-tool` 并将其加入 PATH；建议设置 `memory_limit=1G`。

## 数据工作台与详细 Zepp 记录（2026-10-19 至 2026-10-20）

本次在本机开发服务验证了统一的总览、身体、运动、计划页面，原始运动档案、文件导入和高级设置沿用统一外壳。下面早期章节为历史验收，不代表当前数据接入状态。

- **224 个定向 PHP 测试、1390 条断言通过**：健康、训练、跑步、相应 API 和无个人资料首次使用流程。另有统一外壳、文件导入及连接入口相关 141 个测试、1299 条断言通过；两组有重叠，不相加。Zepp 最终定向回归为 37 个测试、253 条断言。相关生产 PHP 的静态分析、格式检查及新增 JavaScript 语法检查通过。
- 合并前全库复核执行 **4537 个测试、147137 条断言**，无错误，剩余 11 个失败与干净 `origin/main` 在同一容器中的失败内容逐字一致（9 个 HTML URL 编码快照、2 个 GPX 浮点末位差异）。本机环境为 PHP 8.5.10 / aarch64 / libxml 2.13.9；本次新增的界面兼容、鉴权、翻译和缓存快照问题已全部修复。全库 PHPStan、Rector 和 CS Fixer 检查通过。
- `tests/e2e/redesign-browser.cjs` 在专用隔离数据库通过 **9 组浏览器检查**：登录与空状态、睡眠/来源覆盖、跨时区今日安排、日期范围、计划完成回读与 409、体重共用历史与草稿保留、并发读写不回退状态、读取失败不伪造 0，以及 320/390px 布局。使用 DEMO 数据和真实 HTTP；慢响应、失败及竞态部分使用明确的浏览器拦截，不能视为真实上游故障验证。
- `tests/e2e/firstuse-browser.cjs` 验证空库登录后直接进入工作台、七个页面（含文件导入）及 API 可用、未填写姓名/生日/导入运动即可保存首条体重、320px 布局和零页面错误。`tests/e2e/workouts-browser.cjs` 用隔离 DEMO 运动验证列表、传感器曲线、未知数值、日期筛选替换超出范围的选中记录、直接链接及窄屏展示。
- **真实 Zepp 同步**验证了日常记录、分钟心率、睡眠、运动详情及原始数据归档；实际可用的速度、距离、海拔、步频、步幅、功率也已保存。派生配速与原始传感器分别说明，缺失指标保留 null。具体个人记录和统计只保留在私有验收产物中。
- 真实重复同步验证未新增重复记录、未增加原文副本，已有内容保持不变；补全后日记录包含字段来源信息。部分新响应、同日多设备和失效同步锁覆盖的问题均有独立回归；原始快照和已知历史字段不会因局部失败而被清空。
- 个人实例只读浏览器检查已覆盖真实总览、运动列表展开和详细曲线、逐日心率读取与手机布局；真实个人数据、令牌和截图保留在忽略的本地目录，不进入 Git。所有写入型界面验收使用隔离数据库。

**当前边界：**血氧、压力、PAI 和 readiness 的事件接口实际返回 HTTP 403，因此日同步为 partial，界面显示原因与缺失；睡眠和运动同步为 success。这不证明 Zepp 能提供全部历史睡眠或所有设备指标。小时同步任务已接入 daemon，但当前本机预览只运行 app，尚未启动后台调度。本次没有远程/NAS 部署、Android 真机验收、通知发送或课表下发到手表。AI 可通过 API/Skill 读取数据并保存计划，尚未启动持续自主规划。

本机预览：`http://localhost:8081/admin/insights`。上述浏览器脚本分别限定隔离端口 8084 / 8085；不要修改为个人实例地址执行写入验收。后面的全库上游快照限制仍需区分，不能把本次定向通过宣称为全库通过。

## 初始版本基线

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
