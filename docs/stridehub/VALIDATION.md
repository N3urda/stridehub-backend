# StrideHub 验收记录

验证日期：2026-09-28。基础版本：Dreeve `11a100cfa4007219d8c87eb04a019c6b1e81c0dc`。运行环境为 PHP 8.5.10、SQLite、锁定的 Composer 依赖与本机 Chromium。

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
