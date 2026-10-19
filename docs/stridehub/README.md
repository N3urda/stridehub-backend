# StrideHub 运动健康管理

以 HTTP API + Skill 为 AI 接口，让人和 AI 共同使用运动记录、健康背景和训练课表。在 Dreeve 的运动数据基础上，管理未来课表、比赛目标、训练前天气与穿衣建议、身体状态、跑后反馈及补给演练。项目不提供 MCP 服务。

Android 客户端源码位于 [`android/`](../../android/README.md)。安装 APK 后填写同一后端的 URL、管理员用户名和密码，即可在手机上使用今日安排、课表、运动分析与文件导入；连接页和导航为原生界面，数据管理复用现有移动网页。

## 启用

1. 安装依赖，执行 `bin/console doctrine:migrations:migrate --no-interaction`，重启应用与 daemon。
2. 使用已有管理员账号打开 `/admin/training`。填写跑步地点、IANA 时区、固定跑步日和冷热偏好。
3. 创建比赛目标和训练课次。课次的开始时间必须包含 UTC 偏移，例如 `2026-10-04T06:30:00+08:00`；课次可以包含热身/主训练/放松步骤及补给计划。
4. 训练前查看简报；导入真实运动后，查看匹配建议并关联记录。系统不会把匹配候选自动当作已经完成的课次。
5. 记录睡眠感受、疲劳、酸痛和跑后冷热感受。没有心率、睡眠或 HRV 数据时，不会虚构生理恢复评分。

天气来自 Open-Meteo，按训练时段查询并标注更新时间。未来七天以外、接口失败、坐标未设置或数据不完整时会明确提示。附近时段的建议只使用实际取得的预报。穿衣规则是可解释的个人参考；反馈用于调整建议，不会自动修改课表。

## 给 Codex 使用的 HTTP API

基础路径：`/api/v1/training`。所有接口包括 OpenAPI 文档都使用现有 Dreeve Bearer 鉴权。管理员界面的 API Key 设置可以生成密钥；把它写入服务器的 `DREEVE_API_KEY`，重启服务后生效。轮换时替换该配置，旧密钥随之失效。密钥只放环境变量，不能提交到 Git，也不需要填入网页。

OpenAPI：`GET /api/v1/training/openapi.json`；源文件为本目录下 `openapi.json`。

```bash
# STRIDEHUB_URL 是你部署的 HTTPS 地址，DREEVE_API_KEY 从安全环境注入。
curl --fail-with-body "$STRIDEHUB_URL/api/v1/training/openapi.json" \
  -H "Authorization: Bearer $DREEVE_API_KEY"

curl --fail-with-body "$STRIDEHUB_URL/api/v1/training/sessions?from=2026-10-01&to=2026-10-07" \
  -H "Authorization: Bearer $DREEVE_API_KEY"
```

创建一节课（示例训练仅用于展示接口）：

```bash
curl --fail-with-body -X POST "$STRIDEHUB_URL/api/v1/training/sessions" \
  -H "Authorization: Bearer $DREEVE_API_KEY" -H 'Content-Type: application/json' \
  --data '{"id":"week-01-easy","title":"轻松跑","startAt":"2026-10-04T06:30:00+08:00","durationMinutes":45,"type":"easy","steps":[{"kind":"easy","minutes":45,"target":"轻松体感"}]}'
```

批量写课表：`PUT /sessions/batch`，请求为 `{"sessions":[...]}`，最多 100 节。新课次指定 `id` 和 `version:0`；已有课次必须带最新版本号。整个请求原子执行，任何一条失败都会回滚，避免半周课表写入成功。

```bash
curl --fail-with-body -X PUT "$STRIDEHUB_URL/api/v1/training/sessions/batch" \
  -H "Authorization: Bearer $DREEVE_API_KEY" -H 'Content-Type: application/json' \
  --data '{"sessions":[{"id":"week-01-easy","version":1,"startAt":"2026-10-05T06:30:00+08:00"},{"id":"week-01-long","version":0,"title":"长距离","startAt":"2026-10-11T06:30:00+08:00","durationMinutes":90,"type":"long"}]}'
```

