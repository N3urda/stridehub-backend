# Android 0.3.3 发布与 master 合并

2026-10-28，Android 0.3.3 / versionCode 7，正式包名 `io.stridehub.app`。

下载：https://n3urdachengdu.xyz:1999/downloads/StrideHub-0.3.3.apk

## 代码与发布范围

- UI 功能提交：`bf5bbdb96`；合并到 `master` 并准备版本号的源提交：`ef1a6089271fec0481a8745702c9164b391164d0`。
- `master` 原有 `a09ebd507` 产品流程与原生分支保留在合并历史中。冲突处理保留日期范围、睡眠同步恢复、训练反馈与待办，同时保留新版身体工作台和原生界面。
- 新增批量运动查询的 WorkoutCatalog/Zepp 别名回归，避免旧训练待办把云端已关联运动误报为丢失。
- 本轮 NAS 发布仅在现有 0.3.2 镜像上增加 Android APK、校验文件和下载清单；后端代码继承 `e77e371dec36c4627e253ecf50fede6c2d865714`，并未把 master 合并时保留的旧 Web 产品流程重新部署到生产。

主要展示变化见 [产品展示验收](product-presentation-20261028.md) 和 [称重一级导航](weighing-navigation-20261028.md)。

## 合并与安装包验证

- 正式签名构建、lint、55 项 JVM 单元测试通过；发布 APK 检查 2 个 DEX，无 WebView 构造/导航或 CookieManager 调用。此前同一 UI 实现的 28 项模拟器仪器测试、4 项小屏/两倍字号/深色回归通过。
- 合并后 Training/Mobile 领域和接口回归：172 tests / 944 assertions，通过。首次执行命中旧工作树 Symfony 测试路由缓存而失败，保留失败日志；使用独立 tmpfs 测试缓存重跑通过，没有更改生产代码绕过测试。
- 训练目录别名/历史关联专项：33 tests / 160 assertions（包含上述总数中的测试），PHP 样式、Twig 和 JavaScript 语法检查通过。Web 冲突使用 Chromium 合成响应检查，无页面脚本错误；不代表生产 Web 已更新。
- 专用 `emulator-5556 / stridehub_native` 执行一次正式签名覆盖安装，0.3.2/code6 → 0.3.3/code7，UID、证书、加密登录 token 密文及称重四表全列摘要相同。原合成数据为 measurements 1、derivations 1、legacy_imports 0、sync_queue 0。
- 实际离线界面无需密码，五个主导航可用，称重页显示原本机历史并能返回身体栏目。首次等待无缓存身体内容标题超时，保留该过程；随后只读/导航复核确认选中态与错误状态正常，未重复安装。没有访问真实账号或 NAS 写入。
- 未连接实体 BLE 体脂秤，也未在个人手机安装。

## 固定产物

- 下载文件：`StrideHub-0.3.3.apk`，11,518,970 bytes。
- APK SHA-256：`8f360916e13febf4f947712502bbcaa151e55f591cddb128463ece6bf680be00`。
- 原签名证书 SHA-256：`5676c1a75175530805fd99e23a19a30b2b7aee0a749f51ca2697438517190c27`，与旧正式版一致。
- 镜像：`stridehub:native-0.3.3-ef1a60892`。
- 镜像 Docker config ID：`sha256:e8b9bf6c392d5500a869f385a104f2fffb852d39eb61e8b5d4f705f852b1202d`。
- Docker 导出包校验通过：旧后端全部层相同，仅一个新增层，且恰好包含三个 Android 下载文件；除发布标签外运行配置一致。Docker 引擎对空 User/Volumes/OnBuild 字段的省略与空值差异作显式等价规范化，其余配置严格比较。
- 部署门禁在全新本地 Docker 卷、合成数据上验证十项保护/拒绝路径，通过。

## NAS 上线验证

最新备份：`/vol1/1000/stridehub/backups/pre-native-0.3.3-20261028/data.tar.gz`。

备份 SHA-256：`0c09de057a56d3264086b54abf3246aaaff438d68dc8a478894db34b1a1f0447`。

停止三个生产写者后完成备份与镜像替换；启动前冷校验通过，24 张表、14 个 TrainingRecord 类别 / 1405 条记录、schema、持久文件与备份均保持一致，KeyValue 也未变化。启动后再次核对通过，业务表、所有类别和持久文件保持一致。

- 三个服务运行在同一已核对的新镜像；app 健康，环境变量、挂载、端口与启动命令一致。
- 上线前后 21/21 只读 API 返回正确 JSON；19 个载荷完全一致，training/today 和 health/workspace 仅 `/generatedAt` 改变。保留严格字节比较失败记录，再逐字段复核生成时间差异，没有放宽业务内容校验。
- 新 daemon 恢复后观察到一次 mobileWorkflows 开始与完成，核对的是本次容器和启动后的日志。
- 公网最终 15 项校验通过：下载清单、APK 全文件 SHA/大小、校验文件、登录页、匿名权限边界、缺字段请求、六个只读 API，以及固定证书的 MCP TLS initialize。
- 管理接口部分启停/Compose 操作返回错误，但实际异步任务继续执行；每次均按容器状态、健康结果及数据报告确认，没有盲目重复下发。


四个本轮维护 helper 容器已逐个核实停止，备份和验收证据保留；专用模拟器已关闭，未操作其他模拟器或个人设备。

## 证据

原生工作树：`var/native-artifacts/product-release-20261028/`；合并工作树：`var/native-artifacts/master-merge-20261028/`。私有凭据、容器 inspect、完整业务载荷只保存在忽略目录中，不提交 Git。
