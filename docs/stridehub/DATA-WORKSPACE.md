# 数据工作台：页面、字段与 AI 接口

页面展示指标、趋势、计划和实际记录。分析与问答由外部 AI 聊天工具完成；AI 使用同一组 HTTP API 读取事实和保存用户授权的计划。本次没有新增站内聊天或自动规划任务。

## 页面与数据

| 页面 | 展示 | 数据来源 |
|---|---|---|
| 总览 `/admin/insights` | 最近体重、当前减重目标、今日已记录摄入、期间训练完成数，以及已有睡眠、身体信号与运动统计 | `workspace`、`workouts`、既有健康接口 |
| 身体 `/admin/health` | 睡眠、血压、心率、实测体重与来源明细 | 既有测量与 Zepp 数据 |
| 运动 `/admin/running` | 统一运动次数/距离/时长/心率、周趋势、来源核对；下方保留各来源的传感器曲线与导入分析 | `workouts`，Zepp/Running 详情 |
| 计划 `/admin/plans` | 默认七天训练日历，可选最多 93 天；训练类型、时间、距离、分段目标、状态与 RPE | `/training/sessions`，既有健康待办 |
| 饮食 `/admin/nutrition` | 当日目标与已记录摄入、能量趋势、餐食计划、实际餐食、逐日对照和修改历史 | `meal-plans`、`meals`、`workspace` |
| 减重 `/admin/weight` | 实测体重、七日均值、目标与阶段目标、进度、腰围/体脂和修改历史 | 既有 `weight`、`weight-goals`、`measurements` |

页面可新增、编辑、删除实际饮食、减重目标和身体测量。餐食计划由外部 AI/API 保存；已有训练编辑器继续管理训练课表。用户未提供的计划、营养数值和身体测量都保持未知。

## 新增结构化记录

前缀为 `/api/v1/health`，Bearer 鉴权。网页对应 `/admin/health/api`，写入需管理员会话及 health CSRF。响应 `no-store, private`，正文最多 1 MiB。

| 资源 | 必填创建字段 | 其他主要字段 |
|---|---|---|
| `meal-plans` | `date` | `status`（draft/active/cancelled）、`targets`、`meals` |
| `meals` | `date`、`foods` | `mealType`、`portion`、`nutrients`、`nutritionSource`、同日 `planId` |
| `weight-goals` | `title`、`startDate`、`endDate`、`startWeightKg`、`targetWeightKg` | `status`、`milestones[{date,weightKg}]` |
| `measurements` | `date` 及至少一个测量值 | `waistCm`、`bodyFatPercent` |

共同字段为 `source`（user/ai/import）、`sourceLabel`、`rationale`、`notes`；AI 写入必须提供非空 rationale。身体测量仅允许 user/import，目标不能当作测量。

- `GET /{resource}?from=...&to=...` 返回 `{records:[...]}`。减重目标按日期重叠筛选。
- `GET /{resource}/{id}` 读取当前记录。
- `PUT /{resource}/{id}` 创建带 `version:0`，更新带当前版本。未提供的顶层字段保留；嵌套对象和数组整体替换。营养对象中省略的营养素变为 null。
- `PUT /{resource}/batch` 接收 `{records:[{id,version,...}]}`，1–100 项，原子提交；任一校验、引用或版本冲突会回滚整批及其历史。
- `DELETE /{resource}/{id}?version=N` 返回 204。
- `GET /{resource}/{id}/history` 按记录版本倒序返回保存/删除快照，包含操作及保存时间。删除后仍能查询历史。

稳定 ID 为 1–80 位字母、数字、短横线或下划线，`batch` 为保留值。相同 `version:0` 创建只在当前仍为第一版时支持重放；已删除 ID 不可复用。修改必须先读取；409 后对比最新内容，不能只换版本号重发。来源为调用方声明，不代表独立的调用者身份认证。

一天最多一个 active 饮食计划。关联了实际饮食的计划不能删除或改日期；先显式解除实际记录的 planId。目标结束日期晚于开始日期、目标体重低于起始体重，阶段日期唯一且在目标期间内；这是模型约束，不是自动制定的减重建议。

