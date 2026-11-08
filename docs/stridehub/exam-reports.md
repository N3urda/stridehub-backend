# 体检报告与日常跟踪

体检报告页面位于 `/admin/health/reports`，使用已有的健康档案和日常记录，不另建一套身体测量数据。所有正常和异常检查结果保留原始文本、单位、参考范围、来源和页码；历史对照单独保存，并明确不是完整的历史原报告。医生意见和 AI 解读分开显示。

日常跟踪展示真实记录的日期、来源和覆盖情况，缺失项不补零。体检数据只作为检查当日的基线，血脂、尿酸等检验值留在报告和复查事项中。健康计划中的复查事项可以标记完成；完成事项不会自动解除健康限制。

## 原件接口

`GET /api/v1/health/reports/{id}/attachment` 返回是否存在原件，存在时含文件大小和 SHA-256。`PUT` 在同一路径接收原始 `application/pdf` 请求体，最大 20 MiB，必须先有对应报告。相同内容可安全重试，不同内容返回 409，不覆盖已有原件。

`GET /api/v1/health/reports/{id}/attachment/file` 浏览原件，添加 `?download=1` 下载。浏览器会话接口使用 `/admin/health/api/reports/...` 前缀，上传要求 `health` CSRF 令牌；API 接口要求 Bearer 身份认证。所有结果均为 `no-store, private`。

文件保存在持久卷 `storage/files/health-reports`，不能复制到 `public`、提交 Git 或打入容器镜像。部署须先停止写入并备份数据库与持久文件，验证原数据不变后，再经正式接口导入报告、原件和跟踪计划。
