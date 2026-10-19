# v5.5.86 验证证据

- `android9-before.log` / `android9-crash-stack.txt` / `crash-mapping.txt`：原 5.5.85 APK 在 SDK 28 上的崩溃复现与发布映射。
- `ai-backup-before.log`：失效弹幕地址导致 AI 流程提前结束的修复前失败。
- `unit-summary.json`：337 项单元测试，无失败或跳过。
- `mobile-matching.log` / `leanback-matching.log`：最终 SDK 28 两种构建各 51 项完整回归通过。
- `*-matching-detail.log`：实际选中的平台视频 ID、模型归一化、时间和移动像素证据。
- `*-tls.log`：Android 9 自身对模型网关的 HTTPS 连接检查，各 1 项通过，不使用真实 Key。
- `live-model-calls.jsonl`：实际模型回复、用量与耗时；无密钥。最终两端每轮各覆盖 8 组片目／集期，外加播放器公开入口回退。
- `*-timeline.png`：最终两种构建的真实屏幕截图；黑色背景是两小时静音测试视频，白字是实际平台弹幕。
- `binary-guard-before.log` / `binary-guard-release.log`：旧包被拒绝、本轮四种正式构建通过字节码检查。
- `*-api-lint.txt`：API 兼容检查，无错误。
- `leanback-initial-matching.log` / `tv-playback-omx*.log`：保留首次失败与解码后端诊断实验，不计为成功；见主报告中的原因与复测说明。
- `tv-playback-24fps.log`：恢复默认 Codec2 后，标准 24 fps 测试视频的两条平台播放链路通过。

设备为 Android 9 ARM64 模拟器，独立安装 mobile / leanback 构建。未连接用户实体设备；ARMv7 包做构建、签名与字节码检查，未作 ARMv7 运行实测。网络时延含缓存，不能作为用户家庭网络速度保证。
