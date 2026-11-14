# v5.5.98 验证记录

完整修复说明见仓库 UPDATE_DANMAKU_REPAIR_REPORT.md 的 v5.5.98 段。

- filename-baseline.xml：原解析代码把用户提供的第 22 集文件名识别成 73。
- baseline-unit.log：旧实现重复 URL 跨集绑定、旧别名污染的失败。
- leanback-baseline-log.txt：修复前开发 APK 的实际播放器标签 17 / URL 73 重现；不是声称下载并测过正式 5.5.97 发行包。
- first-instrumentation：首轮测试夹具污染及一次并行构建期间的 215 ms 样本，未放宽性能阈值。
- regression：手机最终 32 项通过；电视 31 项通过，真实显示用例曾被受控正文污染。
- leanback-isolated：清理夹具后功能和响应 3 项通过；实际联网收到 -352。
- leanback-live-final：保持安装状态，超过平台冷却时间后单项重试通过；未变更端点、代理或身份。
- actual-22.png：真实平台第 22 集弹幕，背景是黑色视频夹具。

Android 测试运行参数 danmakuLive=true。主体套件是 DanmakuRoutingInstrumentedTest、DanmakuEpisodeTransitionInstrumentedTest，及播放器、手动搜索的定向用例。JVM 与 API lint 汇总见 validation.json、unit-results.json 和两端 api-lint.txt。

没有用户实体设备或家庭网络验收，不将服务临时恢复归因于本修复。发布包签名、覆盖升级及资产校验另存 Release/v5.5.98/。
