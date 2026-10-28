# 原生 Android：NAS 隔离实例的公网 HTTPS 验收

此入口只测试专用 NAS 实例的真实公网地址，不访问 NAS 主实例、不管理 NAS 容器、不暂停服务、不清空应用数据、不跳过 TLS 校验。它不是已有本机 HTTP 脚本的换地址版本。

## 部署方最小契约

1. 公网安装根地址必须为 `https://公网域名[:端口]/__stridehub_e2e_20261026`。可以有公开端口，例如 `:1999`；不能传入主站根、管理页面、查询参数或其他子路径。入口拒绝跟随重定向。
2. 专用数据库、watch、文件目录和账号只含合成数据；无真实 Zepp 凭据。部署方负责 worker/daemon 正常运行。脚本不执行远端 worker 命令，而是等待真实任务终态。
3. 在相同公网根下提供匿名 `GET /__e2e_health.json`：

```json
{
  "kind": "stridehub-native-e2e",
  "instanceId": "部署方选择的唯一实例标识",
  "productionData": false,
  "allowNativeWrites": true
}
```

4. 私有凭据 JSON 由部署方提供，不能提交 Git：

```json
{
  "username": "专用合成账号",
  "password": "专用合成密码",
  "instanceId": "与公开标识完全相同"
}
```

ADB 文本注入支持 ASCII 合成输入，并对 shell 特殊字符正确引用；原样百分号与 `%s` 分开注入，避免密码被 Android 的空格替换规则改变。若合成凭据含非 ASCII，入口会明确拒绝；这属于当前自动化输入工具限制，不应因此修改生产账号口令。

5. 原生移动会话支持 `apiVersion:1` 以及 `mobile-workflows` capability。登录后的 `/api/v1/health/context` 的 `notes` 必须包含：

```text
STRIDEHUB_NATIVE_E2E:<instanceId>
```

6. `/api/v1/training/profile` 时区默认为 `Asia/Shanghai`；以下默认夹具必须存在，标题均以 `DEMO` 开头：

| 夹具 | 默认 ID | 用途 |
| --- | --- | --- |
| 健康计划 | `native-demo-plan` | 原生修改目标并检查版本/条目保持 |
| 训练课表 | `native-demo-session` | 原生修改名称、填写 RPE 并读回 |
| 导入跑步 | 任一 DEMO 名称的 Run | 原生跑步复盘、散点、区间、覆盖及分段 |

跑步必须经过实际文件导入，且 running detail 中有 timeline、scatter、zones、splits。建议复用既有真实 TCX 夹具生成器。三个同步来源 daily/sleep/workouts 必须均为 `not_configured`，以验证真实 NAS worker 将任务从排队推进至诚实的失败状态，而不触发真实 Zepp 账号请求。

可选 `--fixtures` JSON 覆盖夹具标识：

```json
{
  "planId": "native-demo-plan",
  "sessionId": "native-demo-session",
  "runActivityId": "实际导入的运动 ID",
  "timezone": "Asia/Shanghai",
  "expectWeighingConflict": true
}
```

若要强制验证称重日记录冲突，设置 `expectWeighingConflict:true`，并在模拟器当前本地日期预置不同于 71.4kg 的手工日体重（例如70.5kg）；否则脚本接受正常投影或受保护的手工冲突。冲突时只检查提示及原值完整保留，不执行覆盖。

## 模拟器与运行

只允许专用 `emulator-5556`，AVD 名必须为 `stridehub_native`，已安装的包必须是 `io.stridehub.app.debug`、DEBUGGABLE、默认 versionCode 5。入口不启动/停止模拟器，不安装/卸载 APK，也不重建数据库。未来版本可显式传 `--version-code`。

```sh
python3 tests/e2e/native-android-nas.py \
  --base "$STRIDEHUB_E2E_PUBLIC_BASE" \
  --credentials /private/path/nas-e2e-credentials.json \
  --fixtures /private/path/nas-e2e-fixtures.json \
  --report-dir var/native-artifacts/public-nas-e2e \
  --adb "$ANDROID_HOME/platform-tools/adb" \
  --scenarios all
```

