# HTTP API 使用约定

## 配置与调用

需要 Python 3。客户端脚本默认读取 `~/.config/stridehub/connection.json`：

```json
{
  "baseUrl": "http://localhost:8081",
  "credentialsFile": "/absolute/path/to/private/credentials.json"
}
```

凭据文件只需包含 `apiKey`。现有本地开发凭据文件含其他字段，客户端只读取 `apiKey`。相对凭据路径相对于连接配置目录解析。不要把凭据复制进 Skill、Git、提示词或日志。连接配置与凭据文件只允许当前用户读取。

也可注入 `STRIDEHUB_URL`、`DREEVE_API_KEY`、`STRIDEHUB_CREDENTIALS_FILE`；环境变量优先。`--config PATH` 选择其他配置。不使用 MCP。除回环地址外要求 HTTPS；不跟随重定向，以免将凭据带到其他地址。

```bash
python3 <skill-directory>/scripts/stridehub_api.py request GET /api/v1/training/openapi.json
python3 <skill-directory>/scripts/stridehub_api.py request GET /api/v1/running/openapi.json
python3 <skill-directory>/scripts/stridehub_api.py request GET /api/v1/health/openapi.json
python3 <skill-directory>/scripts/stridehub_api.py request GET '/api/v1/training/sessions?from=2026-10-05&to=2026-10-11'
python3 <skill-directory>/scripts/stridehub_api.py request PUT /api/v1/training/sessions/batch --body-file /private/path/plan.json
```

JSON 请求文件使用安全文件工具写入，包含真正的换行；不要拼接不可信内容进 shell。`--body-file -` 可以从 stdin 读取。文件中不得含 API Key。含健康信息的临时文件存放在用户私有目录，不放仓库，并在任务结束后清理本次创建的临时副本。

## 接口地图

| 方法与路径 | 用途 |
|---|---|
| `GET /api/v1/running/overview?from=all&sportType=all&page=1` | 全历史统计、可用日期范围及分页记录 |
| `GET /api/v1/running/overview?from=YYYY-MM-DD&to=YYYY-MM-DD` | 指定日期范围的完整汇总 |
| `GET /api/v1/running/activities/{id}` | 传感器分析、分段、圈和数据覆盖 |
| `GET /api/v1/training/profile` | 地点、时区、固定跑步日、偏好 |
| `GET /api/v1/training/today` | 跑者时区的今日安排、状态、约束与待办 |
| `GET /api/v1/training/reconciliation?from=...&to=...` | 待对账课次及候选，不自动关联 |
| `GET /api/v1/training/races` | 比赛日期和目标 |
| `GET /api/v1/training/sessions?from=...&to=...` | 所有状态的课表；仅需某状态时追加合法 `status` |
| `GET /api/v1/training/check-ins?from=...&to=...` | 主观疲劳、酸痛、疼痛和睡眠时长 |
| `GET /api/v1/training/fuel-logs?from=...&to=...` | 补给演练 |
| `GET /api/v1/training/briefing?sessionId=...` | 已保存课次的预报与穿衣建议 |
| `GET /api/v1/training/sessions/{id}/comparison` | 计划/实际差异与候选运动 |
| `POST /api/v1/training/sessions/{id}/link` | 用 version 与完整 activityIds 列表确认/替换关联 |
| `PUT /api/v1/training/sessions/{id}/feedback` | 用 version 部分合并反馈及实际补给汇总 |
| `PUT /api/v1/training/sessions/batch` | 原子批量创建/部分更新，1–100 节 |
| `GET/PUT /api/v1/health/context` | 结构化报告摘要、来源约束、日常偏好 |

资源 CRUD 的完整字段读取相应 OpenAPI。不要把臆造的 `readiness`、`diagnosis`、`healthNotes` 等字段塞进 profile/check-ins。不要调用未实现的“自动生成课表”端点：规划由本 Skill 完成，API 校验并保存。

## 写入与并发

- 更新和删除必须带当前整数 `version`；新记录由客户端指定稳定 ID，批量创建使用 `version:0`。首次健康背景返回 `id:default, version:0, updatedAt:null`。
- 常规资源 PUT 是顶层部分更新。未提供的字段保留；**提供的数组和嵌套对象整体替换**。因此追加一份报告时需读取原 `reports` 并合并；用常规课次 PUT 更新 `feedback.notes` 也要保留原有反馈。专用 `PUT /sessions/{id}/feedback` 例外：按提供的反馈字段及 fuel 子字段合并。不要把未知反馈补成默认值。
- `activityIds` 是完整关联集合，最多 20 条；旧 `activityId` 只表示第一条，显式写旧字段会替换整个集合。关联冲突不得靠删除别人课次来绕过。空 activityIds 解除关联但不会自动改成 planned；确认关联非空记录会标记 completed。
- 一周调整放在一次原子 batch 中；超过 100 条拆分后不再保证跨请求原子性，先缩小任务范围或说明边界。批量失败时先读回确认，不逐条盲目补写。
- 收到 409：GET 最新记录，对比本次目标字段及其依赖。只有不冲突的变更，才在保留新内容后按已有授权重新构建最小请求；最多自动合并重试一次。目标日期、强度、健康约束等存在实际冲突或再次发生冲突时，停止相关写入并说明差异。
- 写请求超时、断连、服务端 5xx 或无法解析响应都不能证明未写入。先 GET 稳定 ID 和受影响日期确认结果；不要自动重复 POST，或创建另一个 ID 规避冲突。客户端自身不重试任何写请求。
- 401 需要修复凭据；404 表示资源/能力不存在；422 需要修正数据；428 缺少版本。不要绕过接口改数据库。
- 成功后 GET 核验改动，报告记录 ID、版本和修改理由。当前只有乐观版本保护，没有完整历史版本存档或一键回滚；不能承诺自动撤销。

## 分析口径

总览 `summary/weekly/monthly` 覆盖整个筛选范围；`activities.items` 每页 50 条，仅逐条比较时继续分页。热力图最多 366 天，不能据此判断更早历史不存在。所有历史对比先核对 `availableRange`，再用明确日期范围与相同口径。

运动 ID 使用返回的完整值（含 `activity-` 前缀），路径片段做 URL 编码。课次使用包含偏移的 RFC3339 `startAt`，日期筛选按个人档案/应用时区口径，遇到不同口径需说明；不要把机器所在时区当成用户时区。
