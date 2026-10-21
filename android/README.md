# StrideHub Android

可安装的 Android 客户端，连接同一个 StrideHub 后端。原生连接页和导航承载现有手机管理网页，账号、训练、运动记录和健康背景继续由后端保存。

## 使用

1. 安装签名 APK（Android 8.0 或更新版本），允许当前文件来源安装应用。
2. 填写部署地址，例如 `https://sport.example.com`，以及网页版管理员用户名、密码。不要填写 `/admin/login` 或 API 路径。
3. 登录后进入「今日」，底部可切换课表、运动分析和称重。「更多」中可进入文件导入、身体与健康、刷新、管理原始运动记录、切换服务器或退出本机登录。
4. 「保持登录 7 天」使用后端的 remember-me cookie；过期后重新输入密码。APK 不保存密码，不需要配置 API key。

如果是全新后端，导入页可能先进入原有的运动员资料初始化页面；完成该页面后即可上传。请保持 Android System WebView 更新，图表和编辑器使用它运行。

手机的 `localhost` / `127.0.0.1` 指手机自己。电脑开发实例目前只监听本机；请先让后端在手机可达的地址提供服务。真实手机推荐有效证书的 HTTPS。可信局域网 HTTP 需要勾选明确的允许项；它不会加密密码和数据。不要因为连接失败关闭证书验证，APK 也不会绕过证书错误。应用支持带安装子目录的 URL，后端及反向代理同样需要正确配置该子目录。

应用需要网络。课表写入、跑后反馈、运动对账和并发处理复用现有页面。系统文件选择器支持选择运动文件；上传格式和大小仍由后端决定。服务器同源的下载通过系统保存位置选择器保存，最多 256 MB，不跨源携带 cookie。当前没有离线编辑、原生手表同步、推送或后台自动训练规划；更新后端网页即可更新管理界面。

## 构建

