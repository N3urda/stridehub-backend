# 数据记录与 AI 计划

StrideHub 的职责是保存和展示真实数据。AI 通过已有 API 读取证据、解释变化、在用户授权后下发计划。不要要求用户先逐项填写所有设置；只补问当前决策确实缺失的信息。

## 读取一份完整的数据上下文

服务提供 `/api/v1/health/workspace?from=...&to=...` 时优先读取它（默认最近 30 天、最多 366 天）。它返回个人时区、健康背景、体重趋势与目标、饮食计划与记录、统一运动、课表、反馈与覆盖度；原始传感器仍按来源详情读取。若返回 404，使用下列分项接口兼容旧版本。

统一运动使用 `/api/v1/health/workouts`，确认来源关联后只计一次。`duplicateCandidates` 只是时间/距离候选；待核对时总量为 null，不能把两个来源直接相加。用户授权确认是同次运动时才调用 `PUT /workout-links/{zeppId} {version,activityId}`；训练已占用该云端记录时会返回 409，应先向用户说明关联依赖，不能绕过约束。

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

训练继续使用现有 sessions/batch；作息、恢复和文字待办使用 `/api/v1/health/plans/{stable-id}`。量化饮食、减重目标使用下节专用接口，不重复保存为课次或测量。旧版本的 weight/nutrition 健康计划仍保留读取。先读已有计划，避免重叠或覆盖用户已执行条目。

- 创建带 `version:0`，必填 `title`、`goal`、`startDate`。可选 `endDate`，未确定为 null。
- `category`：`weight/sleep/nutrition/recovery/general`；`status`：`draft/active/paused/completed/cancelled`。
- AI 写入必须 `source:"ai"`，`sourceLabel` 标明助手来源，`rationale` 非空，描述实际数据依据、约束和缺口。不能标成 clinician，除非保存的是用户明确提供的医生计划，且如实标注来源。
- `items` 每条带唯一 id 和 title；可包含 date、time（个人档案时区）、status（pending/completed/skipped）、notes。无明确时间时为 null，不擅自编造精确安排。
- 用户仅要求“分析/给建议”时在对话中给方案。要求“保存/安排/下发”已授权相应写入；不要重复询问笼统确认。status 使用用户要求；方案已授权执行时可 active，尚待决定的草案为 draft。
- 修改时 GET 最新整数版本，数组整体替换，保留用户已完成状态和其他条目。409 后按 API 冲突约定处理。
- 不自动产生诊断、用药或补充剂处方；减重目标、训练容量与营养建议需遵循用户目标、实际数据及有效健康约束。需要医学判断时按健康报告参考核实来源，不把设备估计当作诊断。
- 成功后 GET 单条并核验，再报告计划 ID、版本、起止日期、保存状态与依据；不把未执行计划称为已完成。

系统不会因为保存一条健康计划而自动发送通知、持续调用 AI、推送到手表或完成运动匹配。需要这些能力时先检查已实现接口和用户授权。

## 量化饮食、减重与身体测量

先读健康 OpenAPI 获取字段和可用操作。新资源均在 `/api/v1/health/` 下：

- `meal-plans/{id}`：按 date 保存 targets（energyKcal/proteinGrams/carbohydrateGrams/fatGrams）与 meals（id/mealType/foods/portion）。每天最多一个 active 计划，数组整体替换。
- `meals/{id}`：实际餐食，必填 date/foods；portion 描述份量，nutrients 存营养值。`nutritionSource` 为 unknown/label/database/estimate。只有已知数值才填写；估算明确标 estimate。planId 可选，必须引用同日计划。不要将计划份量当作已经吃下的食物。
- `weight-goals/{id}`：title/startDate/endDate/startWeightKg/targetWeightKg，以及可选 milestones、状态。用户提供的目标进入这里，不进入 weight 实测历史。
- `measurements/{id}`：date、waistCm/bodyFatPercent 至少一项，来源只允许 user/import；只能记录用户提供或设备导入的测量。

这些资源支持 GET 列表/单项、带 version 的 PUT/DELETE、`PUT /{resource}/batch {records:[{id,version,...}]}` 原子批量更新，以及 `GET /{resource}/{id}/history`。稳定 ID 不得为 batch；创建 version:0，更新使用当前整数版本。删除 ID 不可复用；已被实际饮食引用的计划不能删除/改日期。

`actualLogged` 是已记录餐次合计，不等于全天摄入；任一餐缺失该营养值时合计 null，已知小计与覆盖另列。`delta` 是已记录数值减计划目标，不是能量缺口。不要将设备活动热量当作全天消耗。旧 fuel-logs 不重复叠加，实际补给若计入饮食需在 meals 中记录一次。体重七日均值是日历窗口内实测均值，必须连同 sampleDays、测量日期解释。

source=ai 必须给非空 rationale，保留实际依据和数据缺口。写入结果不确定时读取同一个 ID 核验，禁止换 ID 重试。分析内容留在当前聊天；网页用于展示已保存的数据、计划和执行状态。
