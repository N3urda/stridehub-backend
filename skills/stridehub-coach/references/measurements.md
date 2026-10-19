# 血压与 Zepp 睡眠

网页入口为 `/admin/health`；API 定义在 `/api/v1/health/openapi.json`。数据与体检摘要分开保存，使用它们前读取明确日期范围，并确认时间和来源。体检里写了一次血压，不代表用户要求将它另建成家庭测量；避免重复录入同一次观察。

## 血压记录

`GET /api/v1/health/blood-pressure?from=YYYY-MM-DD&to=YYYY-MM-DD` 返回 `{"records":[...]}`，按实际测量时间倒序。日期边界按训练个人档案时区解释，包含首尾日期；不传日期则不限制范围。同一天可有多次测量，不能只用日期作为 ID。

新测量用稳定 ID 调用 `PUT /api/v1/health/blood-pressure/{id}`，正文包含 `version:0`、实际 `measuredAt`、`systolic` 和 `diastolic`。时间必须写到秒并包含时区偏移，例如 `2026-10-19T07:30:00+08:00`；不能把录入时间冒充测量时间。血压单位为 mmHg，脉搏为 bpm；不同单位应先核对转换依据，不能直接填入。

可选字段为 `pulse:null`、`arm:"unknown"`（unknown/left/right）、`posture:"unknown"`（unknown/seated/standing/lying）和 `notes:""`。缺少脉搏、手臂或姿势时保持未知，不猜测。血压与脉搏必须是 1–400 的整数，收缩压必须大于舒张压；这些仅为输入校验边界，不是医学正常范围。备注最多 2000 字符，单次请求不超过 16 KiB。

编辑前 GET 单项，带当前 `version` 仅提交需要改变的字段；`pulse:null` 明确清除脉搏。删除用 `DELETE /api/v1/health/blood-pressure/{id}?version=N`。网络结果不确定时先读取该 ID；相同 `version:0` 创建只在记录仍为第一版且完整内容相同时可安全重放，已编辑后不能依赖创建重放。遵守 API 参考中的冲突处理，不新增 ID 绕过冲突。

复盘保留单次时间、手臂、姿势与备注；不同条件的读数不可悄悄合并为同一可比趋势。服务端不诊断、不设个体训练阈值，不自动改药或改课表。实际医学解读时核对当前可靠来源，并将医生明确意见与 AI 解释分开。

## 设备睡眠与人工状态

`GET /api/v1/health/sleep?from=YYYY-MM-DD&to=YYYY-MM-DD` 读取 Zepp 日记录，列表字段为 `records`。`sourceDate` 是上游原始日记录日期，`date` 在来源时区和结束时间已知时为醒来日期，否则沿用 `sourceDate`；筛选比较 `date`。保留开始/结束时间与 `timeZone`；不要因当前机器时区不同而把同一晚睡眠挪到另一日。当前每个来源日期只保留一条摘要，不是所有午睡片段明细。

可用字段包括 `totalMinutes`、`deepMinutes`、`lightMinutes`、`remMinutes`、`awakeMinutes`、`score`、`restingHeartRate` 和 `wakeCount`；具体是否存在由上游设备及账号返回决定。未知值为 `null`，不能补为 0，也不能因为没返回某个睡眠分期就推导出剩余分期。服务器的 `totalMinutes` 由完整深睡、浅睡和 REM 分期求和；上游明确不支持 REM 时用深睡加浅睡，REM 仍为 null；其余分期缺失时总睡眠为 null。不将床上区间长度或自行求和的部分分期当作完整总睡眠。

Zepp 记录与 `/api/v1/training/check-ins` 中人工填写的 `sleepHours` 独立。设备时间与用户体感冲突时分别说明来源、日期和数值，不覆盖人工记录或偷偷取平均。消费级穿戴设备记录不代表临床睡眠诊断，不据此自动给出“恢复良好”、健康许可或精确训练剂量。

`training/today` 和课次 briefing 的 `observations` 分别包含对应日期的 `bloodPressure`、`sleep` 与同步状态 `sleepSync`，与人工 `checkIn` 分开。未来课次缺少当日观察是正常状态，不能把今日/最近一条观察改标成未来日期。需要趋势时另读明确日期范围，不能仅凭这个单日摘要判断长期变化。

## 同步与失败恢复

先读 `GET /api/v1/health/sleep/status`。只有用户要求或已授权同步时，调用 `POST /api/v1/health/sleep/sync`，正文 `{"days":30}`；天数为 1–90 的整数，空对象默认 30 天，正文限 4 KiB。按来源账号和 `sourceDate` 更新同一记录，重复同步不复制夜晚；这不是全历史导入，分析更早区间前应核对实际覆盖。服务端拒绝混入不同账号，不能通过删除凭据或历史记录绕过这一保护。

同步状态区分未配置、空数据、登录过期、进行中和成功/失败。GET status 与 POST sync 使用相同顶层状态结构，含 `imported`、`updated`、`unchanged`、`skipped`。POST 的 503 正文仍为状态对象；此处的 `auth_required` 指 Zepp 授权，不能当作 StrideHub Bearer 401。并发同步返回 409，应查看状态。`configured:true` 只表示授权文件存在，不能证明授权有效。

展示 `lastSuccessAt` 与 `lastError` / `nextAction`，不能把本次失败说成“今天没睡觉”，也不能把存在旧记录说成同步正常。`lastSuccessAt` 包含云端成功返回但无有效睡眠的情况，单条记录的 `lastSyncedAt` 只随内容变化更新。Zepp 登录过期需由用户或已授权的本地配置流程重新接入；不要向浏览器或接口提交密码、读取后展示凭据文件，或绕过登录限制。

同步不改写人工 check-in、不自动更新课表，也不意味着已启用后台任务。仅在实际核验已部署和运行的调度后才能说会自动同步；Skill 本身只在调用时执行。