固定版本：JDK 17、Gradle 8.13、Android Gradle Plugin 8.13.2、SDK 36、Build Tools 35.0.0。Gradle wrapper 含发行包 SHA-256 校验。参考 [Android 官方兼容表](https://developer.android.com/build/releases/agp-8-13-0-release-notes)。

```sh
# ANDROID_HOME 指向已安装的 SDK；也可以配置不入库的 local.properties。
sdkmanager 'platforms;android-36' 'build-tools;35.0.0' 'platform-tools'
cd android
./gradlew testDebugUnitTest lintDebug assembleDebug
```

调试包：`app/build/outputs/apk/debug/app-debug.apk`，包名 `io.stridehub.app.debug`。GitHub Actions 的 **Android APK** 工作流保留调试 APK 与检查报告，可在 Actions 中下载。它使用调试证书，单独安装，不覆盖正式版本。

正式包名 `io.stridehub.app`。创建一次自己的签名密钥，并保存到仓库外的私有位置：

```sh
keytool -genkeypair -keystore /private/path/stridehub-release.jks \
  -alias stridehub -keyalg RSA -keysize 3072 -validity 10000
```

通过私有环境传入以下四项（不要提交到 Git，也不要直接把密码写入 shell 历史）：

- `STRIDEHUB_KEYSTORE`：密钥文件绝对路径
- `STRIDEHUB_STORE_PASSWORD`：密钥库密码
- `STRIDEHUB_KEY_ALIAS`：密钥别名
- `STRIDEHUB_KEY_PASSWORD`：私钥密码

```sh
./gradlew testDebugUnitTest lintDebug assembleRelease
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose \
  app/build/outputs/apk/release/app-release.apk
```

没有签名配置时只能生成 unsigned 文件，不能把它当成可安装正式包。发布后要保留相同签名密钥，升级时增加 `versionCode`；丢失密钥将无法覆盖安装已有版本。正式版关闭 WebView 调试，应用没有 JS/native bridge、广泛存储权限或可供其他应用传入 URL 的深链接。

## 验证与数据处理

`ServerAddressTest`、`LoginScriptTest` 验证 URL、同源边界和脚本转义，`CorePolicyJUnitTest` 将它们接入 Gradle。`tests/e2e/android-login.cjs` 使用真实 Java 生成的脚本登录隔离后端，同时以合成页面测试错误表单与特殊字符。

`tests/e2e/android-apk.py` 在专用 Android 模拟器中操作实际 APK，使用隔离后端验证登录、导航、数据写入、文件上传及退出。它固定使用 `emulator-5556` 和端口 `8082`，避免操作个人实例；只在专门准备的验收环境运行。`app/src/androidTest/` 另有原生生命周期与文件选择器并发回归，可在指定测试设备执行 `connectedDebugAndroidTest`；这些回调测试不代替真实服务器验收。

会话 cookie 和网页运行时存储位于 Android 应用沙箱；服务器、用户名和 HTTP/保持登录偏好保存在私有 preferences。密码只存在当次登录的内存中。关闭备份及应用数据迁移；切换账号/服务器和退出本机登录会清除该应用的 cookie、缓存与 Web Storage。退出本机登录不等同于撤销其他设备的会话。健康资料仍以服务器记录为准。

WebView 边界参考 [Android 官方 URI 校验说明](https://developer.android.com/privacy-and-security/risks/unsafe-uri-loading)。APK 只向配置的登录页面及其合法 POST 表单提交凭证；第三方 cookie、任意本地文件访问及混合内容均禁用。

本目录与仓库保持相同的 GPL-3.0-or-later 许可证，见根目录 `LICENSE`。

## 合并的身体记录模块（0.2.0）

只保留一个 StrideHub 安装包，包名沿用 `io.stridehub.app`。TinyYumi 的蓝牙称重、手工记录、SQLite 历史、趋势与身体成分估算已迁入 `io.stridehub.app.scale`，作为内部页面打开，没有第二个启动图标。原登录、网页控制、文件导入继续使用现有客户端。登录页有离线称重入口，NAS 不可达时仍能记录。

首次连接秤时申请附近设备权限（旧 Android 为扫描所需定位权限），无需广泛文件权限。当前称重模块仅在本机保存，尚未接入 NAS 上传；界面明确显示“离线 · 仅本机”。退出 NAS 账号不会删除本机称重历史。

旧 TinyYumi 的私有数据库属于另一包名，不能直接继承。请在旧 App 设置中导出全部 CSV，再在本 App 的称重 → 设置 → 导入旧 TinyYumi 记录选择文件。导入最多 8 MB，整批事务保存，同一导出记录重放不重复添加，已删除的导入记录不会因重放复活。保留测量时间、当时日期、资料快照以及支持算法版本的历史估算；不支持的算法版本整批拒绝，原文件仍可保留。CSV 原本不包含原始蓝牙报文，因此不能从 CSV 恢复报文。导入不会自动覆盖当前个人资料或设备绑定。

核对总条数、日期、体重和历史详情后再停用旧 App；尚未完成 NAS 同步前，不应卸载唯一保存数据的 App。来源修订见 `TINYYUMI-ORIGIN.md`。原有六组纯 Java 称重测试已纳入 Gradle JUnit，另有 CSV 格式测试及模拟器事务/去重/离线入口回归。


### Native NAS weighing sync

Weighing → Settings uses the existing StrideHub connection and HTTP opt-in. Log in to the intended account in StrideHub, then explicitly confirm pairing and upload of local stable measurements/imports. Live BLE readings are never uploaded. Device credentials use Android Keystore AES/GCM; browser Cookie+CSRF is used only for pairing and is never stored by sync. Redirects are not followed and TLS uses the platform trust store.

Each measurement has a durable UUID and captured timestamp/offset. A SQLite transaction freezes the canonical payload and binding before retry. Jobs require network, persist across reboot, process at most ten records per run, and retry uncertain/transient failures with backoff. A successful receipt must identify the expected device/measurement and be nondeleted. A manual daily-entry conflict or validation/tombstone rejection remains visible for NAS review. No automatic overwrite or resurrection occurs.

Disconnect/logout/server switch disables native credentials and cancels scheduling. Queues and nonsecret per-server/login device identities remain visible. Explicit pairing to the original exact destination/login rotates the same device token and resumes its pending/revoked rows; synced or conflict/error rows are not reset. New server/accounts cannot adopt old rows. Delete local cancels unsent work; an already received NAS copy must be deleted separately from NAS administration. Export remains available for retained local history.

Verification: source `var/runtime/android-toolchain/env.sh` from repository root, run `android/gradlew` in `android/` for `assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug`. If Google's UTP dependency cannot download, install the built debug/test APKs only on `emulator-5556` and use `am instrument -w io.stridehub.app.debug.test/android.test.InstrumentationTestRunner`. Use a clean debug test application state for the full suite; never clear a user's release application or use a personal device.
