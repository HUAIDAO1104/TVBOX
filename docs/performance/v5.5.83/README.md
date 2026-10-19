# v5.5.83 验证证据

- JVM：`DanmakuAiModelsTest` 4 项、`DanmakuAiProtocolTest` 5 项通过。
- API 36 ARM64：`mobile-tests.log` 16 项通过，4 项跳过；`leanback-tests.log` 17 项通过、2 项跳过、1 项粘贴未完成，`leanback-paste-retest.log` 原用例复测通过。付费实网匹配选测未注入真实凭据，不计作通过。
- 最终下拉交互：`mobile-dropdown-final.log`、`leanback-dropdown-final.log` 各 2 项通过，含真实输入、模型 ID 保存、网页列表失败与取消恢复、过期响应忽略。
- 初期测试问题：`mobile-tests-first.log` 为布局前立即选择；`dropdown-input-before-window-ready.log` 为窗口获得焦点前发出输入。最终测试先等实际窗口、坐标和焦点，记录没有删除。
- `default-model-smoke.json`：唯一一次真实模型调用的摘要，2.781 秒、208 token。`provider-models-summary.json` 只保留已核实的预设 ID 和接口状态。
- `production-smoke.json`：正式电视包经实际配对端口的读取、配对、保存与回填。使用模拟 Key；ADB 转发不等同用户家庭网络实测。
- PNG 分别是手机／电视模型下拉、网页原生选择器和正式包保存状态；网页测试候选中的 Fixture model 是受控数据。
- 静态设计审计 `premium-audit.json` 无阻断项；`scripts/check_mobile_design.py` 通过。

本轮聚焦模型配置。默认真实接口已验证，不把聚合服务商的模型快照、价格、响应时间推广为所有用户的保证；完整说明见仓库根目录 UPDATE_DANMAKU_REPAIR_REPORT.md。