资源列表返回 `{"items":[...]}`，单项返回记录本身。记录包含 `id`、`version`、`updatedAt`。PUT 为部分更新，未提供字段保留原值。DELETE 要求 `?version=N`。

| 接口 | 用途 |
|---|---|
| `GET/PUT /profile` | 跑步地点、习惯、冷热偏好和提醒设置 |
| `GET/POST /sessions` | 查询、创建课表 |
| `GET/PUT/DELETE /sessions/{id}` | 查看、编辑、改期、取消和删除课次 |
| `PUT /sessions/batch` | 原子批量创建或调整课表 |
| `GET/POST /races`、`GET/PUT/DELETE /races/{id}` | 比赛日期、距离和目标成绩 |
| `GET/POST /check-ins`、`GET/PUT/DELETE /check-ins/{id}` | 每日身体状态；每日只能一条 |
| `GET/POST /fuel-logs`、`GET/PUT/DELETE /fuel-logs/{id}` | 补给时间、数量和胃肠感受 |
| `GET /activities` | 可关联的真实跑步记录 |
| `GET /sessions/{id}/comparison` | 计划/实际差异、匹配候选和调整建议 |
| `POST /sessions/{id}/link` | 以 `{version,activityId}` 关联运动并标记完成 |
| `GET /briefing?sessionId=...` | 指定课次或下一次跑步的天气、穿衣和训练建议 |
| `GET /today` | 跑者时区的今日课表、身体状态、适用健康约束及待反馈/对账事项 |
| `GET /reconciliation?from=...&to=...` | 查询待对账课次及匹配候选，只读、不自动标记完成 |
| `PUT /sessions/{id}/feedback` | 用当前 version 部分合并跑后反馈和补给汇总 |

401 表示缺失或无效凭据；403 表示网页请求缺少 CSRF；422 表示字段或引用无效；428 表示缺少版本号；409 表示版本冲突、重复 ID 或资源仍被引用；404 表示资源不存在。API 不会在冲突后强行覆盖最新内容。

### 建议交给 Codex 的工作方式

读取 OpenAPI、个人偏好、比赛目标和已有课表，按用户要求生成或调整训练。先查询最新版本；批量更新时保留不属于本次要求的课次和字段。收到 409 后重新读取变化，向用户说明冲突，不要简单替换版本号后覆盖。写入后再次读取受影响日期核验；不要把天气建议自动变成课表修改。重复 POST 同一个 ID 会冲突，网络结果不确定时先 GET 确认。

## 手机今日安排与运动对账

打开 `/admin/training/today`，按跑步设置中的时区查看今天的课表，选择具体课次读取天气与穿衣建议。身体状态可以只填已知项目；未填写的疲劳、酸痛、疼痛保持未知。跑后快速填写主观强度、冷热体感、疼痛和实际补给汇总，不必进入完整课表编辑器。保存反馈不会自动生成运动记录或把计划标为完成。

待对账列表默认检查今天及此前七个本地日的已结束课次。候选搜索覆盖计划当天及开始时间前后六小时的相邻时段，能发现延后数小时或跨午夜的跑步。建议标明日期与时间差；同一天有多次跑步时仍须核对，候选不会默认勾选或自动关联。已经关联的课次和提前完成的课次也可以从今日卡片打开对账。

一节课可关联最多 20 条运动，例如热身、主课和放松。确认时发送 `POST /sessions/{id}/link`，内容为 `{"version":1,"activityIds":["activity-warmup","activity-main"]}`，整体替换这节课的关联列表；非空关联会标记完成。每条运动只能属于一节课，任一占用、缺失或版本冲突都会拒绝整次修改。空列表解除关联但保留课次状态，需要时另行调整状态。旧 `activityId` 字段保持单条关联兼容，显式写它会替换整个关联列表。

对账汇总运动距离与移动时长，不把两条记录之间的休息间隔计为跑步；心率仅按有测量值的移动时长加权。展示每条来源，缺失已关联记录时不输出完整差值。汇总距离/时长对比不能证明间歇的每个分段都达标。

