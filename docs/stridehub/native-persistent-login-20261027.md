# Android 0.3.2 长期登录与 NAS 发布验收

发布日期：2026-10-27。正式 APK 为 `io.stridehub.app`，版本 0.3.2 / versionCode 6；配套 NAS 镜像 `stridehub:native-0.3.2-e77e371de`。产物固定源提交 `e77e371dec36c4627e253ecf50fede6c2d865714`，后续提交仅完善验收脚本与本记录。

下载：https://n3urdachengdu.xyz:1999/downloads/StrideHub-0.3.2.apk

## 用户行为

- 默认长期保持登录，取消原七天登录选项。密码不保存在 App，身份凭据由 Android Keystore 加密保存。
- 有效的旧会话首次联网自动升级，原 token 密文、账号、缓存和称重绑定不因升级而替换。已真正过期或已撤销的旧会话仍需重新登录一次。
- 主动退出、服务端撤销或改密码后失效。断网退出先持久保存加密撤销请求和不可恢复标记，清除本机身份，联网后由原生后台任务补撤销。多个失败服务器不会阻塞后面的任务。
- 全原生界面；发布 APK 的两个 DEX 检查无 WebView 构造/导航及 CookieManager 调用。原数据库和称重流程保留。

## 实际设备与公网 NAS 测试实例

使用独立 NAS 项目 `stridehub-native-e2e-20261026`、独立数据库/合成账号，经有效 HTTPS 公网前缀访问。设备为专用 API 35 Android 模拟器 `emulator-5556 / stridehub_native`，不是用户手机。

1. 正式签名 0.3.1 真实原生登录，保存一条手工本机测试称重，再以 `adb install -r` 覆盖安装 0.3.2。签名、UID、原 session 密文及称重四表全列摘要完全一致，无需输入密码完成长期登录升级。
2. 多次强停重开 App 后正常读取公网数据。断网强停重开保留身份和离线缓存，恢复网络后直接刷新成功。
3. 独立 NAS app 容器真实重启，StartedAt 改变、持久挂载不变；新长期 token 和升级 token 都继续可用，App 仍免登录。
4. 正式 App 断网退出：一个加密待撤销请求落盘、重开不恢复身份；联网后正常原生任务将队列从 1 清到 0，保留退出标记。随后用原生表单重新登录，长期身份和称重数据均正确。没有用脚本代替 App 发出这次 DELETE，也没有强制执行测试 job；服务端确认依据原生队列仅接受 DELETE 204 或经过校验的 JSON 401 后清除。未声称直接观察到该 App 原 token 的 401。
5. 对重新登录后的当前身份执行一次真正的 Android 系统重启：boot ID 改变，19.92 秒内完成启动；原生界面无密码输入即可完成公网刷新，当前新 token 密文和称重四表保持一致。
6. 独立公网 API 验证默认旧请求仍为七天、新请求明确 persistent=true/expiresAt=null、PATCH 使用同 token 幂等升级、撤销后 GET/PATCH 均 401。观察 token 均清理。

称重核对涵盖 measurements、measurement_derivations、legacy_imports、sync_queue 的所有列。此次没有连接实体蓝牙秤；证明的是原生手工记录和已有本机数据保留，不能替代实体 BLE 验收。正式生产密码未知，未尝试旧密码或修改密码：成功密码登录与写入验证使用上述独立测试账号；生产使用现有 API key 只读验收。

## 自动化与镜像检查

- 后端：118 tests / 492 assertions，涵盖受控时钟推进 20 年后仍有效、七天旧身份到期、密码/账号变化、撤销、并发升级/删除不复活、原 mobile/security/称重回归。PHPStan、代码格式、生产容器编译和路由检查通过。时间推进为测试时钟，不是实际等待 20 年。
- Android：51 JVM tests；lintDebug、debug/release 构建通过。独立 applicationId 测试包运行 12 项仪器测试，覆盖真实 Keystore/持久存储、冷启动、磁盘清除失败、加密撤销队列及公平重试。原 release/debug 包未被仪器套件清数据。
- 验收脚本：45 项无设备自测通过；与实际公网/设备结果分开记录。
- 生产镜像：linux/amd64；全新隔离 Docker 卷完成真实启动/迁移、长期/旧会话升级与重启、六个读取接口、配对、权限范围、APK 字节校验及实际 daemon 分钟任务。2100 个运行文件摘要匹配，vendor/生成资源与 0.3.1 基础镜像一致；临时容器/卷已清理。

APK 大小 `11469818` bytes，SHA-256：
`fb1489ab51cd29a9b064e1c64b8df622ceaeffc529f7441ca7d6d4f9173a6ac3`

签名证书与 0.3.1 一致：
`5676c1a75175530805fd99e23a19a30b2b7aee0a749f51ca2697438517190c27`

NAS Docker config ID：
`sha256:aec8ae45fbf83b95e269c81761bedff002d4fca655800e62125463ad0b235e1c`

## 正式 NAS 数据保护与上线

所有写者停止后，完整备份现有 storage/build/watch、环境、MCP/config 等持久文件，再只替换 Compose 两处镜像引用。共享 anchor 覆盖 app/daemon，另一处覆盖 MCP TLS；三个服务都升级到同一候选镜像。

备份：`/vol1/1000/stridehub/backups/pre-native-0.3.2-20261027/data.tar.gz`

备份 SHA-256：`f38e6d666718d68c947ed59d811cc9c791c284423f4ed36823a5f0bad2c32787`

停服及新版启动后两次独立 helper 核对均通过：24 张表、13 个 TrainingRecord 类别 / 1329 条记录、schema、全部业务数据及持久文件一致，KeyValue 本次也未变化，备份完整性通过。三个容器的环境值、挂载及权限、端口、启动命令均保持不变。

上线后 21/21 只读 API 返回正确 JSON；19 个载荷完全相同，仅 training/today 和 health/workspace 的 `/generatedAt` 改变，已逐字段复核。新 daemon 本次启动后的 mobileWorkflows 开始与结束日志已实查。公网 HTTPS、APK manifest/全文件 SHA、匿名权限边界及原 MCP TLS initialize 均通过。

维护项目名称最初包含小数点，被 Compose 拒绝且未生成容器、未执行备份/修改。核实后将 helper 名称规范为 `032-20261027` 并使用独立目录，保留失败证据；重做本地 Compose 配置验证后完成部署。管理请求有错误响应时先观察实际容器状态，不因响应失败盲目重发。

收尾曾捕获一次公网 504：网关日志为连接 NAS 上游超时，非应用登录异常；同一时段 NAS 内网为 JSON422、三个正式容器健康/运行且无重启，随后网关本机和公网接口恢复。保留首次失败记录 `public-final-first-transient-504.json`，重新完整执行公网协议、读取、APK 全文件与 MCP TLS 验收。未修改超时值或对未知写入增加盲目重试。

## 证据位置与清理

本轮证据位于 `var/native-artifacts/persistent-login-20261027/`，主要为 `release-*-report.json`、`device-reboot-proof-report.json`、`stage-restart-result.json`、`native-live-verification.json`、`production-before-after/acceptance.json`、`production-deploy/{apply,verify-cold,verify,worker}-result.json` 及 `final-production-gate.json`。私有凭据、inspect 和业务载荷不提交 Git。

公网临时测试 include 已移除，生产 mobile 路由保留。独立 stage 与维护容器停止，备份和测试数据保留以便复查。早期无容器的失败维护项目注册与本地失败记录保留供追溯，不影响生产服务。
