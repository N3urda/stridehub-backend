# 小米运动健康数据接入

已接入睡眠，以及步数/距离、独立热量、心率、静息心率、血氧、压力、体重/体成分、异常心搏事件、运动记录，共 10 类云端数据。睡眠进入统一恢复概况；其余 9 类作为独立来源记录保存、按类型查询并在身体与恢复页面展示，HealthWorkspace 同时返回 `miFitnessData` 汇总。

这些接口返回什么就保存什么；不虚构未返回的手环指标。体重和运动来源暂不自动关联手填体重、训练计划或 WorkoutCatalog，避免重复归档。设备/地区可用性以实际云端返回为准。

## 数据规则

- `sleep` 保存两个来源全部会话，使用独立 ID，不覆盖手填身体状态或 Zepp 记录。
- `/api/v1/health/sleep` 每个醒来日期只返回一段主睡眠：没有品牌优先级，按有效时长、时间范围、分期及附加指标完整度选择主记录；明确不完整记录降级。同等完整度时依次比较时长、结束时间和 ID。小睡不进入每日平均。
- `/api/v1/health/sleep/sources` 返回全部原始来源会话的规范化明细，包括小睡。小米保存分期累计分钟与可用逐段时间；缺失指标为 null，0 分评分视作未知。保留的是连接器规范化数据，非小米云端原始报文。
- 小米登录失败、空响应、解析失败不会删除历史；部分解析失败显示 partial，不能声称整段日期完整。同步失败不会删除已有记录或自动降低其优先级。
- 每日观察、训练分析、HealthWorkspace 和首页通过统一睡眠选择读取数据；原有每日 Zepp 指标保持原路径，小米活动和生理指标通过独立明细与 `miFitnessData` 查看。
- 同步日期按个人资料时区计算，主睡眠归到醒来日期。桥接查询按入睡日期过滤，因此读取范围前扩 3 天、后扩 1 天，再过滤到目标醒来日期。
- 同步带本地锁、数据库版本化租约与账号绑定。更换小米账号需独立数据库，不能混入原账号记录。

### 互补规则（2026-11-06 更新）

同段匹配要求：起止时间各相差不超过 90 分钟，交集至少覆盖较短会话的 80%。只对匹配且非不完整的记录补充缺失指标，并返回 `fieldSources`、`contributingSources`、`alternatives`。已有值冲突时不覆盖、不平均。分期不逐字段拼凑，只有整组缺失且两份总时长一致（允许 1 分钟舍入差）时才补整组。评分保留在 `sourceScores`，不跨设备平均。不同时间段仅列为参考，不自动合并成一次睡眠。

`wakeCount`、`isIncomplete`、`sleepSegments` 已扩展保存；段落保留实际起止时间，状态 6 为未分期，未知编号不推断。`total_snore_time` 仅在 `rawExtensions` 保存原值，单位尚未确认，不换算鼾声时长或生成健康结论。页面所有来源区可展开逐段时间。

新增来源记录使用账号、类型、设备来源和云端时间构成稳定 ID；重复同步不新增，相同 ID 的云端修订更新原记录。缺失值不补零，每类独立报告成功、空数据或失败；空数据及失败不删除历史。源设备以散列标识保留，嵌套凭据、账号、URL 类字段在桥接层剔除。

每日小米活动汇总对同一分钟的多个来源取步数（或热量）较大的单条记录，避免直接相加。原始来源均可查看；这只是保守汇总，不声称复现小米 App 的专有合并算法。步数流热量与独立热量分别显示，单位仍需手机对账。

## 上游合约

