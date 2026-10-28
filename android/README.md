# StrideHub Android 0.3.3

Android 8.0 及更新版本的全原生客户端。主界面使用 Kotlin、Jetpack Compose 和 Material 3；称重继续复用已有原生 Java、BLE 和 SQLite 实现。登录、图表、表单、文件导入及业务详情均通过 JSON API 完成，不依赖 WebView、网页脚本或浏览器 cookie。

正式包名为 `io.stridehub.app`，当前 `versionName` 为 `0.3.3`、`versionCode` 为 `7`；调试包使用独立包名 `io.stridehub.app.debug`。

## 安装与连接

先升级 StrideHub 后端，再安装此版 APK。后端必须提供移动会话、称重配对和移动业务接口。连接时会检查 `apiVersion=1` 和 `mobile-workflows` 能力；旧后端缺少接口时会显示升级提示。

1. 安装使用原发布证书签名的 APK。已有正式版应覆盖安装，保留本机称重记录、测量 UUID 和同步队列。
2. 填写后端根地址，例如 `https://sport.example.com`，以及现有管理员用户名、密码。不要填写 `/admin/login` 或 API 路径。安装在子目录时保留该子目录。
3. 登录后使用「总览、身体、称重、运动、计划」五个主栏目；「设置与更多」提供饮食、减重目标、健康背景、今日安排与反馈、文件导入、运动管理、同步和账号连接操作。
4. 「称重」常驻底部主导航中间，进入后仍可直接切换其他主栏目；「称重、趋势、记录、设置」保留为称重内页。登录表单上方提供完整的离线称重卡片，桌面长按快捷方式也可直达；离线称重不要求先连接 NAS。

原生移动会话默认长期保持登录，token 使用 Android Keystore AES/GCM 加密保存；主动退出、修改密码或撤销会话后需要重新登录。有效的旧原生会话首次联网自动升级，已过期的会话需重新登录。APK 不保存密码，也不需要用户复制服务器全局 API key。旧版网页登录状态不会转换为原生移动会话。

手机上的 `localhost`、`127.0.0.1` 指手机自身。后端需在手机可达的网络地址提供服务。使用有效证书的 HTTPS；可信局域网 HTTP 需要在连接页明确允许，HTTP 不加密账号和健康数据。应用使用系统证书验证，不绕过证书错误，不跟随认证请求的重定向。

NAS 网页的「安卓 App」下载入口读取发布清单。部署尚未包含安装包时会显示未发布；构建成功不代表 APK 已发布或后端已部署。

## 页面、缓存与保存

本轮展示按主要任务组织：总览优先呈现最近身体记录与称重，身体页集中查看期间趋势，称重保持一级入口，运动页突出统计与新增/导入，计划页按今日执行、训练目标、饮食补给和身体感受分组。五个栏目统一卡片、字号、颜色与状态说明；空状态提供后续入口，离线缓存标明保存时间，缺失值保留未知。本轮不涉及存储迁移，验证范围与待补证项目见 [产品展示优化验收记录](../docs/stridehub/product-presentation-20261028.md)。

总览展示最近真实记录、睡眠、身体、运动、下一项计划与同步状态。身体页提供睡眠、日指标、分钟心率、血压、身体测量和体重趋势。运动页提供日期和类型筛选、分页、来源与重复记录核对、详情、原生曲线与轨迹、计圈/分段、跑步分析及可用的 GPX 导出。跑步复盘还包含周/月趋势、活动日历、最佳片段和当前页 CSV；详细分析展示配速/心率散点、心率区间及分布、数据覆盖/暂停/断档和公里分段。确认来源合并后，仍可分别打开文件与 Zepp 的原始详情。计划页和常用记录入口复用服务器的训练、健康计划、饮食、减重目标、条目完成、训练关联和反馈规则。

各主栏目保存自己的详情路径和页面状态。原生表单包含日期/时间、枚举和数值等输入；部分编辑能力由服务器按来源及导入模式决定。手工运动可在文件导入模式下新增；Zepp 直接来源的运动保持只读。删除运动前显示关联数据影响，有训练或来源合并关联时需先解除。

健康 API 缓存与表单草稿放在独立的 Room 数据库 `native-health-v1.db`，按规范化服务器地址和账号隔离；不重建称重数据库 `tinyyumi.db`。已缓存的页面在连接失败时可继续显示，并标明缓存时间和离线状态。缺失测量保留缺失说明，不生成零值代替实际数据。

