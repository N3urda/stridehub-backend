# 0.3.2 长期原生登录：设计评审与分阶段验收

只允许原有独立 stage 的公网路径 `/__stridehub_e2e_20261026`，专用 `emulator-5556 / stridehub_native`。不访问生产实例，不启动设备，不安装/卸载 APK，不清 App 数据，不写 SQLite，不管理 NAS。所有实际操作由执行者显式选择阶段启动；本脚本的编写和自检不代表真机或公网结果。

## 设计评审：必须保留的边界

| 边界 | 必要行为与证据 |
| --- | --- |
| 0.3.1 兼容 | 未传 persistent 的 POST 仍给7天到期值。长期响应必须同时具有 capability `persistent-mobile-sessions`、`persistent:true`、显式 `expiresAt:null`；缺失或无效 expiry 不能被解释成永久。 |
| 有效旧身份升级 | PATCH 使用原 token 并幂等升级；不生成新token。已过期、撤销、改密码后的身份仍401；不能因为升级流程恢复失效权限。旧会话若在7天过期后才首次联网，需重新登录，不能声称无条件迁移。 |
| 首次联网与并发 | 保存升级元数据应保留原密文、账号、缓存、草稿和称重绑定/队列。若 generation 变化，要正确衔接 Repository；退出/切账号与旧升级回包竞争时，不得写回身份。网络失败、旧后端无capability、取消不能清除有效旧token。 |
| 凭据存储 | Android Keystore加密token，不保存密码；永久身份不依赖设备当前年月。`persistent:false` 的旧expiry仍必须检查。损坏密文/账号绑定不符要求登录。 |
| 撤销 | 在线退出确认原token被撤销。离线退出先清本机可复用身份，以加密的待撤销任务在网络恢复后补交；保留称重记录。断网退出不能谎称服务器已撤销，也不能把待撤销token当当前登录身份。 |
| 服务器重启 | mobile-sessions 数据与 credentialVersion 持久保存，不依赖进程内内存；密码变更/账号禁用后长期token立即失效。 |
| 数月闲置 | 用debug专项instrumentation注入时钟/反射重载进程证明；不要调整模拟器系统时钟，否则TLS、夹具日期和NAS时钟会混入结果。 |
| 正式签名覆盖 | 必须先在真实签名0.3.1登录并保留本机合成测量，再 `install -r` 0.3.2。UID、签名、会话密文及称重四表均保留；不能用全新0.3.2登录代替升级证据。 |

其中时间推进、失效旧token、改密码、升级/退出竞态及离线撤销任务应由PHP冻结时钟测试与debug专项instrumentation覆盖。现有 `NativeSessionTest` 会清原debug身份/设置，不能在保存现场数据的阶段运行整个instrumentation套件；只跑已审查、负责恢复原状态的专项用例。

## 运行前契约

复用 `native-android-nas.md` 的公开 marker 与认证 context marker；私有凭据文件只含合成账号的 username/password/instanceId。所有阶段先检查双marker。设备阶段再检查专用 AVD、预期包/版本、release/debug属性、私有connection中的精确公网根与合成账号。

正式包只读私有证据通过专用模拟器 `su 0 cat`，不调用 `adb root`，不改变 adbd。若当前镜像不允许此读操作，阶段明确失败，不绕过或退化为“页面能打开”。debug使用run-as。原始数据库和安装APK仅在临时目录使用，不进报告；会话只保存密文摘要。称重检查覆盖 measurements、measurement_derivations、legacy_imports、sync_queue 的全部列，不上传本机历史。

API跨重启检查需要跨进程保留两个合成测试token，存于输出目录 `api-state.private.json`（0600）。这不是报告，绝不提交Git或展示；`api-finish`撤销后删除。普通报告只含状态、路由、摘要及计数。

设置本机参数（真实值不要粘贴到报告）：

```sh
PERSISTENT_OUT=var/native-artifacts/persistent-login-20261027
PERSISTENT_CREDENTIALS=/private/path/stage-credentials.json
PERSISTENT_ADB="$ANDROID_HOME/platform-tools/adb"
PERSISTENT_APKSIGNER="$ANDROID_HOME/build-tools/35.0.0/apksigner"
# STRIDEHUB_E2E_PUBLIC_BASE 由root设置，须带既有隔离前缀。
```

## 1. 先冻结真实0.3.1正式包基线

由root恢复**同一独立stage** 0.3.1及公网投影，在现有正式签名0.3.1原生登录并保持登录，再新增唯一一条release手工DEMO本机测量。不要配对上传所有历史。debug已有的71.4kg记录及原队列不动。

```sh
python3 tests/e2e/native-persistent-login.py device-before \
  --base "$STRIDEHUB_E2E_PUBLIC_BASE" --credentials "$PERSISTENT_CREDENTIALS" \
  --output "$PERSISTENT_OUT" --adb "$PERSISTENT_ADB" --apksigner "$PERSISTENT_APKSIGNER"
```

要求正式包code5、有效有限expiry、加密token、本机至少1条测量。脚本原生读身份并刷新，然后强停冻结一致的SQLite证据。已有基线禁止覆盖。

## 2. 部署stage0.3.2并覆盖安装同签名APK

部署、保留数据及 `adb install -r` 均由root另外执行；不能卸载或清数据。然后运行：

