# 数据记录与 AI 计划

StrideHub 的职责是保存和展示真实数据。AI 通过已有 API 读取证据、解释变化、在用户授权后下发计划。不要要求用户先逐项填写所有设置；只补问当前决策确实缺失的信息。

## 读取一份完整的数据上下文

1. 先 `GET /api/v1/training/profile` 确定时区，`GET /api/v1/health/context` 获取报告、有效约束与生活偏好。
2. 先读取 `GET /api/v1/health/zepp/workouts?from=...&to=...` 与 `/api/v1/health/zepp/workouts/status`，对需要复盘的运动读取 `/api/v1/health/zepp/workouts/{id}` 中的 `streams`、`sourceSummary`、`sourceDetail`。返回的源快照 ID 可用 `/api/v1/health/zepp/workouts/sources/{id}` 追溯；授权同步使用 `POST /api/v1/health/zepp/workouts/sync {"days":90}`。设备训练负荷/VO₂max 等是来源观测或估计，不是此服务重新算出的医学结论。文件导入与云端可能描述同一次运动，按日期、时间、距离、来源核对，不要直接累加。另可 `GET /api/v1/running/overview?from=all&sportType=all&page=1` 确认真正的可用日期范围。随后读取目标时间段完整汇总，逐条分析时继续分页，并对必要运动调用 `/api/v1/running/activities/{id}` 获取传感器和分段数据。
3. `GET /api/v1/health/zepp/status` 检查最近成功同步、失败及下一步；`GET /api/v1/health/zepp/days?from=...&to=...` 获取逐日睡眠、活动、心率、血氧、压力与身体观察。需要细节时 GET `/api/v1/health/zepp/days/{date}` 获取 `samples`、`sleepStages`、`activityStages`；再按返回的 `rawRecordIds` 调用 `/api/v1/health/zepp/raw/{id}` 追溯源数据。不要从每日平均值假装推导分钟级波动，也不要给未识别的原始字段臆造含义。用户已要求同步时可 `POST /api/v1/health/zepp/sync`，正文 `{"days":30}`（最多 90）；失败后明确记录范围，不假设云端缺失等于用户未佩戴。
4. `GET /api/v1/health/weight?from=...&to=...` 读取确认的体重历史，`GET /api/v1/health/blood-pressure?from=...&to=...` 读取逐次血压，`GET /api/v1/training/check-ins?from=...&to=...` 读取用户主观状态。缺少任一类仍可做不依赖它的复盘。
5. `GET /api/v1/health/plans` 读取作息、减重、营养和恢复计划；再读取 `/api/v1/training/sessions?from=...&to=...`、`/api/v1/training/races` 及实际反馈。判断日程冲突时覆盖计划可能重叠的日期。

每次报告必须区分：设备观察、用户录入、医生意见、AI 推断、待执行计划。`records:[]` 和 `null` 表示未知，不能补成零；`coverage` 描述设备实际提供的字段，不等于健康状态。日汇总和原始采样必须保留来源日期/时区与测量单位。不要把 Zepp 体重与人工体重简单相加或静默覆盖；需要对账时按日期和来源比较。

`lastSuccessAt` 只说明某次同步成功；具体日期仍以 `records`、`lastSyncedAt` 和覆盖字段为准。运动热力图或 API 首屏都不是全量历史。读取失败必须可见，不能用旧值或默认值冒充新数据。

## 保存真实体重

先 GET `/api/v1/health/weight`，保留返回的**字符串** `version`。用户已提供并授权记录实际测量后，PUT `/api/v1/health/weight/{date}`：

```json
{"version":"GET 返回的 64 位字符串","weightKg":72.4,"source":"manual","notes":"用户提供的晨起测量"}
```

这里固定 kg，一天一条；旧数据复用同一份历史。禁止将预测体重、计划目标或 AI 估计写成测量。若用户只给“72”且单位/日期不明确，先完成不依赖该数值的工作，再澄清具体缺失项。

PUT 返回完整列表和新版本。409 表示历史或单位变化，先重新读取比较。不要用 `version:0`，不要绕到旧接口强行覆盖。

## 下发健康计划

训练继续使用现有 sessions/batch；作息、减重、饮食和恢复使用 `/api/v1/health/plans/{stable-id}`，不重复保存为课次。先读已有计划，避免重叠或覆盖用户已执行条目。

- 创建带 `version:0`，必填 `title`、`goal`、`startDate`。可选 `endDate`，未确定为 null。
- `category`：`weight/sleep/nutrition/recovery/general`；`status`：`draft/active/paused/completed/cancelled`。
- AI 写入必须 `source:"ai"`，`sourceLabel` 标明助手来源，`rationale` 非空，描述实际数据依据、约束和缺口。不能标成 clinician，除非保存的是用户明确提供的医生计划，且如实标注来源。
- `items` 每条带唯一 id 和 title；可包含 date、time（个人档案时区）、status（pending/completed/skipped）、notes。无明确时间时为 null，不擅自编造精确安排。
- 用户仅要求“分析/给建议”时在对话中给方案。要求“保存/安排/下发”已授权相应写入；不要重复询问笼统确认。status 使用用户要求；方案已授权执行时可 active，尚待决定的草案为 draft。
- 修改时 GET 最新整数版本，数组整体替换，保留用户已完成状态和其他条目。409 后按 API 冲突约定处理。
- 不自动产生诊断、用药或补充剂处方；减重目标、训练容量与营养建议需遵循用户目标、实际数据及有效健康约束。需要医学判断时按健康报告参考核实来源，不把设备估计当作诊断。
- 成功后 GET 单条并核验，再报告计划 ID、版本、起止日期、保存状态与依据；不把未执行计划称为已完成。

系统不会因为保存一条健康计划而自动发送通知、持续调用 AI、推送到手表或完成运动匹配。需要这些能力时先检查已实现接口和用户授权。
