# Zepp 运动原始数据与轨迹

Zepp 运动与日常健康记录分别保存。运动主列表是轻量摘要；每次云端返回的摘要和详情原文以内容摘要为键留存，已知的传感器轨迹另外解码。未知字段和未解码的流仍保存在原始详情中，不会因为当前界面没有图表而丢弃。

## 接口

以下接口使用现有 Bearer API Token。管理员页面的对应前缀为 `/admin/health/api/zepp/workouts`，沿用管理员登录；POST 额外要求 `X-CSRF-Token`（`health`）。所有返回均为 `Cache-Control: no-store, private`。

| 方法与路径 | 返回 |
| --- | --- |
| `GET /api/v1/health/zepp/workouts?from=YYYY-MM-DD&to=YYYY-MM-DD` | `{records: [...]}`，包含边界的日期筛选，新到旧 |
| `GET /api/v1/health/zepp/workouts/{id}` | 摘要、`streams`、`sourceSummary`、`sourceDetail`、`sourceRecordIds` |
| `GET /api/v1/health/zepp/workouts/sources/{hash}` | 历史原文快照，含来源、所属运动、类型、抓取时间和 payload |
| `GET /api/v1/health/zepp/workouts/status` | 连接与同步状态、记录/样本计数、失败/部分详情计数、最近范围和下一步 |
| `POST /api/v1/health/zepp/workouts/sync` | JSON `{ "days": 30 }`，允许 1–90 天；不接收账号或密钥 |

服务器命令：`php bin/console app:health:zepp:workouts:sync --days=90`。定时任务可加 `--if-configured`。凭据与日常健康同步共用受限的 `storage/zepp/credentials.json`，账号所有权由统一保护记录校验。命令只输出计数和同步状态，不输出测量值、位置或凭据。

## 摘要和单位

列表含 `id`、`source: zepp`、`sourceId`、`date`、`startedAt`、`endedAt`、`sportType`、`sportName`、`durationSeconds`、`elapsedSeconds`、`distanceMeters`、`caloriesKcal`、`averageHeartRateBpm`、`maxHeartRateBpm`、`averageSpeedMetersPerSecond`、`averageCadenceSpm`、`ascentMeters`、`descentMeters`、`totalSteps`、`trainingLoad`、`aerobicTrainingEffect`、`anaerobicTrainingEffect`、`vo2Max`、`detailState`、`retainedFields`、`streamCounts`、版本和同步时间。

缺失值为 `null`，不能作为零值参与分析。平均速度由距离/运动时长计算。`sportType` 保存 Zepp 原始类型码；未知类型仅显示“运动”，不猜测类型。

| streams 字段 | 单个实测点 |
| --- | --- |
| `heartRate` | `{at, bpm}` |
| `speed` | `{at, metersPerSecond}` |
| `pace` | `{at, secondsPerKilometer}`，由同时间的有效速度换算 |
| `cadence` | `{at, stepsPerMinute}` |
| `stride` | `{at, meters}` |
| `altitude`、`distance` | `{at, meters}` |
| `power` | `{at, watts}` |
| `location` | `{at, latitude, longitude}` |

所有时间保留时区。每条流保持自身的采样时刻，未插值、未补齐空测量，也未在没有 GPS 时生成路线。心率采用累计值增量编码；空时间增量表示一秒。位置采用累计坐标增量和 `1e8` 缩放；高度、步幅及累计距离的厘米转换为米。位置流中断后无法确认的增量不生成坐标。

## 部分数据和恢复

网络失败保留此前的详情和轨迹，并标记 `stale`。HTTP 成功但部分字段或整条流缺失时保留此前的有效读数，`retainedFields` 明示哪些字段沿用，详情标为 `partial`。历史原文快照可通过 `sourceRecordIds` 回溯；AI 应结合 `sourceDetailFetchedAt` 和这些标记判断时效，不把保留值当成新测量。

同步使用有时效的数据库所有权记录。每条运动的原文、摘要、轨迹与所有权续期一起提交；旧同步即使晚到，也不能覆盖新的同步结果。运动历史按 Zepp `count`/`trackid` 分页，遇到异常分页、超限响应或授权失败时会明确报错，已成功提交的记录保留。

## 已验证边界

接口与字段格式依据真实账号只读响应及上游 [Zepp connector 客户端](https://github.com/dreeveapp/dreeve-zepp-connector/blob/main/src/dreeve_zepp_connector/zepp_client.py)、[轨迹解码器](https://github.com/dreeveapp/dreeve-zepp-connector/blob/main/src/dreeve_zepp_connector/decoder.py) 核对。Zepp 不是稳定的公开开发者接口；设备未上传、不支持或未授权的字段无法补造。原始详情可含分段、暂停、游泳、训练效果、设备、天气及其他扩展字段，是否实际存在以该次响应为准。

单元与接口测试覆盖实测点解码、空时间增量、无效 GPS 和空段不移位、幂等、原文保留、部分同步恢复、账号与并发所有权隔离、认证/CSRF和日期筛选。真实同步证明服务器可读写这些记录，尚不等同于逐条与手机 App 校对所有单位和完整性。