每次运行生成独立的时间戳目录。`--scenarios` 支持 `navigation,meal,plans,running,dates,draft,weighing,offline,sync,logout`，或 `all`。公开实例、认证背景和合成 profile 守卫及原生登录始终先执行。若 App 已登录其他服务器/账号，脚本拒绝继续，不自动注销或切换原目的地。

## 实际断言

- 原生 HTTPS 登录、明确关闭 HTTP 选项、保持登录，校验 App 设置显示的完整公网根与专用账号。
- 四个 tab 使用真实接口数据；控件树不得含 WebView。
- 饮食新增→版本1→编辑→版本2→删除，均经公网 JSON API 读回。
- 健康计划目标修改保留原条目；训练课表原生 RPE 反馈实际落库并递增版本。
- 跑步复盘的周趋势、日历、最佳片段入口及真实导入详情：散点、区间、心率分布、记录缺口、GAP 分段均可达。
- 原生日期选择器实际更改查询起点。
- 未保存饮食草稿：后台 `am kill` 后重新进入，确认 PID 改变、草稿内容恢复，并确认没有服务器新增记录。
- `weighing`：先只读确认本机 measurements 和 sync_queue 均为空，再原生配对。此时配对虽然按钮叫“上传本机历史”，实际无任何历史可上传；配对前后均复核本机为空和服务器采集数不变。任何旧记录或旧队列都立即停止，不清理、不上传。
- 配对后关闭 Android Wi-Fi/移动数据，原生新增唯一 71.4kg 手工 DEMO 测量，检查稳定 UUID 和冻结上传载荷；连续两次点网络恢复重试后仍待上传，公网观察会话确认此 UUID 尚未到达服务器。恢复网络后等候真实设备回执，通过公网移动会话读取 collection/weight，逐项核对 UUID、设备和精确体重；再次重试后服务器采集数仍仅增加一条，日期采集视图也仅出现一次。默认等候120秒，可用 `--weighing-timeout` 调整。
- `offline`：缓存显示离线时间；若前面已完成 weighing，离线读取同一条手工历史，不新增第二条。如果单独运行 offline，则只新增一条本机手工记录，不声称上传。只读 SQLite 快照连同 WAL 在临时目录核对，不保留原始库或载荷。网络状态在 `finally` 中恢复到原值，再确认公网刷新清除离线/待核对标识。
- 原生提交同步任务；不操作 NAS worker，最多默认等候240秒，确认其进入 failed/not_configured，App 显示真实终态。
- 退出后仍可打开离线称重，再回到连接页。

称重上传场景只适用于全新专用 App 的空历史/空队列；重复运行前需由部署方另行准备隔离环境，脚本绝不删除历史。手工录入并非物理 BLE 实秤；报告明确 `manualEntryNotBle:true`。重复重试验证的是断网待上传及已确认后的幂等性，未注入“服务器已收但回执丢失”故障，也不声称经过此边界。真实手机蓝牙仍需单独验收。

## 报告与失败停点

`report.json` 记录每步状态、耗时、API 方法/路由/状态码、失败停点和有限数量统计，不写密码、token、原始健康载荷或原始异常输出。截图和报告为私有权限。只在双重实例身份校验后截图；每步失败立即停止后续业务写入，尝试截图，并撤销脚本自己的观察会话。网络恢复失败会明确提示先恢复模拟器网络。

这些是自动化证据，不把 `queued` 当成功，不把页面仅打开当保存成功，也不把 HTTP API 可达当原生 App 整条链路通过。

## 无设备自检

```sh
python3 -m unittest discover -s tests/e2e -p test_native_android_nas.py -v
python3 -m py_compile tests/e2e/native-android-nas.py
```

自检仅验证目的地守卫、重定向禁止、日志脱敏、UI helper 隔离、断网恢复的 `finally` 行为，以及空历史配对、单一手工 UUID、采集读回去重/设备/精确体重守卫，不访问网络或模拟器。