### 饮食示例（仅演示接口）

```json
{
  "version": 0,
  "date": "2026-10-20",
  "source": "ai",
  "sourceLabel": "用户授权的助手",
  "rationale": "按用户已确定的餐食安排保存，营养目标尚未提供。",
  "targets": {"energyKcal": null, "proteinGrams": null},
  "meals": [{"id": "breakfast", "mealType": "breakfast", "foods": "用户指定的早餐", "portion": "用户指定份量"}]
}
```

营养单位固定为 kcal / g，字段为 `energyKcal`、`proteinGrams`、`carbohydrateGrams`、`fatGrams`。实际餐食的 `nutritionSource` 为 unknown/label/database/estimate；填写数值时不能为 unknown。估算不可标为标签或实测。

`workspace.nutrition.days[].actualLogged` 只代表已记录餐次，不能证明全天饮食完整。只要一餐缺少某项营养值，该项合计就是 null；`knownSubtotal` 和 `coverage` 单独给出已知部分。零值保留为真实零，不补造缺失项。`delta` 为已记录合计减计划目标，不是能量缺口。旧 `fuel-logs` 不额外叠加，实际运动补给需要在 meals 中以 mealType=fuel 记录一次才计入饮食总量。

## 共用上下文与统计

`GET /api/v1/health/workspace?from=YYYY-MM-DD&to=YYYY-MM-DD`

默认最近 30 天，最多 366 天。返回范围、个人时区、生成时间、个人偏好、健康背景、睡眠、设备日数据、血压、身体测量、体重/目标、饮食/计划、统一运动、训练课次、主观反馈、健康待办及数据覆盖。此读取不触发同步、分析或通知。原始分钟采样按来源详情另取，避免上下文膨胀。

七日体重均值按当前测量日期及此前六个日历日计算，只使用实测值；`sampleDays` 是实际测量天数，可包含查询开始日前的样本。体重查询和目标进度截至 `min(to,today)`；不会让未来填写的体重成为当前状态。目标进度以目标开始日起最近实测计算，保留测量日期，不自动标为完成。图中阶段点为计划值，七日均值为计算值。

## 统一运动身份

`GET /api/v1/health/workouts?from=...&to=...` 返回统一记录、周统计、来源、别名及待核对重复。日期按训练个人档案时区。现有 Running 深度分析仍使用导入文件的流；Zepp 深度曲线仍使用云端来源，不将两者原始采样拼接。

仅时间/距离相近会产生 `duplicateCandidates`，不能证明是同一次运动。存在候选时累计数值为 null；记录仍可查看。无候选也不证明已排除所有重复，跨来源疑似相同记录应核对原始详情。

- `PUT /workout-links/{zeppId}`：`{version:0,activityId:"activity-..."}` 确认相同运动。一个导入记录最多对应一条 Zepp 来源。
- `GET /workout-links/{zeppId}`：读取关联与版本。
- `DELETE /workout-links/{zeppId}?version=N`：拆分关联。

合并只记录来源关联，不修改/删除源记录。确认后以导入记录统计，同一运动只计一次。若导入记录被移除，云端来源仍保留可见。云端记录已关联课表时须先解除课表关联，再合并来源并重新关联；拆分时两种 ID 均不能仍被课表引用。

训练活动查询、匹配及关联已支持 Zepp 的跑步/越野跑/跑步机记录。确认合并后，传入任一别名都会保存为导入记录 ID；同次运动不同别名不能重复占用课次。云端缺少距离或时长时保持 null，计划差值也保持未知。其他运动类型可以查看，当前课表仍采用跑步模型。

## 存储与上线

复用 `TrainingRecord` 保存新记录、历史和来源关联，体重继续复用已有 `Setting.weightHistory`；没有新增数据库迁移。部署新版代码后重启应用以加载路由/服务。后台同步与既有 daemon 配置保持原样，本功能不启用后台 AI。

测试使用独立数据库与 DEMO 记录。详细验收命令及结果见 `VALIDATION.md`。