训练、饮食、计划及运动修改需要服务器在线确认。保存失败不会显示成功；409/428 版本冲突保留草稿并提示核对服务器最新内容。普通健康修改不会在账号切换后盲目重放。称重有独立的持久上传队列，见下文。

退出或切换账号/服务器立即清除本机移动凭据、当前账号健康缓存和草稿，并暂停称重同步。在线时同时撤销该移动会话；远端不可达时明确提示本机已退出，远端凭据仍受有效期限制。本机称重历史和原目的地队列保留，新账号不会接管旧账号记录。

应用私有数据关闭系统备份和应用数据迁移。更换设备或卸载前，请通过称重 CSV 导出保留需要的本机数据。

## 文件导入与服务器同步

系统文件选择器支持 FIT、TCX、GPX，移动上传接口每个文件最多 25 MiB，仍受服务器/PHP 上传配置约束。原生 API 客户端单次响应上限为 16 MiB，过大时需缩小日期范围；GPX 通过原生保存位置选择器导出。上传进度完成和 HTTP 202 仅表示已排队；页面读取单文件回执，再显示成功、重复跳过、失败、中断或已删除。失败/中断可重试，重复提交不会重复排入同一文件。删除关联运动后，服务器清理保留的原文件并留下删除回执，重试不会重新创建它。

Zepp 日数据、睡眠和运动同步提交为服务器持久任务，手机查询任务状态。App 退到后台不会取消已提交的任务；连接中断也不能作为任务失败的证明。重新连接后先刷新状态。未配置、认证失败、部分完成、没有数据和进程中断保留各自结果，不统一显示成成功。

服务器需运行现有守护进程 `bin/console app:daemon:run`。`SystemDaemon` 自动每分钟执行 `bin/console app:mobile:workflows:run --limit=10`，无需另建移动同步定时任务；并发唤醒通过全局/单任务锁避开重复执行。文件导入仍由现有导入队列处理，文件模式下默认每五分钟运行一次。仅运行 Web 服务而没有 daemon 时，任务可能一直排队。

回执和任务状态保存在现有数据库；重试原文件与进程锁位于 `watch/.uploads/mobile-imports/` 和 `watch/.uploads/mobile-locks/`。既有 Web/daemon 容器必须共享并持久化数据库和 `watch` 挂载。锁针对现有同一主机的 NAS/容器部署；多主机部署需要另行设计可靠的共享锁。

移动接口概览如下，业务数据还复用已有健康、训练和运动 JSON API。所有移动响应保持 `private, no-store`，App 自行管理账号隔离缓存。

| 接口 | 用途 |
| --- | --- |
| `POST /mobile/v1/sessions` | 原生账号登录及版本/能力检查 |
| `GET /mobile/v1/session`、`DELETE /mobile/v1/session` | 查询及撤销当前移动会话 |
| `POST /mobile/v1/collection/devices` | 用户确认后的原生称重配对/令牌轮换 |
| `/api/v1/health/...`、`/api/v1/training/...` | 健康工作台、记录、计划、训练与版本化修改 |
| `GET /api/v1/mobile/workouts` | 稳定分页的统一运动列表、筛选后的概览 |
| `GET /api/v1/mobile/activity-options` | 允许的新增/编辑选项和单位 |
| `/api/v1/mobile/activities[/{id}]` | 专用运动新增、查看、更新和删除接口 |
| `GET /api/v1/mobile/activities/{id}/streams` | 各运动类型的曲线、轨迹、计圈与分段 |
| `POST /api/v1/mobile/activities/{id}/enrich` | 按能力补充天气和地理信息 |
| `/api/v1/mobile/imports[/{id}]`、`POST .../{id}/retry` | 上传、列表、单文件真实结果和幂等重试 |
| `/api/v1/mobile/sync[/{id}]` | 提交和查询 daily/sleep/workouts 同步任务 |
| `PUT /device/v1/weight/{measurementId}` | 独立称重设备凭据及持久测量回执 |

统一运动列表接受 `page`、`limit`、`from`、`to` 和 `sportType=all|Run|Walk|Ride|Other`；类型过滤先于汇总和分页。更新/删除保留服务端版本校验，不开放通用管理命令接口。完整字段以对应服务及接口测试为准。

## 自动称重、历史与 CSV

「称重」首次选择体脂秤后记住设备，之后进入称重页自动寻找。搜索持续 90 秒，发现设备后最多等待两分钟；等待期间保持屏幕亮起，离开应用暂停，返回后继续未完成的等待。可主动暂停、关闭自动寻找或更换体脂秤。应用关闭后不会持续收秤，秤休眠时仍需唤醒。

