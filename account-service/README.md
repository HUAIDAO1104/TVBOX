# 片多多账号服务

手机和电视使用同一个服务地址注册、登录。观看记录按账号和点播订阅隔离。登录后，两端通过此服务交换集数和播放位置，任何一端都可以离线。另一端联网并打开应用后会自动接续。

## 部署

在有持久磁盘的服务器上，从本目录构建并启动服务。

```sh
docker build -t pianduoduo-account .
docker volume create pianduoduo-account-data
docker run -d --name pianduoduo-account --restart unless-stopped \
  -p 127.0.0.1:8787:8787 -v pianduoduo-account-data:/data pianduoduo-account
```

将账号域名通过 HTTPS 反向代理到本机的 8787 端口。注册和登录依照来源 IP 及账号名限速。反向代理应覆盖客户端传入的 X-Real-IP，并可使用 PDD_TRUSTED_PROXY 指定应用直接看到的代理 IP。定期用 SQLite 在线备份工具备份挂载卷内的 account.sqlite。

正式 APK 内置私人账号服务地址。个人激活码仅用于创建唯一的账号。后续在两端输入账号和密码即可。本文的 Python 服务面向自行托管，可在构建时通过 `-PaccountServiceUrl=https://你的账号域名` 替换内置地址。未指定该属性的调试包支持在账号页输入测试服务地址。生产安装包只接受 HTTPS。服务地址及账号数据库应跨版本保持稳定。

## 数据

密码使用带独立随机盐的 scrypt 验证。登录时每台设备获得独立会话，有效期 90 天。客户端通过 Android Keystore 保存会话。服务端保存令牌摘要，并按账号限制历史记录的查询与写入。订阅地址使用摘要，影片标识与播放记录通过账号密钥加密后存储。数据库包含账号密钥，数据库及备份需要由运营者保护。

旧版记录在升级后归入游客历史。用户可在账号页主动导入本机游客历史。退出账号后，设备回到游客历史。重新登录可继续使用该账号的缓存。账号使用相同订阅才会合并相应记录，无痕模式暂停同步。离线期间两端同时修改同一条记录时，账号页的「立即同步」允许逐条选择要保留的进度。

## 验证

```sh
python3.12 -m unittest discover -s . -v
```

本机开发可设置 PDD_ACCOUNT_DB 为临时数据库路径，运行 `python3.12 service.py`。测试服务只监听回环地址。Android 调试包可通过 adb reverse 连接此地址。
