# 健康计划与体重记录

项目保存真实观察和用户授权的计划。计划由用户或外部 AI 提供，服务器负责验证、持久化与展示，不自动生成减重、饮食或医疗方案。

## 鉴权与公共约定

- 外部接口前缀：`/api/v1/health`，使用现有 Bearer API Key。
- 网页接口前缀：`/admin/health/api`，使用管理员登录会话；PUT/DELETE 还要求 `X-CSRF-Token: csrf_token('health')`。
- 所有响应（含错误）使用 `Cache-Control: no-store, private`。
- 请求为 JSON 对象，未支持字段会返回 422。完整机器可读定义位于 `GET /api/v1/health/openapi.json`。

## 健康计划

`GET /plans` 返回 `{ "plans": [...] }`，按 `startDate` 倒序。`GET /plans/{id}` 返回单条。`PUT /plans/{id}` 创建或顶层部分更新；`DELETE /plans/{id}?version=N` 删除。

稳定 ID 由调用方指定，1–80 位字母、数字、短横线或下划线。创建带 `version:0`，更新及删除带当前整数版本。成功保存后版本加一；相同创建请求在版本仍为 1 时可重放，不产生重复记录。过期版本返回 409，缺失版本返回 428。

```json
{
  "version": 0,
  "title": "一周作息观察",
  "category": "sleep",
  "goal": "记录睡眠与晨起体感，为后续调整提供依据",
  "startDate": "2026-10-19",
  "endDate": "2026-10-25",
  "status": "draft",
  "source": "ai",
  "sourceLabel": "用户授权的 AI 助手",
  "rationale": "用户要求先记录作息。已有数据不足以推断恢复程度。",
  "items": [{"id":"morning-note","title":"记录晨起体感","date":"2026-10-19","time":"07:00","status":"pending","notes":""}],
  "notes": ""
}
```

- 必填创建字段：`title`、`goal`、`startDate`。`endDate:null` 表示尚未设结束日期。
- `category`：`weight`、`sleep`、`nutrition`、`recovery`、`general`。
- `status`：`draft`、`active`、`paused`、`completed`、`cancelled`。默认 `draft`。
- `source`：`user`、`ai`、`clinician`。AI 计划必须有非空 `rationale`；来源是调用方声明，不是医生身份验证。
- `items` 最多 200 条；每条 `id` 唯一，`title` 必填。`date:null` 表示未指定日期，非空日期必须在计划范围内；`time:null` 表示未指定时间，非空为 `HH:mm`（使用个人档案时区）；`status` 为 `pending/completed/skipped`。
- 更新 `items` 是整体替换，修改一条前必须读出并保留其他条目。状态不会根据运动或测量数据自动完成。
- 返回附加 `id`、`version`、`updatedAt`。同一记录没有完整修订历史，版本用于冲突检测。
- 训练课次继续使用 `/api/v1/training/sessions` 与原子 batch，不将健康计划条目重复复制成训练记录。

## 体重

`GET /weight?from=YYYY-MM-DD&to=YYYY-MM-DD` 返回日期倒序的列表：

```json
{"records":[{"date":"2026-10-19","weightKg":72.4,"source":"manual","sourceLabel":"","notes":"晨起实测"}],"version":"64位校验字符串","unit":"kg"}
```

无记录返回 `records:[]`。没有目标体重、平均值或起始值的默认假数据。历史记录无来源时为 `unknown`；只有日期，不推断精确测量时间。

`PUT /weight/2026-10-19` 正文为 `{ "version": "刚读取的字符串", "weightKg": 72.4, "source": "manual", "sourceLabel": "", "notes": "晨起实测" }`，返回完整最新列表。`source` 可为 `manual/import/unknown`；新条目默认 manual。省略来源、备注时保留该日期旧值。一天一条记录，同日期更新；`DELETE /weight/{date}?version=...` 删除。

**体重版本是字符串，不能用 `0` 创建。** 版本覆盖完整历史和当前单位设置；即使 GET 使用日期过滤，也要提交原样返回的整体版本。旧接口或设置页的修改会使版本过期，返回 409。写入结果不确定时先读回当天记录。

存储继续使用现有 `Setting.weightHistory`，没有新建一份 TrainingRecord 体重源。新接口固定使用 kg，内部换算为旧设置单位，旧 API 和统计读取同一份历史；修改会触发原有设置缓存失效事件。旧 API `/api/v1/athlete/weights` 仍遵循旧单位与写入约定。Zepp 原始身体观察保留在设备数据中，不自动替换人工确认的体重历史。

## AI 使用

使用仓库 `skills/stridehub-coach/references/data-records.md` 组合读取运动、Zepp、体重、血压、健康背景和既有计划。先明确数据缺口、范围、来源和时区，再按已有授权保存计划。写入后 GET 核验，不能把建议输出误称为已下发计划。