收到同次测量过程和稳定读数后自动保存；只收到稳定广播时要求确认归属，避免把秤重播的旧结果当成本次测量。本次实时读数和已保存历史分开展示，实时值明确标记尚未保存。手工补录支持日期与时间、字段错误提示和旋转后的草稿恢复；保存后先展示刚保存的记录。

首次使用按 Android 版本申请附近设备权限或旧版扫描所需定位权限。BLE 不是安装前提，也不申请广泛存储权限。记录、历史、趋势和身体成分估算保存在本机，未配对或 NAS 不可达时仍可使用。

旧 TinyYumi 的私有数据库属于另一包名，不能直接继承。请在旧 App 导出完整 CSV，再在本 App「称重 → 设置 → 导入旧 TinyYumi 记录」中选择文件。导入最多 8 MiB，整批事务保存；同一记录重复导入会跳过，已删除的导入记录不会因重放复活。保留测量时间、当时日期、资料快照和支持算法版本的历史估算；不支持的算法版本整批拒绝。CSV 不包含原始蓝牙报文，不能恢复该报文；导入不会覆盖当前资料或设备绑定。

核对总条数、日期、体重和历史详情后再停用旧 App。尚未完成备份/同步时，不要卸载唯一保存记录的应用。迁入源码及许可证说明见 `TINYYUMI-ORIGIN.md`。

### 称重配对与持久上传

在称重设置中明确配对 NAS，才会上传本机稳定测量和导入历史；仅登录不会自动启用历史上传，实时 BLE 读数也不会上传。配对复用当前原生移动会话，通过 `/mobile/v1/collection/devices` 获取独立设备凭据；设备凭据使用 Android Keystore 加密保存，后续测量上传不使用移动账号 token。

每条测量有持久 UUID 和采集时区偏移。SQLite 事务冻结规范化载荷及目的地绑定后再发送，重试沿用同一身份和内容。上传任务要求网络、可跨重启恢复，每轮最多处理十条记录；连接中断、超时及暂时故障保留原任务并退避重试。只有回执中的设备、测量 ID 匹配且未被删除时，才确认上传结果。手工日记录冲突、校验失败和服务器删除墓碑保持可见，不能自动覆盖或复活。

退出、断开或切换目的地会停用设备凭据并取消当前调度；队列和不含秘密的原服务器/账号设备身份仍保留。明确回到相同服务器、相同账号并确认配对后，轮换同一设备令牌，恢复可恢复的原队列；已同步、冲突和终止错误不会被统一重置。其他账号不能接管旧队列。删除本机记录会取消尚未发送的任务；服务器已收到的副本需在 NAS 单独删除。

## 构建与签名升级

项目配置：JDK 17、Gradle 8.13、Android Gradle Plugin 8.13.2、Kotlin/Compose 插件 2.0.21、SDK 36、Build Tools 35.0.0。Gradle wrapper 包含发行包 SHA-256 校验；具体依赖版本以 Gradle 文件为准。

```sh
# 从仓库根目录准备 SDK；也可使用不入库的 android/local.properties。
sdkmanager 'platforms;android-36' 'build-tools;35.0.0' 'platform-tools'
cd android
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

调试 APK 为 `app/build/outputs/apk/debug/app-debug.apk`，测试 APK 为 `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`。GitHub Actions 的 **Android APK** 工作流运行单元测试、lint、调试/测试 APK 构建和 APK 原生边界检查，并保留调试 APK 与测试/lint 报告；CI 构建不等于模拟器或真机验收。

正式升级必须使用与已安装版本相同的签名证书，并递增 `versionCode`。不要为本次升级重新生成签名密钥；新证书无法覆盖已有安装。卸载后重装会丢失私有本机数据，不是签名问题的升级方案。仅首次发布全新应用时创建一次密钥，并在仓库外妥善保存。

通过私有环境配置以下四项，不提交密钥或密码，不把密码写入命令历史：

- `STRIDEHUB_KEYSTORE`：原签名密钥库绝对路径
- `STRIDEHUB_STORE_PASSWORD`：密钥库密码
- `STRIDEHUB_KEY_ALIAS`：原密钥别名
- `STRIDEHUB_KEY_PASSWORD`：私钥密码

```sh
# 在 android/ 目录执行；私有签名环境已准备好。
./gradlew --no-daemon testDebugUnitTest lintDebug assembleRelease
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose --print-certs \
  app/build/outputs/apk/release/app-release.apk
