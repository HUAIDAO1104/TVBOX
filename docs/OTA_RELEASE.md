# 片多多 OTA 发布

App 从 `HUAIDAO1104/TVBOX` 的最新 Release 读取 `latest.json`。设备依次使用以下国内镜像，全部失败后才直连 GitHub：

1. `gh-proxy.com`
2. `ghfast.top`
3. `ghproxy.net`

下载完成后必须同时通过清单中的 SHA-256 和已安装 App 的签名校验。

## 一次性配置

为了让已经安装当前 Debug 版本的设备无损覆盖并保留应用数据，GitHub 仓库的 Actions secrets 必须使用开发机当前的同一份 `~/.android/debug.keystore`：

- `RELEASE_KEYSTORE_BASE64`：当前 `~/.android/debug.keystore` 的 Base64 内容
- `RELEASE_KEY_ALIAS`：`androiddebugkey`
- `RELEASE_STORE_PASSWORD`：`android`
- `RELEASE_KEY_PASSWORD`：`android`

这份 keystore 从此就是片多多的永久发布密钥，必须私密保存并至少离线备份一份，禁止提交到 Git 仓库。更换或丢失 keystore 后，Android 将拒绝覆盖安装，只能卸载旧版并丢失本地应用数据。

首次需要手动安装本次带镜像更新能力的 APK。只要包名、签名证书保持不变且 `versionCode` 递增，Android 会覆盖旧版本并保留仓库、收藏、历史、网盘凭证及其他本地数据；此后即可通过 App 内更新升级。

## 发布版本

1. 增加 `app/build.gradle` 中的 `versionCode` 和 `versionName`。
2. 提交并推送代码。
3. 创建并推送标签，例如 `git tag v5.5.19 && git push userrepo v5.5.19`。
4. GitHub Actions 自动构建电视/手机的32位和64位 APK、生成 `latest.json` 并创建最新 Release。

也可以在 Actions 页面手动运行 `Release OTA packages`，填写版本标签和显示在 App 中的更新说明。

普通 Android 设备会显示系统安装确认，这是 Android 的安全限制。Android 8 及以上第一次更新时，App 会引导开启“允许安装未知应用”。
