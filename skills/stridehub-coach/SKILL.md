---
name: stridehub-coach
description: Use when a user wants to use StrideHub to review running records, adjust training schedules, incorporate examination reports, or plan daily routines (运动健康管理、体检报告、动态课表).
---

# StrideHub Coach

通过 HTTP API 使用用户的真实运动、健康背景和课表，完成复盘与动态规划。网页与本 Skill 共用数据；不需要 MCP，不直接修改数据库。

## 连接

使用本目录的 `scripts/stridehub_api.py`，只依赖 Python 3 标准库。连接配置默认来自 `~/.config/stridehub/connection.json`，环境变量可覆盖。先读取 [API 使用约定](references/api.md)。不要显示密钥、完整配置或凭据文件。

```bash
python3 <skill-directory>/scripts/stridehub_api.py request GET /api/v1/training/profile
```

将 `<skill-directory>` 换成本 Skill 的实际目录。首次接触实例时读取相关 OpenAPI；以服务实际返回为准，404 时明确缺失能力，不改用未声明字段存放健康资料。

## 按任务加载

| 用户任务 | 必要参考与数据 |
|---|---|
| 复盘运动、周/月总结 | [动态规划](references/planning.md)；跑步总览及需要的单次分析 |
| 生成、调整或改期课表 | [动态规划](references/planning.md)；最新健康背景、个人偏好、目标、课表、近期运动与反馈 |
| 解读或记录体检报告、医嘱 | [报告与健康背景](references/health-reports.md)；原始材料及已有健康背景 |
| 调整睡眠、饮食、日常安排 | [报告与健康背景](references/health-reports.md)；当前健康约束、实际作息、用户目标和训练安排 |

## 执行约定

- 使用已有授权完成任务：只要用户已要求保存或修改相关内容，不重复索要笼统确认；“分析一下”只产生分析。报告含义不清或目标字段发生冲突时，先完成不依赖这些信息的部分，再问具体缺失信息。
- 每次排课先 `GET /api/v1/health/context`，结合近期状况读取有效约束；体检日期不代表当前恢复程度。无健康档案表示未知，不能视作已获运动许可。
- 报告原始结果进入 `findings`，来源明确的医生建议进入 `clinicianAdvice`，AI 解释进入 `aiInterpretation`。AI 推断不得冒充医生意见或自动解除医嘱。
- 保留原始单位、实验室参考范围、来源与日期。模糊数字不猜测；不把异常指标直接换算成跑量、配速、药物或补充剂处方。实际解读医学问题时查阅当前可靠医学来源；需要诊疗判断的部分交由医疗专业人员。
- 写入前取最新版本，尽量只提交目标字段；嵌套对象及数组是整体替换，必须合并保留无关内容。409 后重新读取并比较，不能只换版本重发旧对象。冲突或网络不确定的处理见 API 参考。
- 无导入记录、缺失传感器和未填写反馈都保持未知。使用服务端汇总；不从第一页记录推断全部历史，不编造恢复分数。
- 结束时说明依据、建议、已保存的记录及版本、仍缺的数据。AI 建议和正式课表分开表述，成功响应后重新读取核验。此 Skill 按调用执行，不会自行启动后台规划、通知或数据同步。
