# 原生 Android 配套后端 NAS 部署记录

2026-10-24：已将原生 Android 0.3.0 配套后端部署到既有 NAS Compose 项目 `stridehub`。生产数据、账号配置、Zepp 授权和称重存储接口沿用原位置。没有运行初始化导入、制造测试健康记录、重新配对设备或触发真实 Zepp 同步。

## 部署版本与入口

- 部署源码：`0ed6f7854ced7d0b12e3f39b033294fa0f0aa549`，分支 `codex/native-android`。
- 镜像：`stridehub:native-0.3.0-0ed6f7854`，Linux AMD64。
- NAS 镜像 Config SHA256：`c1bba895a4f9e9cb9aeb1ebc238ce11dd543d3fa85311ecbd013f411ca968ab8`。
- 上一版镜像：`stridehub:main-fb657020c`，已保留。
- 局域网入口：<http://192.168.31.55:8088>。
- HTTPS 入口：<https://n3urdachengdu.xyz:1999>。
- APK：<https://n3urdachengdu.xyz:1999/downloads/StrideHub-0.3.0.apk>，versionCode 4，11,453,394 字节。
- APK SHA256：`0659dec96ed2539ebd2a4f85579cdd5f956ba298ca1515a795fed371e4aed05f`。

## 数据保护与回退

先停止既有 app、daemon 和 TLS 容器，并逐一核对停止状态，再备份。备份路径：

`/vol1/1000/stridehub/backups/pre-native-0.3.0-0ed6f7854-20261024`

其中包含原 `docker-compose.yml`、`.env`、完整 `storage/build/watch/.initialized/mcp` 压缩包、部署前各表与记录种类的内容指纹，以及部署记录。压缩包已校验可完整读取，SHA256 为 `043a40e0e072c72c2a2ac2a453dbf717b2a8a05c0fa3266e94a2258bbdcbf055`。

只替换 Compose 中两处镜像引用，app、daemon 和 TLS 均使用相同新镜像。实际容器检查确认：全部环境变量和挂载的来源、目标、读写权限与更新前一致。数据库仍为 `/vol1/1000/stridehub/storage/database/dreeve.db`，文件、Zepp 授权及共享 watch 目录均保留。`.env`、初始化标记、Zepp 凭据和 MCP 令牌文件内容指纹未变化。

原镜像、原 Compose 和备份均保留。回退应先停止现有服务，再恢复备份中的 Compose 并重建服务。代码回退默认保留当前数据库，避免覆盖部署后产生的新记录；只有确认需要数据恢复时才使用数据库备份。

## 已完成的验证

- 新镜像先在全新隔离卷完成启动、数据库初始化、生产容器编译、路由匹配、原生登录/注销、权限隔离、六项带认证读取、称重配对和实际分钟任务执行。临时测试容器及卷已清理。
- NAS app 健康，daemon 和 TLS 正常运行；三者镜像摘要一致、重启计数为 0。
- 部署前后 SQLite 完整性检查均为 `ok`。24 张表的行数和二进制安全内容指纹全部相同；TrainingRecord 的 12 个原记录种类、共 1,222 条记录均未变化。包括 93 天 Zepp 数据、28 条运动记录及 909 条原始来源记录。
- 原有 21 项带认证数据接口全部返回成功。19 项响应完全一致；另外两项仅 `generatedAt` 变化。
- 原生运动、项目选项、导入列表、同步任务列表、健康工作区及称重历史接口可正常读取。匿名原生会话、运动和设备称重请求均被拒绝；原生登录请求格式校验正确返回 422。
- NAS 实际 daemon 日志确认 `mobileWorkflows` 分钟任务已开始并完成；未为验证创建真实同步任务或测试称重数据。
- HTTPS 登录页面、发布清单可访问；局域网及公网 APK 下载字节数和 SHA256 均与正式签名产物一致。
- 既有 MCP TLS 证书校验和带认证初始化成功。

## 验证范围

NAS 部署前的称重历史、设备采集记录数量均为 0，部署后仍为 0。本次操作没有访问或改变手机本地数据库，不能把这次 NAS 验证视为手机称重历史已经上传。手机本地升级保留证据见 `native-android-validation.md`。

当前用户的 NAS 登录密码没有可用本机副本，因此没有修改或猜测密码。真实 NAS 上验证了登录页面、协议校验、认证保护和既有 API 凭据；成功的原生密码登录/注销验证来自同一镜像的隔离环境。实际手机蓝牙连接、现场上秤及手机到 NAS 的新称重上传仍需实机验收。

受限的原始检查文件、健康数据响应和部署脚本仅保存在被 Git 忽略的 `var/native-artifacts/nas-deploy-20261024/`，没有提交凭据或健康数据。