快速反馈格式为 `{"version":2,"feedback":{"rpe":4,"pain":null,"fuel":{"fluidMl":250}}}`。此专用接口只合并提供的字段，补给对象也按字段合并；明确的 `null` 表示未知/清除。补给汇总和逐分钟 `fuel-logs` 是两个不同层次，不重复累计。一般的课次 PUT 仍保持原来的嵌套整体替换语义。

页面在刷新数据、切换课次和写入冲突时保留本页未保存草稿；草稿不会写入浏览器持久存储，关闭或重新加载页面前会提示。天气读取失败不影响状态回填和对账。简报同时列出本课次日期适用的健康约束，要求先核对约束，不将天气适宜当成健康许可。

本机地址 `http://localhost:8081/admin/training/today` 仅供当前电脑访问。手机实际访问需要将同一应用部署到你可访问的 HTTPS 地址；页面适配手机不等于当前本地服务已向手机开放。

## 用 Skill 管理运动与健康

仓库提供 [`stridehub-coach`](../../skills/stridehub-coach/SKILL.md)。它通过上述 HTTP API 工作，支持运动复盘、课表调整、体检摘要整理、血压记录和 Zepp 睡眠复盘，不需要 MCP。运行客户端只需 Python 3 标准库。

将 `skills/stridehub-coach` 放入 Codex 的技能目录。本机已安装为指向本仓库目录的链接，连接配置位于 `~/.config/stridehub/connection.json`，只保存服务地址和本地凭据文件路径，凭据本身不进入 Skill 或 Git。其他机器可复制技能目录并创建自己的配置：

```json
{
  "baseUrl": "https://your-stridehub.example",
  "credentialsFile": "/private/path/credentials.json"
}
```

凭据文件格式为 `{"apiKey":"你的实例密钥"}`，也可安全注入 `STRIDEHUB_URL` 与 `DREEVE_API_KEY`。两类配置文件均应只允许当前用户读取。客户端仅接受训练、跑步和健康 API 路径，拒绝跳转，远程连接要求 HTTPS；写入失败不会自动重试。

可以对 Codex 说：

> 使用 $stridehub-coach，复盘最近四周训练，结合我的健康约束和周末天气调整下周课表，保留周三休息。

> 使用 $stridehub-coach，整理这份体检报告并更新健康背景，保留旧报告，再说明对日常作息和训练安排有什么影响。

Skill 先读取最新数据，按已有授权执行修改；仅要求分析时不会保存课表。它按每次调用执行，未启动持续后台规划。没有真实运动或报告时会明确缺失，不能把空数据当作健康或恢复良好。

### 结构化健康背景 API

`GET/PUT /api/v1/health/context` 与 `GET /api/v1/health/openapi.json` 使用相同 Bearer 鉴权，所有健康响应禁止缓存。源文档为本目录 `health-openapi.json`。

健康背景包含 `reports`、`constraints`、`lifestylePreferences` 和 `notes`。报告分别保存原始结果 `findings`、医生意见 `clinicianAdvice` 与 AI 解读 `aiInterpretation`，同时保留日期、单位、参考范围、来源及提取待核对状态 `needsReview`。约束保留来源、关联报告、生效/复查日期和状态。

第一次读取返回 `version:0` 的空背景；PUT 必须带当前版本。省略的顶层字段保留，提供的数组整体替换，因此追加报告时应先读旧数组再合并。最多 30 份报告、合计 1000 项结果、50 条约束和 30 条生活偏好；重复 ID、未知字段及失效报告引用均拒绝，过期版本返回 409。新接口复用现有 TrainingRecord 表，无需新增迁移。

报告 API 只保存结构化摘要，不接收 PDF/图片原件，也不核验医学结论。体检摘要仍由 Skill/API 管理，没有报告网页编辑器；血压与设备睡眠另有下述健康页面。实际解读应结合当前症状、原报告参考范围和医生意见；报告异常不自动换算成训练强度，复查日期经过不等于医嘱自动解除。服务器不会根据文本约束自动拦截所有课表修改，Skill 在排课前主动读取并考虑它们。完整报告和私人数据不要提交到本公开仓库。