```sh
python3 tests/e2e/native-persistent-login.py api-begin \
  --base "$STRIDEHUB_E2E_PUBLIC_BASE" --credentials "$PERSISTENT_CREDENTIALS" --output "$PERSISTENT_OUT"
python3 tests/e2e/native-persistent-login.py device-after \
  --base "$STRIDEHUB_E2E_PUBLIC_BASE" --credentials "$PERSISTENT_CREDENTIALS" \
  --output "$PERSISTENT_OUT" --adb "$PERSISTENT_ADB" --apksigner "$PERSISTENT_APKSIGNER"
```

`api-begin`验证旧默认7天、新长期身份、原旧token两次PATCH、撤销后GET/PATCH401。保留两个有效测试token供重启检查。设备阶段要求code6、原签名和UID、称重四表未变；第一次联网自动升级后要求persistent元数据和原密文摘要相同，再强停重开两次验证免登录。截图分别保留升级与重开状态。

## 3. NAS重启后检查

root只重启同一stage，并保存容器StartedAt变化/同数据库卷的独立证据。脚本本身不重启NAS，报告不会单独声称执行了重启。

```sh
python3 tests/e2e/native-persistent-login.py api-after-restart \
  --base "$STRIDEHUB_E2E_PUBLIC_BASE" --credentials "$PERSISTENT_CREDENTIALS" --output "$PERSISTENT_OUT"
python3 tests/e2e/native-persistent-login.py device-restart \
  --base "$STRIDEHUB_E2E_PUBLIC_BASE" --credentials "$PERSISTENT_CREDENTIALS" \
  --output "$PERSISTENT_OUT" --adb "$PERSISTENT_ADB" --apksigner "$PERSISTENT_APKSIGNER"
```

同两个API token须200且仍明确永久；App须无需输入密码即可刷新并显示原账号，称重数据保持原样。

## 4. 断网重开仍有身份，恢复公网刷新

```sh
python3 tests/e2e/native-persistent-login.py device-offline \
  --base "$STRIDEHUB_E2E_PUBLIC_BASE" --credentials "$PERSISTENT_CREDENTIALS" \
  --output "$PERSISTENT_OUT" --adb "$PERSISTENT_ADB" --apksigner "$PERSISTENT_APKSIGNER"
```

此阶段须在已升级登录的release上执行。先联网核对身份及原密文，再保存Wi-Fi/移动数据原状态并断网，强停重开，确认显示总览及真实离线缓存且没有登录表单，密文与称重四表均保持。`finally` 恢复原网络状态后，通过App重新刷新公网并再次核对。默认离线状态最多等待90秒，可传 `--offline-timeout`。

## 5. 离线退出不复活，联网补撤销，再重新登录

```sh
python3 tests/e2e/native-persistent-login.py device-logout \
  --base "$STRIDEHUB_E2E_PUBLIC_BASE" --credentials "$PERSISTENT_CREDENTIALS" \
  --output "$PERSISTENT_OUT" --adb "$PERSISTENT_ADB" --apksigner "$PERSISTENT_APKSIGNER"
python3 tests/e2e/native-persistent-login.py api-finish \
  --base "$STRIDEHUB_E2E_PUBLIC_BASE" --credentials "$PERSISTENT_CREDENTIALS" --output "$PERSISTENT_OUT"
```

设备阶段先验证没有其他待撤销记录，然后断网点击原生退出。只读 `native_revocations.xml` 必须出现本轮唯一64hex String队列项，其JSON仅包含当前隔离服务器/合成账号与Keystore加密token，并具有 `loggedout.<id>` boolean true 墓碑；不把任意字符串或明文当加密证据。断网强停重开后仍在登录页，当前身份token为空，冻结队列载荷与墓碑均保留，称重四表不变。

`finally` 恢复原Wi-Fi/数据状态后，仅通过正常App启动/系统网络Job自动补撤销，不强制Job、不直接提取token发DELETE、不修改队列。等待pending从1变0，原有及本轮墓碑仍保留、本地身份未复活，再原生重新永久登录并核对称重四表。默认补撤销最多等待180秒，可传 `--revocation-timeout`。

队列消失依据已审查的原生实现：只有DELETE204或已验证的JSON无效凭据401才移除意图。脚本报告 `serverConfirmedViaNativeRevocationDrain:true`，但 `deviceOriginalTokenServer401Observed:false`；不声称抓到了原token的具体401响应。API阶段仍独立撤销两个观察测试token，GET/PATCH均401，再验证新长期登录可用并清理观察身份。

**证据界限：API observer不是App原token。** 正式包离线场景通过持久队列从有到无及墓碑保留证明真实网络恢复后原生撤销路径已得到服务器确认；它没有直接读取原App token的401。若需具体401证据，仍须结合debug专项instrumentation或独立服务端证据。不要把API observer的401当作正式App token的401。

`--package debug` 可用于已预先登录的对应debug基线，不会自动接管其账号，不创建/删除称重记录。正式包验收结束重新登录后已换token，不能继续拿旧before密文去断言device-restart；需保留旧证据，另建新输出目录定义新的验收轮次。

## 无设备自检

```sh
python3 -m unittest discover -s tests/e2e -p test_native_persistent_login.py -v
python3 -m py_compile tests/e2e/native-persistent-login.py
```

这些自检不接触NAS、网络或设备；不能计作公网/签名APK升级验收成功。
