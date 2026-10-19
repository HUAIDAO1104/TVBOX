# v5.5.84 自动弹幕 / AI 匹配验证

2026-10-19。Android API 36 ARM64 模拟器，独立 mobile / leanback 构建，真实 DeepSeek V4.1 Flash 接口、真实平台目录及弹幕文档。完整原因与行为见仓库根目录 `UPDATE_DANMAKU_REPAIR_REPORT.md` 顶部。

- `unit-summary.json`：327 项单元测试全通过，另附关键四个测试类的 XML。
- `mobile-matching.log`：35 项通过；`mobile-extra.log`：公开播放器 AI 入口和两个客户端回归，共 3 项通过。
- `leanback-matching.log`：37 项通过、1 项模型批量测试连接超时。`leanback-live-bridge-timeout.log` 保留同通道再测超时；`leanback-live-retest.log` 改用 ADB reverse 测试转发后，8 组真实模型到文档链路全部通过。应用代码相同，测试转发调整不属于产品修改。
- `*-logcat.txt` 仅保留相关匹配状态、正确视频 ID、耗时和实际移动像素检查；不保存设备完整日志或密钥。
- `live-model-calls.jsonl`：脱敏的真实模型输入片名/集期、结构化回复和 token 数，包含排查阶段的请求，不全部计为最终通过次数。
- `model-clean-live.json`：初始五个关键词清理实测；`pool-before.log`：修复连接恢复前失败的可控回归。
- `initial-mobile-matching.log`：扩大范围初测，包含已修复的腾讯季数丢失和未通过的 B 站直链接口。`bili-upstream.json` 是独立公开接口 412 探测，不能推断用户所在地的状态。
- 四张 `*-timeline.png`：腾讯/芒果自动匹配后实际屏幕，黑底为专用静音长视频夹具；测试还检查 1/10/40 分钟、暂停继续、全屏和后台恢复后的移动像素。

手机 AI 阶段 3.2–7.8 秒、电视单独复测 2.3–6.9 秒均为当前网络及混合缓存条件。另测播放器从常规失败转 AI 的完整入口为手机 18.8 秒、电视 17.0 秒。常规自动匹配的亚秒数据来自预热缓存，不能当作首次加载速度。没有连接用户实体手机或电视。

正式四个 ABI/设备组合的签名、版本、提交及发布资产校验保存在本机 `Release/v5.5.84/`，以最终构建与发布结果为准。
