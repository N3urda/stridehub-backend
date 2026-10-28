# StrideHub MCP

外部 AI 的首选入口是专用 MCP 服务。页面负责展示真实数据和已保存的计划；对话、分析和计划制定由用户选择的 AI 聊天工具完成。服务使用官方 `mcp/sdk`，直接调用与网页相同的领域服务和数据库，不转发 REST 请求，也不把接口目录暴露成工具清单。

## 本机启动

需要 PHP 8.5、Composer 依赖和 Python 3（进程管理器只用标准库）。在仓库根目录执行：

```bash
composer install
bin/stridehub-mcp init
bin/stridehub-mcp start
bin/stridehub-mcp status
bin/stridehub-mcp config
```

`init` 使用项目的正常数据库迁移，保留已有记录；不会导入演示数据。默认数据库与网页一致，为 `storage/database/dreeve.db`，已有 `strava.db` 会按项目原有规则复用。已有实例请让 MCP 和网页使用相同的 `DATABASE_DIRECTORY`、`TZ`、`APP_SECRET`；`.env.local` 和进程环境配置优先。默认本机进程使用 `APP_ENV=prod`、`APP_DEBUG=0`。

`start` 在 `http://127.0.0.1:8082/mcp` 启动独立进程，该监听端口只接受 `/mcp`。可用 `--port` 选择空闲端口；不会停止占用端口的其他程序。`stop` 停止本管理器创建的进程。机器重启后需再次启动；本地 PHP 内置服务器用于本机连接，没有注册系统自启服务。

私密运行文件位于 `var/runtime/mcp/`（目录 0700、凭据文件 0600、已被 Git 忽略）：

- `credentials.json`：本机默认 MCP token 与应用 secret；已有配置优先，不在命令行或输出中打印值。
- `server.json` / `server.log`：进程信息和 HTTP 服务日志；MCP SDK 不记录工具参数。
- `mcp.json`：运行 `config` 后生成的客户端连接配置，包含当前机器的可执行文件绝对路径。

### AI 客户端连接

本机 AI 客户端可以直接导入生成的 `var/runtime/mcp/mcp.json`。它使用 **stdio**，按需启动 MCP 进程，无需复制令牌；当前系统用户权限就是其本地访问权限。可直接执行的入口：

```bash
bin/stridehub-mcp stdio
```

该进程的 stdout 只输出 MCP 协议。AI 客户端退出后进程随输入管道关闭。生成的配置会固定 Python 和 PHP 路径；手动配置时可用 `STRIDEHUB_PHP` 指定 PHP。

支持 Streamable HTTP 的本机客户端也可连接 `http://127.0.0.1:8082/mcp`，使用 `Authorization: Bearer <STRIDEHUB_MCP_TOKEN>`。令牌从 `.env.local`/进程环境安全注入，或从上述私密凭据文件配置到客户端。HTTP 令牌与旧 `DREEVE_API_KEY` 独立，未设置或长度小于 32 的令牌不会开放访问。令牌是单用户全工作区权限，更新令牌后重启进程，旧令牌立即失效。

默认保留 SDK 的 Host / Origin 校验，仅允许 loopback/localhost。运行在其他机器或云端的 AI 无法直接访问本机地址；需先为目标实例配置受控连接和 HTTPS 部署，不能将本地开发监听直接暴露到公网。本仓库不自动修改聊天客户端的全局配置。

## 供 AI 使用的 9 个工具

| 工具 | 任务 |
|---|---|
| `get_overview` | 先读身体状态、近期运动/饮食表现、未来计划、健康约束、数据覆盖和缺失项；默认 14 天 |
| `get_history` | 按主题读取完整记录、ID、版本；日期范围和稳定快照分页，默认每页 20 条 |
| `get_workout_details` | 单次运动的分析、来源、计划/实际对照；传感器数据按需分页 |
| `save_plan` | 一次事务保存训练、餐食计划和减重目标，保留未列出的记录 |
| `record_progress` | 一次保存真实体重、测量、餐食、血压、主观状态、训练反馈和关联运动 |
| `update_health_profile` | 更新训练偏好、报告摘要和健康约束；报告/约束按 ID 增量合并 |
| `remove_record` | 按类型、ID 和版本删除用户指定的记录，检查引用关系 |
| `resolve_workout_sources` | 经核对后合并/拆分同一次运动的 Zepp 与文件来源 |
| `get_operation` | 按 requestId 查询已提交的操作回执，处理响应丢失 |

客户端通过 `tools/list` 发现 JSON Schema、读写属性、幂等属性和工具说明，无需读取 OpenAPI 或拼接 URL。字段规则在服务端复用现有契约，实际写入复用现有校验。还提供 `stridehub://guide` 资源、初始化 instructions 和 `review-and-plan` 提示词。客户端可以只支持 tools，也可以读取完整使用指南。

### 常见工作流

1. 用户说“复盘最近两周并制定下周计划”：先 `get_overview`，必要时读取 `get_history`、`get_workout_details`，结合数据覆盖和健康约束在聊天中分析。
2. 用户要求保存：生成稳定 `requestId`，调用一次 `save_plan`，包括 `reason` 和所需的 `training` / `nutrition` / `weightGoals`。单次最多保存 100 条记录；任一版本、日期或字段校验失败，整批回滚。
3. 用户报告完成情况：使用 `record_progress`，将实际与计划分开；营养估算使用 `nutritionSource=estimate`，未知值保持 null。
4. 核验响应的记录与版本，或按 `get_operation` 读取提交回执。页面读取相同的数据即可展示结果。

创建可省略 ID/version，服务分配可重试的 ID，版本从 0 开始。修改必须携带当前 ID/version，体重使用 `weightVersion` 字符串。健康背景使用 `healthContext.version`（历史工具返回 `healthContextVersion`）；报告和约束增量合并，单条内容及其他嵌套列表是完整替换。

相同 `requestId` 和相同参数再次调用，返回第一次的提交结果，不产生新记录或新版本。ID 被用于不同参数时返回 conflict；失败事务不保存成功回执。回执与业务数据在同一事务提交。回执代表提交时的状态，记录后来可能变化；不要把旧回执当作最新数据。

`get_history` 后续页须传首次返回的 `from/to/snapshot`；数据改变时要求从头读取。报告、约束、减重目标不按日期过滤，响应明确 `dateFilterApplied=false`。`get_overview` 的近期身体数据只覆盖所选时间窗口，不宣称是全历史的最后一条。

错误通过 MCP `isError` 和 `structuredContent.error` 返回具体原因及恢复步骤。版本冲突后应重新读取并核对差异，不可只替换版本号覆盖修改。服务不推断医学结论、不自动同步设备、不发送通知，也不会依据报告自行执行训练安排。

## 验证与升级

```bash
TEST_TOKEN=mcp php -d memory_limit=1G vendor/bin/phpunit tests/Infrastructure/Mcp
php tools/mcp/smoke.php
```

smoke 需要本机 HTTP 服务已启动；使用官方 MCP 客户端检查 stdio 和 HTTP 两种传输、2025-11-25 与 2026-07-28 协议、工具发现、只读概况、资源和提示词。只输出通过状态，不打印健康资料或令牌。此命令不保存计划、不生成测试数据。

升级代码和依赖后，在同一环境执行 `php tools/mcp/console.php cache:clear --env=prod --no-debug`，再 `stop` / `start`。旧 REST 接口保留供现有网页和兼容客户端使用；新 AI 接入使用 MCP。
