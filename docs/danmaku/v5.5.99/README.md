# v5.5.99 验证记录

本次修复复杂文件名回退第 1 集，加入搜索前的 AI 集数判断，默认模型 deepseek-v4.1-flash-1017。

- `final-unit-counts.json`：全量 JVM 电视 411 / 手机 448 项；各跳过 1 项，其余零失败。
- `final-target-counts.json`：最后补充英文 Part B、Bonus 限制后，集数计划测试两端各 5 项通过。
- `*-final-instrumentation.txt` / `*-final-log.txt`：Android 9 两端各登记 32 项，26 项执行通过、6 项旧实网 AI 测试因本轮未传端点按条件跳过。新增 8 项 AI 前置流程均执行通过；API 29 下拉 UI 用例不在 Android 9 执行集合内。
- `*-initial-*`：保留模型超时测试的初轮失败；原断言叠加了外部备用搜索时间，改为先建立已确认目录以隔离模型 8 秒预算后通过，未修改生产限时。
- `provider-models.json`：服务商真实模型列表摘录。`model-*-probe*.json`、`gateway-*-probe.json`：实际接口对推理控制的响应，保留 null/length 的失败和 effort=none 后成功；没有推理正文或密钥。
- `*-live-*`、`*-actual-ai-22.png`：真实模型先判断“序号01【4K】凡人修仙传 22 1-73集”，再由真实平台下载第 22 集，并检查合成屏幕移动像素。视频是黑色测试视频，不是用户片源；截图只证明弹幕渲染。
- 手机首次实网遇到 B 站 -352，原始记录保留，冷却后保持相同设备身份重试。不能把外部拒绝归因于模型或把后续服务恢复宣称为客户端修复。
- `network-context.json`：系统、环境和 Android 无 VPN/全局代理。AI 通过本机回环测试转发器代填环境凭据，原请求参数直接送入配置供应商 HTTPS 接口；弹幕由 Android 直接请求平台。
- `*-api-lint.txt` / `build-verification.txt`：API 检查零错误，两端各一条既存警告；四种正式包最终 DEX 均无不兼容流 API 调用。

实体手机、电视及用户家庭网络不可连接，本目录不能视为实体设备验收。正式签名、版本、来源提交、ABI、覆盖升级及上传散列另由发布流程核验。
