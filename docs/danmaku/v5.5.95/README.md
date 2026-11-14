# v5.5.95 验证证据

- `network-probes.json` / `catalogue-probes.json`：显式无代理的服务状态与公开目录探测。
- `direct-network-context.json`：系统代理、实际路由、ADB 设备清单，只有自有 Android 9 模拟器，无用户实体设备。
- `live-before.txt` / `live-direct-final.txt`：修改请求节奏前的真实失败记录，包含 -352；`live-paced.xml`：使用生产适配器和解析器的 12 个真实样本通过记录。
- `unit-summary.json` 与各 Test XML：最终单元测试结果。默认关闭的实网用例已单独开启；初轮空文档夹具失败单独保留。
- `leanback-*` / `mobile-*`：两端运行日志、API lint 与屏幕截图。每端 57 项中 4 项真实模型供应商测试未配置而跳过，实际 53 项通过。
- `direct-links-*`：电视上 B 站、优酷、腾讯、爱奇艺 4 项直链通过。未包含上一版已知不可达的巴哈姆特。

截图背景为测试视频，文字为自动取得的真实弹幕；验收比较屏幕合成的移动像素，未强制调用 View.draw。它不是用户实体设备或实际电影画面的截图。

复现单元测试时设置 CAST_FFMPEG 指向本机 ffmpeg，执行两端 `test*Arm64_v8aDebugUnitTest`。BilibiliDanmakuLiveTest 需在 Gradle Test 配置中显式设置 systemProperty `danmakuLive595=true`。Android 实网测试使用 instrumentation 参数 `-e danmakuLive true`，不需要 AI 密钥；相关用例会检查 VPN 与代理状态。

根因和完整边界见仓库根目录 UPDATE_DANMAKU_REPAIR_REPORT.md 的 v5.5.95 段落。
