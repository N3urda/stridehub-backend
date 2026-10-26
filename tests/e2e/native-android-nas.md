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
  "timezone": "Asia/Shanghai"
}
```

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

每次运行生成独立的时间戳目录。`--scenarios` 支持 `navigation,meal,plans,running,dates,draft,offline,sync,logout`，或 `all`。公开实例、认证背景和合成 profile 守卫及原生登录始终先执行。若 App 已登录其他服务器/账号，脚本拒绝继续，不自动注销或切换原目的地。

## 实际断言

- 原生 HTTPS 登录、明确关闭 HTTP 选项、保持登录，校验 App 设置显示的完整公网根与专用账号。
- 四个 tab 使用真实接口数据；控件树不得含 WebView。
- 饮食新增→版本1→编辑→版本2→删除，均经公网 JSON API 读回。
- 健康计划目标修改保留原条目；训练课表原生 RPE 反馈实际落库并递增版本。
- 跑步复盘的周趋势、日历、最佳片段入口及真实导入详情：散点、区间、心率分布、记录缺口、GAP 分段均可达。
- 原生日期选择器实际更改查询起点。
- 未保存饮食草稿：后台 `am kill` 后重新进入，确认 PID 改变、草稿内容恢复，并确认没有服务器新增记录。
- Android Wi-Fi/移动数据关闭后，缓存显示离线时间；原生手工称重准确新增一条本机记录且有稳定 UUID。只读 SQLite 快照连同 WAL 在临时目录核对，不写入报告。网络状态在 `finally` 中恢复到原值，再确认公网刷新清除离线/待核对标识。
- 原生提交同步任务；不操作 NAS worker，最多默认等候240秒，确认其进入 failed/not_configured，App 显示真实终态。
- 退出后仍可打开离线称重，再回到连接页。

离线称重只断言本机保存，不宣称该记录已配对/上传或经过物理 BLE；本脚本不点击“上传全部本机历史”。配对、日记录冲突和真实手机蓝牙需在各自受控验收中另外报告。

## 报告与失败停点

`report.json` 记录每步状态、耗时、API 方法/路由/状态码、失败停点和有限数量统计，不写密码、token、原始健康载荷或原始异常输出。截图和报告为私有权限。只在双重实例身份校验后截图；每步失败立即停止后续业务写入，尝试截图，并撤销脚本自己的观察会话。网络恢复失败会明确提示先恢复模拟器网络。

这些是自动化证据，不把 `queued` 当成功，不把页面仅打开当保存成功，也不把 HTTP API 可达当原生 App 整条链路通过。

## 无设备自检

```sh
python3 -m unittest discover -s tests/e2e -p test_native_android_nas.py -v
python3 -m py_compile tests/e2e/native-android-nas.py
```

自检仅验证目的地守卫、重定向禁止、日志脱敏、UI helper 隔离及断网恢复的 `finally` 行为，不访问网络或模拟器。