### 血压记录与设备睡眠

健康页面为 `/admin/health`，从「今日安排」进入，现有 APK 连接同一新版后端即可访问。体重历史仍在「设置 → 常规」管理。血压、设备睡眠、人工每日状态与体检摘要各自保留来源，既能一起查看，也不会相互覆盖。

今日接口与课次简报中的 `observations` 单独返回 `bloodPressure`、`sleep` 和 `sleepSync`：血压只取对应本地日期内且测量时间不晚于当前时刻的最新记录，睡眠取该来源日期的记录。未来课次不会套用今天的血压或昨晚睡眠；没有对应记录就是 null。人工 `checkIn` 原样保留，健康观察不会自动形成新的健康评分或训练强度建议。

血压支持同一天多次测量，保存测量时间、收缩压/舒张压（mmHg）、可选脉搏（bpm）、手臂、姿势和备注。时间必须含时区偏移，手臂和姿势允许未知；记录和图表用于查看变化，不产生诊断或自动调整课表。接口提供与手机页面相同的数据：

| 接口 | 用途 |
|---|---|
| `GET /api/v1/health/blood-pressure?from=YYYY-MM-DD&to=YYYY-MM-DD` | 按测量时间倒序列出 `records`；日期按训练个人档案时区，包含首尾边界 |
| `GET /api/v1/health/blood-pressure/{id}` | 读取一条记录及当前版本 |
| `PUT /api/v1/health/blood-pressure/{id}` | 稳定 ID 创建（`version:0`）或带当前版本局部更新 |
| `DELETE /api/v1/health/blood-pressure/{id}?version=N` | 带当前版本删除，成功返回 204 |
| `GET /api/v1/health/sleep?from=YYYY-MM-DD&to=YYYY-MM-DD` | 查询独立的 Zepp 睡眠日记录，返回 `records` |
| `GET /api/v1/health/sleep/status` | 查看同步配置、最近成功时间和失败恢复提示 |
| `POST /api/v1/health/sleep/sync` | 正文 `{"days":30}`，同步最近 1–90 天 |

血压 PUT 创建必须提供 `measuredAt`、`systolic` 和 `diastolic`；可选 `pulse` 默认为 null、`arm`/`posture` 默认为 unknown、`notes` 默认为空文本。收缩压须大于舒张压，三种数值仅接受 1–400 的整数，这是输入校验边界，并非正常值定义。单条血压请求限 16 KiB。读取最新版本后修改；同 ID、相同完整创建内容、`version:0` 仅在记录尚为第一版时可重复提交而不新增记录。已编辑或内容不同的 ID 返回冲突，不能通过换 ID 盲目重试。

Zepp 睡眠保留原始 `sourceDate`、开始/结束时间、来源时区、深睡/浅睡/REM/清醒分钟数、评分、静息心率和醒来次数。列表的 `date` 是来源时区的醒来日期；来源时区或结束时间未知时沿用 `sourceDate`。总睡眠 `totalMinutes` 由完整的深睡、浅睡、REM 分期求和；上游明确不支持 REM 时仅求和深睡与浅睡且 `remMinutes:null`，其余分期缺失时总时长保持 null。不会用躺床时间代替实际睡眠。当前保留每个来源日期一条摘要，多设备返回同日时选择较完整/较长记录，未导入所有午睡片段明细。

上游未提供的指标为 null，不补零。设备睡眠与手填 `check-ins.sleepHours` 独立保存，也不是临床诊断。同步按账号和原始来源日期更新同一条记录，内容未变时不增加版本，也不改变 `lastSyncedAt`；每次成功读取云端的时间看状态中的 `lastSuccessAt`。失败或无有效睡眠不会删除历史，账号不一致会拒绝继续同步，防止混入其他人的健康数据。

### 配置 Zepp 睡眠同步