```

与上一版正式 APK 的证书摘要核对一致后，再做覆盖安装和升级数据核验。缺少签名配置时产出可能为 unsigned，不能当作可安装正式包。

发布 NAS 镜像时，在仓库根目录把已核对签名的 APK 和版本清单放入镜像的 `public/downloads/`：

```sh
python3 tools/publish-android-release.py \
  --apk /path/to/StrideHub-release.apk \
  --build-tools "$ANDROID_HOME/build-tools/35.0.0" \
  --output /path/to/image-context/public/downloads
```

发布工具检查有效签名、正式包名和非调试属性，从 APK 读取版本和最低 Android 版本，并生成 SHA-256 及 `android-release.json`；它不能替代与原签名证书的人工核对。同版本不同内容会拒绝覆盖。下载页仅在清单与实际 APK 的大小、摘要匹配时提供下载；安装包和部署清单不提交 Git。

## 验证入口与实际验收边界

`verifyNativeUi` 在 `preBuild` 和 `check` 前扫描生产 Java/Kotlin，拒绝恢复 WebView 创建、CookieManager 或网页脚本执行。APK 还需运行字节码门禁，覆盖打包后的代码及依赖：

```sh
# 从仓库根目录执行。
python3 tools/verify-native-apk.py \
  android/app/build/outputs/apk/debug/app-debug.apk \
  --dexdump "$ANDROID_HOME/build-tools/35.0.0/dexdump"
```

门禁检查嵌入浏览器构造/导航和旧 cookie 调用，不能单独证明所有页面和业务均已验收。`ServerAddressTest`/`CorePolicyJUnitTest`、`NativeContractTest`、原有称重核心和 CSV 测试由 `testDebugUnitTest` 运行。`app/src/androidTest/` 提供原生会话、Room 账号隔离、Compose 表单/图表、独立导航、旧称重库升级、离线入口、BLE 生命周期及上传队列/回执回归。

仪器测试使用 `androidx.test.runner.AndroidJUnitRunner`。只在专门的调试模拟器上运行，不能清除或覆盖用户正式应用/个人设备的数据：

```sh
# 在 android/ 目录执行，并确认连接的是专用验收模拟器。
./gradlew connectedDebugAndroidTest
```

需要直接运行已构建测试 APK 时，安装到专用 `emulator-5556` 后使用：

```sh
adb -s emulator-5556 install -r android/app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5556 install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5556 shell am instrument -w \
  io.stridehub.app.debug.test/androidx.test.runner.AndroidJUnitRunner
```

以上 `adb` 路径以仓库根目录为工作目录。仪器测试会操作调试应用的数据；完整流程只应使用专用测试账号、合成数据、隔离数据库和真实隔离的 `watch` 挂载。测试文件选择器、后端导入和异步任务时，不能仅凭创建同名目录推定已经隔离。

`tests/e2e/native-android-upgrade.py` 专门验证已有 0.2.0 调试 APK（versionCode 2）到本版（versionCode 4）的覆盖升级。脚本固定检查专用 `stridehub_native` AVD、`emulator-5556` 和调试包，使用合成测量、原 UUID/冻结载荷、五种队列状态及删除墓碑作前后快照。准备好专用旧版夹具、同一调试签名和 APK 后，依次执行脚本的 `seed`、覆盖安装新 APK、`verify`；不要对个人应用运行该脚本。

原生验收入口为 `tests/e2e/native-android.py`，覆盖隔离环境中的登录、导航、饮食 CRUD、计划反馈、离线恢复、同步状态和退出；脚本先核对当前 App 的服务器和账号，拒绝操作非测试目的地。`seed-native.php` 和 `seed-native-run.py` 提供合成健康数据及经真实文件导入的跑步数据。文件选择/导出、称重配对、升级与分析页面的实测记录、自动检查结果和未覆盖范围见 [本轮验收报告](../docs/stridehub/native-android-validation.md)。

模拟器、合成 BLE 广播、单元测试、静态门禁和构建结果不能替代真实手机收秤、系统权限/后台行为、TalkBack/大字体、NAS 部署及真实 Zepp 账号核对。运行命令的说明不代表这些检查已经通过；最终测试次数、设备、服务版本、产物摘要及未验证范围以实际验收报告为准。

本目录与仓库使用相同的 GPL-3.0-or-later 许可证，见根目录 `LICENSE`。
