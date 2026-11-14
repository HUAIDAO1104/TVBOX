# v5.5.96 验证记录

2026-11-14。本次处理自动弹幕错集，重点为旧集数绑定和不透明第三方 ID 的正文缓存。完整说明见项目根目录 UPDATE_DANMAKU_REPAIR_REPORT.md。

- `episode17-evidence.json`：真实官方目录的第 17 集原版/重制版 ep ID、CID、标题、时长，以及下载正文的计数。未保存或提交完整评论文本；关键词计数仅用于排查，不能作为剧情匹配算法。
- `network-context.json`：当次本机系统代理、环境代理存在性、物理网卡路由和 Android 代理配置。仅代表本机网络，不代表用户家庭环境。
- `*-cache-instrumentation.txt` / `*-cache-log.txt`：两端 3 项定向回归，包含第 17 集错绑第 100 集、第三方旧正文替换、候选确定在下载之前。
- `*-regression-instrumentation.txt` / `*-regression-log.txt`：手机/电视完整运行回归。电视整轮保留 1 项夹具失败，修正后的 3 项定向复测见 `leanback-restore-*`，未覆盖原失败。真实模型调用未配置端点的用例明确跳过，未使用用户密钥；可控 AI 调度仍执行。
- `*-original-17.png` / `*-remaster-17.png`：实际播放器合成截图；真实弹幕配合黑色视频测试夹具，检查真实移动文字像素，没有用 View.draw 强制制造成功。
- `BilibiliTimelineTest.xml`、`VodPlaybackMediaTest.xml`、`DanmakuHttpTest.xml`、`unit-summary.json`：JVM 回归及总数。CAST_FFMPEG 已配置，包含项目原有离线解码检查。
- `*-api-lint.xml`：NewApi/InlinedApi 检查。`initial-full-lint.xml` 保存误触发的全量 lint 结果，其调试清单 3 项错误未在本次修改。
- `initial-leanback-*`：初轮平台 JSON -352 拒绝的原始失败记录，不计为通过。

平台可能继续限流或拒绝请求；最终通过不证明其永久可用。没有连接用户实体设备，也没有用户正在看的完整源信息，不能宣称该现场的唯一错误地址已查明。错误缓存注入测试是受控复现，不是从用户电视提取的数据。构建、签名升级和线上六项发布资产校验记录保存在本地 Release/v5.5.96/。