睡眠同步直接使用 Zepp 云端摘要接口，与将跑步转换为 FIT 的 Zepp Connector 独立。手表应先同步到 Zepp 手机 App。服务器默认读取私有 `storage/zepp/credentials.json`，可通过 `ZEPP_CREDENTIALS_FILE` 指向其他路径；文件只允许所有者访问（0600）。凭据不经过网页、健康 API 响应或日志。

本机已有授权的 amazfit-analysis 项目时，可由配置工具读取该项目保存的登录信息及 macOS Keychain 令牌，并私密写入服务器文件：

```bash
python3 tools/zepp-sleep-credentials.py --from-amazfit-project /absolute/path/to/amazfit-analysis
```

其他环境执行 `python3 tools/zepp-sleep-credentials.py`，在本机交互输入用户 ID、对应区域的 API 主机和隐藏输入的 app token；`--output /private/path/credentials.json` 可选择文件位置。该工具导入已有授权，不是 Zepp 密码登录器。Docker 部署需确保私有文件在容器内可读且不会进入镜像/Git；改用其他路径时同步设置容器中的 `ZEPP_CREDENTIALS_FILE`。更换账号前需要单独迁移或清理对应睡眠数据，工具不会直接覆盖不同账号。

```bash
# 首次读取近期历史；仅输出同步状态和计数，不输出测量值或凭据。
bin/console app:health:sleep:sync --days=90
```

服务 daemon 运行且凭据文件已配置时，每小时第 15 分钟执行近期 30 天同步；未配置时跳过，不访问 Zepp。部署更新后需重启 daemon 载入新任务。本地开发可用现有 daemon profile 启动：

```bash
docker compose -f compose.stridehub-dev.yml --profile daemon up -d daemon
```

健康页面也可手动同步；同步请求为 JSON 对象，`{}` 默认 30 天，最大 90 天，正文限 4 KiB。同一时间只执行一次，重复并发请求返回 409。`GET /sleep/status` 仅查看状态，不访问 Zepp；`configured:true` 只表示文件存在，授权是否有效仍以同步结果为准。POST 成功或无数据返回 200；未配置、Zepp 登录过期或云端失败返回 503，正文仍是同步状态，包含 `lastError` 和 `nextAction`。不要把此处的 `auth_required` 当成 StrideHub API 密钥过期，也不要把同步失败当作没有睡眠。过期 Zepp 授权需重新登录来源工具后再运行私密配置工具，不能保证永久免登录。

## 自动提醒

在 Dreeve 的 Settings → Integrations 配置通知渠道，在训练助手的个人偏好中打开提醒，再在 Settings → Daemon 启用跑步训练提醒任务。修改 daemon 配置后重启 daemon。

任务涵盖前一晚简报、出发前天气检查、建议明显变化后的更新和完成训练后的反馈提醒。每个通知渠道独立记录投递结果；成功渠道不会因另一个渠道失败而重复投递。超过有效时间的提醒不会补发。没有通知渠道时只显示待配置状态，不假报发送成功。

```bash
# 预览本次将处理的提醒，不发送消息。
bin/console app:training:remind --dry-run
```

身体状态与补给记录属于个人数据，训练页面始终要求管理员登录。API 使用全实例级 Dreeve 密钥，不是多租户或细粒度权限系统；通过 HTTPS 或受控的私人网络访问。

## 跑步分析看板

从“运动记录 → 跑步分析”进入 `/admin/running`，或在单次跑步详情点击“跑步深度复盘”。训练工作台也有入口。

- 长期总览：周跑量与次数、月跑量、最长单次与训练时长、周配速/心率趋势、跑步日历、周期最佳片段。
- 单次复盘：配速、心率、步频、海拔、功率联动曲线，支持时间/距离横轴与拖动缩放；公里分段、GAP、圈记录、心率区间与 bpm 分布、配速—心率散点。
- 记录列表每页 50 条，可导出本页 CSV。所有总览统计包含筛选范围内的全部跑步，不受分页限制。热力图展示范围末尾最多 366 天。
- 没有流数据时只显示运动摘要；没有心率时不会画成 0 bpm。不会推算触地时间、垂直振幅、HRV、VO₂max 或伤病风险。