依赖 [HUAYUE1024/mi-fitness-mcp-cn](https://github.com/HUAYUE1024/mi-fitness-mcp-cn/tree/1767b4921ad94e38a68ac77cbb0bdf809a92864d)，固定提交 `1767b4921ad94e38a68ac77cbb0bdf809a92864d`（包 0.2.2）。项目采用非官方接口，不保证设备/地区永久可用。升级前需重新核对 `api.py`、`services/query_service.py` 与睡眠解析器。

使用 `GET /api/status?deep=false` 校验账号，`POST /api/sync` 仅同步 `sleep` 且 `force_full_sync=true`，完整成功后才读取 `GET /api/sleep?include_naps=true`。最后再次校验账号。其他 9 类通过同样鉴权的 `GET /api/stridehub/records` 直接读取云端并保留可用字段，每类读取前后也校验账号。每类最多 30,000 条/7 MiB，超过限制明确失败，需缩短范围；不会静默截断。HTTP 不跟随重定向，响应大小最多 8 MiB，错误不回显上游响应、凭据或 URL。

## 本地睡眠解析修正

`tools/mi-fitness-local.py serve` 使用 `tools/mi_fitness_sleep.py` 的适配器替换上游睡眠解析。不要用未修正的上游 `mi-fitness-mcp api` 作为本集成的睡眠数据源。

真实授权后的只读检查发现：`duration` 已是睡眠分钟，不能再次扣减 `sleep_awake_duration`；`sleep_trace_duration` 大于 0 的未分期记录具有状态 6，不能按上游默认映射成浅睡。深睡/浅睡/REM 使用明确的 `sleep_*_duration` 字段，未提供的 REM 保持未知，trace-only 时不把占位零值显示为测得零分钟。评分未返回时为 null。

公开字段交叉参考：[mihealth-api 固定版本](https://github.com/Aaron-gx/mihealth-api/tree/05edd1308025795e124f8958b20f8408c41b1c82)。当前适配器遇到无法解释的时长结构会明确同步失败，保留原有数据，不猜测阶段。

## 页面扫码绑定

在“身体与恢复”页面的睡眠区域点击“连接小米运动健康”，使用本人小米账号/米家 App 扫描页面二维码并在手机上确认授权。页面会显示绑定进度；绑定成功后点击“同步小米数据（7 天）”即可读取数据。先在手机小米运动健康 App 完成设备同步和云端上传。绑定本身不触发健康数据同步。

二维码到期后可重新生成，也可取消本次绑定。页面刷新后会恢复仍有效的绑定进度。二维码和状态只通过已登录的 StrideHub 页面读取；小米授权 token、账号和连接器 API Key 不返回浏览器。已有账号重新授权时必须使用同一账号，避免把不同人的健康记录混入同一数据库。

页面扫码需要连接器持续运行。尚未启动连接器时，页面会提示服务未就绪；无需在网页输入 API Key、服务地址或账号密码。连接器启动时生成私有控制凭据，扫码成功后保存同步凭据，后续访问无需再次扫码。

### Docker / NAS

使用仓库提供的 Compose 附加文件启用连接器，并重新构建应用以包含页面和 API 改动：

```sh
docker compose -f docker-compose.yml -f docker-compose.mi-fitness.yml up -d --build mi-fitness app daemon
```

连接器镜像固定安装上游提交 `1767b4921ad94e38a68ac77cbb0bdf809a92864d`，同时包含本仓库的睡眠解析和扫码授权适配器。连接器仅在 Compose 网络中监听，不发布宿主机端口；外部只访问 StrideHub 健康页面。

`mi-fitness-state` 命名卷保存连接器数据库和小米授权；`mi-fitness-connection` 命名卷保存 `pairing-control.json` 与 `credentials.json`。应用和 daemon 只读挂载后者，均无需直接访问连接器数据库。文件权限为 0600，卷目录权限为 0700。当前应用与连接器均以容器 root 读取这些文件；挂载点位于 `/run/stridehub-mi-fitness`，不受应用入口脚本对 `/var/www` 的 `PUID` / `PGID` 所有权调整影响。自定义非 root 运行方式需让相关进程使用一致 UID，并保持私有文件权限。

后续更新、重启和停止也应使用同一组 `-f` 参数，确保服务配置一致。保留这两个命名卷；删除卷会丢失连接器授权。已有 NAS 安装若使用自定义 Compose 或不同服务名，需要将附加文件中的网络、服务名及挂载合并到实际部署配置。此命令是部署步骤，不表示当前 NAS 已执行部署或完成真实扫码验收。

### 本机安装和授权

Python 3.11+：

```sh
git clone https://github.com/HUAYUE1024/mi-fitness-mcp-cn.git /tmp/stridehub-mi-connector
git -C /tmp/stridehub-mi-connector checkout 1767b4921ad94e38a68ac77cbb0bdf809a92864d
python3 -m venv /tmp/stridehub-mi-venv
/tmp/stridehub-mi-venv/bin/pip install '/tmp/stridehub-mi-connector[all]'
# 在本仓库根目录运行，保持此进程运行
/tmp/stridehub-mi-venv/bin/python tools/mi-fitness-local.py serve
# 然后在 StrideHub 身体与恢复页面点击“连接小米运动健康”。
# 也可保留命令行扫码方式，在另一个终端运行：
/tmp/stridehub-mi-venv/bin/python tools/mi-fitness-local.py pair
```

使用命令行 `pair` 时，打开输出路径的二维码，用本人小米账号/米家 App 扫码。脚本默认仅监听 `127.0.0.1:8321`，将数据库/配置写入已忽略的 `storage/mi-fitness/`；成功后自动保存权限 0600 的 `credentials.json`，不在终端打印账号或 Key。账号 passToken 由上游保存到系统 keyring，若 keyring 不可用，上游会退回本地数据库；整个目录含敏感数据，不应上传或公开共享。

`pair` 与页面使用同一绑定流程，可为原账号重新授权；不同账号会被拒绝，已有健康数据保留。到期可重试，二维码在轮询结束后删除。停止连接器进程即停止云端读取。`/tmp` 安装适合首次验证，长期运行应换成持久化虚拟环境和服务管理。

## StrideHub 配置

默认读取 `storage/mi-fitness/credentials.json`，或环境变量 `MI_FITNESS_CREDENTIALS_FILE` 指定的文件。字段为 `baseUrl`、`apiKey`、`userId`（本人账号，必须与桥接状态匹配）。扫码控制配置默认读取 `storage/mi-fitness/pairing-control.json`，可用 `MI_FITNESS_PAIRING_FILE` 指定路径，字段为 `baseUrl`、`apiKey`。两份文件均由连接器生成，不要手动把值填进网页、放在 public 或提交 Git。允许 HTTPS；HTTP 仅允许回环主机或私有 Compose 服务名 `mi-fitness`。Docker 内的 127.0.0.1 指向容器自身，不能直接使用宿主机本地地址；上述 Compose 附加文件已配置服务地址和持久化存储。

连接器环境变量：`MI_FITNESS_STATE_DIR` 指定私有状态目录，`MI_FITNESS_CONFIG_DIR` 指定与应用共享的凭据目录（默认与状态目录一致）；`MI_FITNESS_HOST` 指定监听地址（默认 `127.0.0.1`），`MI_FITNESS_BASE_URL` 指定应用可达的连接器地址（默认 `http://127.0.0.1:8321`）。固定监听端口为 8321。Docker 镜像设置 `MI_FITNESS_ALLOWED_HOSTS=mi-fitness,127.0.0.1,localhost`，允许应用使用服务名访问；若采用自定义服务名或 HTTPS 主机，还需将实际请求的主机名加入该逗号分隔白名单，否则上游会返回 403。保持精确主机列表，不使用通配符。不要将连接器直接发布到公网。

```sh
php bin/console app:health:mi-fitness:sync --days=7
# 经手机对账后可分段回填（每次最多最近 90 天）
php bin/console app:health:mi-fitness:sync --days=30
```

身体与恢复页面有独立“小米数据（7 天）”同步和状态刷新按钮；Zepp 按钮保持原有 90 天同步。已运行的 daemon 每小时第 35 分钟调用 `--if-configured`，无配置时不发起请求。睡眠请求最多等待 180 秒，其他每类云端读取最多 40 秒，多类型同步可能耗时数分钟；Web 代理需允许相应请求时间，超时后先刷新状态再决定是否重试。

## API

- `GET /api/v1/health/mi-fitness/status`：无云端请求的本地同步状态，无账号或密钥。
- `POST /api/v1/health/mi-fitness/sync`：JSON `{"days":7}`，仅允许整数 1–90，默认 7。
- `GET /api/v1/health/mi-fitness/data?from=YYYY-MM-DD&to=YYYY-MM-DD`：各类型保存条数、逐日活动汇总、逐类型同步状态。
- `GET /api/v1/health/mi-fitness/records?type=heart_rate&from=YYYY-MM-DD&to=YYYY-MM-DD&offset=0&limit=100`：全部字段的分页明细，limit 1–200。
- 对应页面会话入口 `/admin/health/api/mi-fitness/status|sync|data|records`，写入需 `X-CSRF-Token`（health）。API 使用既有 Bearer 授权，响应 `no-store, private`。
- 页面扫码使用 `/admin/health/api/mi-fitness/pair/status`、`/pair/qr`（GET）及 `/pair/start`、`/pair/cancel`（POST）。写入同样需要 health CSRF token；二维码仅提供同源私有 PNG，不跳转到外部授权图片地址。
- `GET /api/v1/health/sleep/sources?from=YYYY-MM-DD&to=YYYY-MM-DD`：全部来源规范化明细；默认 `/sleep` 为每日选中的主睡眠。两者均要求现有健康接口授权。

## 验证

运行 `php vendor/bin/phpunit tests/Domain/Health tests/Controller/Api/V1/Health`。覆盖跨日、分期未知值、来源优先、重复导入、历史修订、小睡、空同步保留、错误状态、账号隔离、鉴权、CSRF 和响应隐私。

真实验收还需：本人扫码 → 近 7 天同步 → 与手机核对醒来日期/睡眠时长/阶段 → 重复同步无重复 → 检查统一概况及 Zepp 原有记录。未完成此步骤不得报告真实数据已接入。

### 本机验证记录（2026-11-06）

用户已完成本机扫码，已读取并写入最近 7 天的 7 段睡眠；首次重复同步为新增 0、未变化 7。真实字段检查发现的上游解析问题已由本地适配器修正，重同步更新 7 段、无跳过，未分期记录不再错误标记为浅睡。当前本地库没有 Zepp 历史，双来源合并通过合成测试，尚未在 NAS 的真实 Zepp 历史上验收。未部署 NAS。

PHP 健康领域/API 回归：151 项、994 个断言通过；定向 PHPStan level 8 通过。Python 解析器另有 4 项合成测试。完整仓库仍有本次接入前已有的合并状态，不能将这些检查当作全仓库发布验收。

### 互补与扩展验证补充

主记录选择已移除品牌权重。补充了品牌互换不影响完整度选择、同夜缺失指标互补、非重叠不合并、分期不混拼、不完整记录降级等测试。真实重同步更新 7 段：11 月 6 日保存 11 段时间，11 月 2 日保存 6 段时间及 2 次清醒。没有真实 Zepp 历史的限制不变。

当前回归为 154 项 PHP 测试；Python 解析及明细测试 5 项。测试与真实同步需串行运行，因为两者在当前机器共享同步锁名，重叠运行会正确返回 409。


### 全类型接入与运行条件

新增测试覆盖所有类型、重复导入、云端修订、同分钟来源去重、缺失与零值区分、单类失败保留历史、账号隔离，以及明细鉴权/分页参数。桥接测试验证字段清理、来源身份和运动开始时间回退。

后续手环数据需要先同步到手机小米运动健康并上传云端。应用 daemon 每小时第 35 分钟运行全类型同步；只有 daemon 和连接器都持续运行才会自动采集。当前是本机验证，尚未安装开机服务或部署 NAS，`/tmp` 虚拟环境不作为长期部署。新增记录目前通过通用记录表读取，海量长期分钟级数据仍需后续索引/存储优化。


本次全类型真实验证（2026-11-06）：近 7 天睡眠 7 段、步数/距离流 893 条、独立热量流 893 条已保存；其他 7 类云端返回空数据。紧接着重复同步全部为 unchanged，无新增、无跳过。空数据类型尚未验证真实手环字段，仅通过接入合约与合成数据测试。

最新验证：155 项 PHP 健康领域/API 测试、1043 个断言通过；8 项 Python 测试通过；定向 PHPStan level 8 通过。无头 Chrome 使用合成数据验证类型切换、分页、空记录、原始字段安全文本展示和同步按钮；这不等同于与手机 App 的数值对账。

### 网页扫码绑定验证（2026-11-09）

页面新增连接入口、私有二维码、状态轮询、取消、过期重试和原账号重新授权。确认后由连接器保存凭据，页面启用原有 7 天同步按钮；不会在扫码成功时自动开始拉取健康数据。控制接口仅对管理员会话开放，写请求校验 CSRF，页面不接收小米账号、授权 token 或连接器密钥。

在最新 `main` 上，健康领域 66 项 / 397 断言、健康 API 与首次使用/MCP 工作区 126 项 / 1113 断言通过。两组分开运行，Web 测试使用空的 Zepp 凭据路径以隔离本机授权，领域测试保留自身临时凭据夹具；PHP 测试内存上限为 1 GiB。PHPStan level 8、Twig、服务容器及 JavaScript 语法检查通过。

Python 11 项扫码测试及原有 8 项解析/明细测试通过。扫码测试包含真实固定版本上游应用和数据库生命周期、模拟云端授权、生成凭据后访问既有状态 API，以及账号不匹配、过期、取消后迟到确认、旧会话请求和私有信息不回显。浏览器模拟测试覆盖绑定后手动同步、刷新恢复待扫码状态、旧响应竞态和手机布局。

本轮未执行真实小米扫码、NAS 部署或 Docker 镜像运行验收；当前环境没有可用的 Docker daemon。Compose 配置和构建输入已作静态检查。
