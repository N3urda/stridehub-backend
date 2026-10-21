# Zepp 云端数据扩展研究

研究日期：2026-10-21（Asia/Shanghai）。范围：可复核的公开实现及其请求形状；本研究未读取本地凭据、未请求私人健康数据。下面的“已实现”仅表示上游源码有实现，不等于本账户有数据，也不等于 Zepp 官方承诺稳定支持。

## 证据来源

- [A：zepp-health-cli，固定版本 a466dfa](https://github.com/m4ary/zepp-health-cli/blob/a466dfa853192d445d9b6d55bd244d170818beb7/zepp_health.py)：请求路径、参数、事件预设及体温字段处理的直接源码。
- [A 的说明](https://github.com/m4ary/zepp-health-cli/blob/a466dfa853192d445d9b6d55bd244d170818beb7/README.md)：作者说明请求来自官方 Zepp iOS App 网络捕获；它是非官方实现，不能把预设列表当成设备能力清单。
- [B：zepp-cloud-sdk events，固定版本 ad38be6](https://github.com/weddle/zepp-cloud-sdk/blob/ad38be620f5e1220356302729379c7623012cba4/src/zepp_cloud/resources/events.py)：压力、血氧、PAI、恢复状态及时间窗实现。
- [B：恢复状态解析](https://github.com/weddle/zepp-cloud-sdk/blob/ad38be620f5e1220356302729379c7623012cba4/src/zepp_cloud/parsers/readiness.py)：睡眠 HRV/RHR、基线、身心恢复、皮温、呼吸相关评分，以及 companion 原始数据。
- [B：手环详情解析](https://github.com/weddle/zepp-cloud-sdk/blob/ad38be620f5e1220356302729379c7623012cba4/src/zepp_cloud/parsers/band_detail.py) 和 [协议笔记](https://github.com/weddle/zepp-cloud-sdk/blob/ad38be620f5e1220356302729379c7623012cba4/docs/research/band_data.md)：分钟心率、活动与睡眠原始详情。
- [C：zepp_to_influxdb 源码](https://github.com/bentasker/zepp_to_influxdb/blob/main/app/mifit_to_influxdb.py)：独立实现佐证基础数据、压力序列和夜间血氧事件。该链接为移动版本，研究时已读取，其他核心引用使用固定提交。

## 最有价值的新读取入口

所有下列请求都是 GET。主机沿用账户实际区域主机；不把其他人的区域主机硬编码为默认。A 的通用请求头包括 `apptoken`、`appname=com.huami.midong`、`appplatform=ios_phone`。请求示例只表示形状，绝不包含实际凭据。[来源 A]

### Watch events：无需在路径指定用户 ID

入口：`/v2/users/me/events`。

通用参数：`eventType=<大小写敏感类型>&subType=<子类型>&from=<epoch毫秒>&to=<epoch毫秒>&limit=200&reverse=1`。[来源 A 的 `events()` 与 `_EVENT_PRESETS`]

| 候选数据 | eventType | subType | 证据及处理约束 |
|---|---|---|---|
| 身体电量/精力 | Charge | real_data | A 明确预设；原始保存，需实际响应确认单位及采样 |
| 精力关联压力 | Charge | stress_data | A 明确预设；与 all_day_stress 分开保存 |
| 精力解释/洞察 | Charge | insight_data | A README 命令示例；字段含义未由源码解析 |
| HRV SDNN | hrv_sdnn | real_data | A 明确预设；不得与 RMSSD 混为同一指标 |
| HRV RMSSD | HRVRMSSD | real_data | A 明确预设；实际单位仍应检查返回值 |
| 呼吸频率 | RespiratoryRate | real_data | A 明确预设；未证实本账户有此流 |
| 每日健康汇总 | DailyHealth | summary | A 明确预设；可能与既有汇总重叠 |
| 情绪 | Emotion | real_data | A 明确预设；无解析证据，不解释数值等级 |
| 乳酸阈值 | LactateThreshold | summary | A 明确预设；设备/运动支持条件未知 |
| 血压 | blood_pressure | real_data | A 明确预设；不代表手表能测量或一定有数据 |
| 恢复及皮温偏差 | readiness | watch_score | A 明确预设；当前已有 readiness 原始数据，可能只需解析 |

恢复数据可能已经包含 `sleepHRV`、`sleepRHR`、`hrvScore`、`rhrScore`、`rdnsScore`、`phyScore`、`mentScore`、`ahiScore`、`hrvBaseline`、`rhrBaseline`、`skinTempBaseLine` 等，不必把“没有独立 HRV 流”误判成“完全没有 HRV”。B 还把 `watch_score_data` 当作 companion 原始数据保存；不可丢弃这类记录。[来源 B 恢复解析]

A 将 `skinTempCalibrated` 解释为相对个人基线的摄氏度百分之一，将 255 等值视为缺失。这是第三方解析假设，并非已核验的官方物理量规范；首先保留原值、字段名和来源，不能显示成绝对体温。[来源 A `cmd_temperature()`]

### 训练、生理和身体数据

| 数据 | 路径 | 请求参数 | 证据 |
|---|---|---|---|
| 训练负荷 | `/v2/watch/users/{id}/WatchSportStatistics/SPORT_LOAD` | `startDay=YYYY-MM-DD&endDay=YYYY-MM-DD&limit=900&isReverse=true` | A `sport_load()` |
| VO2max | `/v2/watch/users/{id}/WatchSportStatistics/VO2_MAX` | 同上 | A `vo2_max()` |
| 心率记录 | `/users/{id}/heartRate` | `startTime=<秒>&endTime=<秒>&limit=1000&type=2` | A `heart_rate()` 与命令调用 |
| 体重记录 | `/users/{id}/members/-1/weightRecords` | `fromTime=<秒>&toTime=<秒>&limit=300&isForward=0` | A `weight_records()` |
| 用户配置 | `/huami.health.getUserInfo.json` | `userid=<id>` | A `get_user_info()` |
| 手动睡眠 | `/v1/user/manualData.json` | `userid=<id>&type=sleep` | A `manual_data()` 和 CLI 默认值 |
| 血压记录 | `/users/me/bloodPressure` | `days=7&toDate=YYYY-MM-DD&sourceArrayStr=com.huami.midong.associated,com.huami.midong` | A `blood_pressure_me()` |
| 秒级心率文件索引 | `/users/me/fileInfo/events` | `eventType=second_heart_rate&subType=real_data&from=<毫秒>&to=<毫秒>&limit=200` | A `file_info_events()` 与 `cmd_second_hr()` |

路径里的 `{id}` 必须通过实际授权返回值匹配；本研究没有证据证明所有 endpoint 都接受同一种 ID。项目已有 legacy userId 与 eventsUserId 的分离，必须保留，不能全局替换。

体重 endpoint 的存在有源码证据，但体脂、肌肉量、骨量等字段没有在本次源码中找到具体解析证据：可保留体重响应中的扩展字段，不承诺一定提供身体成分。`getUserInfo` 是 profile blob；具体身高、年龄、设备列表等字段需看实际返回，不能臆造设备 endpoint。秒级心率入口只是文件索引；不等于已获得全部样本，文件格式、权限、URL 有效期和下载范围仍需验证。[来源 A]

### 既有 user events 的补充

入口：`/users/{id}/events`，通用 `from/to` 为毫秒，`limit=2000&reverse=0&userId=<id>`；A 另有 `single_stress`（无 subType）、`health_data/blood_pressure`。已有压力、PAI、血氧可继续使用当前成功路径。[来源 A `events_user()` 和 `USER_EVENT_PRESETS`]

夜间血氧另有 `/users/{id}/events/dateString`：`eventType=blood_oxygen&subType=odi` 或 `osa_event`，`from=<ISO日期字符串>&to=<ISO日期字符串>&timeZone=Asia/Shanghai&limit=999&reverse=0&userId=<id>`。它们是夜间相关事件，不可当作点测 SpO2 百分比平均。[来源 A `events_user_date_string()`；B/C 血氧分类]

## 时间窗、分页和完整性

这些公开实现并没有证明存在稳定的 next-page/cursor 协议。A 多数方法只取一页；B 把请求按天分窗，并用 `max_days=limit`，这只对近似每天一条的流有帮助，不能保证高频事件完整。因此不可直接复制后宣称“全部历史已拉全”。[来源 A 各请求函数；来源 B `_split_windows()`]

建议实现策略（工程建议，不是上游协议事实）：

1. 所有事件保留类型、子类型、原始时间戳、原始 payload 和稳定去重标识；单独记录每个数据源状态。
2. 查询结果达到 limit 时递归缩短时间窗；边界重叠并按稳定 ID 去重，避免同毫秒多条被跳过。最小窗口仍达到上限时明确标记可能截断，不能假报完整。
3. 秒与毫秒严格分离。用户日期按 Asia/Shanghai 转换边界；dateString 使用原生日历日期与显式时区。
4. “成功但空数组”“设备未产生记录”“权限失败”“接口不存在”“可能截断”必须分开，尤其禁止空记录覆盖已有有效值。
5. 先验证近 7–30 天，再按数据源分段回填。每次同步重叠最近数天，以吸收手机迟上传及历史修订。
6. 文件索引 URL 可能是临时签名 URL。默认仅保存必要元数据；不要把签名 URL 放入日志/公开报告。若下载，只读 Zepp 返回的可信 HTTPS 资源且限制大小、重定向和类型。

## 不应承诺的内容

尚无充分请求或解析证据支持完整 ECG 波形、全部设备配置、GPS 每秒轨迹的通用文件协议、月经周期专用接口、食物/营养/饮水、所有历史年份必然可得。这不代表服务没有这些能力，只表示本研究没有可复核证据。训练后恢复时间、训练效果等也可能在运动详情或训练统计 payload 里；先保留已有 workout detail 全量原始响应，再按真实字段扩展，避免猜造 endpoint。


## 补充检索：文件内容及空响应的含义

另检索到 [ZeppBridge 的探测源代码](https://github.com/lingcang728/ZeppBridge/blob/927c83ee30c3e22823b0810c89d9889d58fdb815/src-tauri/crates/core/src/fetcher/probe.rs)。它设置一个故意不存在的事件类型作为阴性对照，因为错误/不存在的事件类型也可能返回空数组。因此，“HTTP 200 且 items=[]”只表示此次请求没有记录，不能据此确认设备不支持、数据不存在或事件名正确。

该源码还列出 `Food` 事件，使用 `/v2/users/me/events?eventType=Food` 并明确省略 `subType`；作者标注为部分地区 App 的手动食物日志。本研究未验证其响应，本账户是否启用也未知。它不改变上文对营养完整覆盖的限制。

秒级心率文件检索检查了 A、[icu 的 ZeppClient 实现](https://github.com/Thejuampi/icu/blob/a5746a6fb31b8ebfbed5666095f382729828a62d/zepp_client.go)、ZeppBridge 以及 [zepp-mcp 探测脚本](https://github.com/DhavalBhimani44/zepp-mcp/blob/9ba4f58ec19c300a74751386fc9a8d22aebbbfff/spike/probe.py)。icu 明确注明只获取 COS 文件索引，**不下载 blobs**；其他所查实现同样没有给出可复核的 `fileId` → 下载 URL 协议。因此不得从 fileId 猜造 COS 地址或把索引计数报告为已下载的秒级样本量。当前可保存 fileId、时间覆盖、文件类型及原始索引，下载能力需后续真实 App 请求证据。