总览配速使用有效运动的总时长除以总距离，心率按有心率记录的运动时长加权。单次流分析使用相邻时间戳的间隔作为权重，排除超过 30 秒的记录断档、显式暂停、零速度和异常值；最后一个采样点不外推时长。曲线抽样用于显示，计算仍用全部有效区间。现有导入器可能填充缺项，覆盖率表示“导入数据覆盖”，不能证明传感器全程都在测量。

分段配速变异系数使用 0.95–1.05 km 的完整公里分段。后半程配速变化按照等距离切半，跨中点分段按比例分配时长，负值表示后程更快。前后半程效率为平均速度/平均心率的相对变化，仅在至少 20 分钟、两半各有至少 90% 配速/心率数据且全程速度变异不超过 15% 时显示。它没有排除热身、坡度或温度影响，不是标准化有氧能力测试。步频沿用 Dreeve 原始周期数 × 2 的口径。

只读分析 API 同样使用 `DREEVE_API_KEY`：

| 接口 | 用途 |
|---|---|
| `GET /api/v1/running/openapi.json` | 完整机器可读接口定义 |
| `GET /api/v1/running/overview?from=all&sportType=all&page=1` | 全部历史汇总及分页记录 |
| `GET /api/v1/running/overview?from=2026-09-01&to=2026-09-30&sportType=Run` | 按应用时区日期和跑步类型筛选 |
| `GET /api/v1/running/activities/{id}` | 真实运动摘要、流分析、公里分段、圈和数据覆盖 |

`id` 使用总览返回的完整值（包含 `activity-` 前缀）。支持 `Run`、`TrailRun`、`VirtualRun` 和 `all`。应用 `TZ` 决定导入文件时间及显示偏移；跨时区的旧 Strava 本地时间记录需结合原记录核对。浏览器使用管理员会话的 `/admin/running/api/*`，不传 Bearer 密钥。

## Zepp / Amazfit 数据

项目原生支持 FIT、TCX、GPX 文件；[Dreeve Zepp Connector](https://docs.dreeve.app/integrations/zepp-connector/) 是独立容器，将 Zepp 云端数据转换为 FIT 并写入 watch 目录。它使用非官方 Zepp 接口；能展示哪些传感器指标取决于实际同步到的内容。运动导入需要单独配置连接器授权；本文新增的睡眠授权不自动启用跑步导入，也不会将演示数据当作你的跑步记录。

## 此目录的本地开发

本机已经准备好 PHP 8.5 容器运行环境和 Composer 依赖。`.env.local`、`vendor/`、`var/` 与数据库均不提交 Git。`var/runtime/credentials.json` 保存此开发实例的管理员登录信息和 API Key，请只在本机读取。若为新检出，请先安装 PHP 8.5 和 composer.lock 锁定依赖，按 Dreeve 文档填写 `.env.local`（管理员、APP_SECRET、APP_URL、TZ、数据库目录和 API Key）。

```bash
docker compose -f compose.stridehub-dev.yml up -d app
docker compose -f compose.stridehub-dev.yml exec app php bin/console doctrine:migrations:migrate --no-interaction
# 新增路由/服务后清理容器缓存：
docker compose -f compose.stridehub-dev.yml exec app php bin/console cache:clear --no-warmup
# 配置通知渠道、个人提醒和 Daemon 任务后，启动调度器：
docker compose -f compose.stridehub-dev.yml --profile daemon up -d daemon
```

访问 `http://localhost:8081/admin/training` 或 `http://localhost:8081/admin/running`。此 compose 为仅绑定本机的源码开发环境，正式部署沿用 Dreeve 镜像构建流程和 HTTPS 配置。天气实际发送的是训练地点坐标；通知只会在完成配置并主动启用后发送。提醒投递记录保证正常重试时不重复成功渠道，但进程在远端接收成功、落库之前崩溃时无法保证严格 exactly-once。
