# 搜索结果页修复记录

> 归档说明：本文记录审查或早期修复时的状态。合并后的实现与验收请查看 [全局修复报告](UX_FIX_REPORT_2026-09-06.md) 和 [发布清单](UX_RELEASE_CHECKLIST_2026-09-06.md)。

日期：2026-09-06。

## 已完成

- 遥控器焦点移动到左侧搜索源后，立即切换右侧结果，无需再按确认。
- 焦点保留在来源列表，支持继续上下切换；触屏点击和确认键仍可使用。
- 左侧每个源及“全部”的数字，直接读取该分组实际展示的卡片数。
- 搜索结束时保留当前选中的源，避免左侧变成“全部”而右侧仍为单源内容。

## 两个问题的原因

### 切源需要额外确认

原 `SearchSourceFamilyAdapter` 仅在点击事件中调用选择回调，焦点事件只做缩放动画。现已接入焦点选择，并通过一次主线程队列投递避免在 RecyclerView 布局过程中直接刷新适配器。执行前确认该行仍有焦点、未被回收且仍代表同一个源，快速移动不会由过期回调切回旧源。

依据：[SearchSourceFamilyAdapter.java:82](app/src/leanback/java/com/fongmi/android/tv/ui/adapter/SearchSourceFamilyAdapter.java#L82)。

### 显示十几条，实际只有几条或零条

旧流程存在两套统计口径：

1. `SiteViewModel.filterRelevant` 先进行较宽松的关键词包含检查。
2. 结果页对进入快照的条目直接累加左侧计数。
3. 右侧 `SearchAggregator.addUnaggregated` 再进行严格相关性过滤，并按稳定来源条目标识去重。

因此，预告、花絮等能通过初步检查的条目，以及重复结果，会计入旧数字，却不会成为右侧卡片。若整批条目都被第二次过滤，数字可能非零而实际为空。这里的差异是计数阶段不同，不是右侧只渲染了首屏几张卡片。

本次删除独立的原始条数缓存，以每个分组的 `workCount()` 为唯一计数来源。原有相关性规则保持不变；仍处于全局搜索中且暂时为零时保留“…”等待标记，搜索结束后显示实际数字。

依据：[SiteViewModel.java:262](app/src/main/java/com/fongmi/android/tv/model/SiteViewModel.java#L262)、[SearchAggregator.java:90](app/src/main/java/com/fongmi/android/tv/ui/search/SearchAggregator.java#L90)、[CollectActivity.java:584](app/src/leanback/java/com/fongmi/android/tv/ui/activity/CollectActivity.java#L584)。

此结论由当前代码路径和模拟器固定数据复现支持；未连接用户实际仓库复查具体片名，因此不能逐条断言某个真实源的结果被哪条规则排除。

## 验证结果

| 检查 | 结果 |
|---|---|
| Leanback arm64 Debug JVM 单元测试 | 244 项通过，0 失败、0 错误、0 跳过 |
| API 36 Android TV 搜索结果页仪器测试 | 4 项通过 |
| Leanback arm64 Debug APK | 构建通过 |
| Mobile arm64 Debug Java 编译 | 通过；该 flavor 复用本次修改的 leanback 页面 |
| Leanback arm64 Debug Lint | 通过，0 error、288 warning；未进行全项目 warning 清理 |
| `git diff --check` | 通过 |

仪器测试使用真实 `CollectActivity` 和 RecyclerView 布局、焦点系统，注入固定搜索快照，不依赖第三方站点可用性。覆盖：

- 甲源原始三条（重复正片两条、预告一条），展示与计数均为一条。
- 乙源只有花絮，展示为空、数字为零，空态面板正确显示。
- 丙源同名但不同 ID 的两条正片均保留；“全部”共三条。
- 同一主线程回合内快速从甲移到丙，只切换到最终停留的丙，焦点保留。
- 增量加入重复结果、替换完整快照时，数字与实际卡片数继续一致。
- 搜索完成时，当前源、左侧激活行、右侧结果同步。

测试代码：[SearchResultInteractionInstrumentedTest.java](app/src/androidTest/java/com/fongmi/android/tv/ui/SearchResultInteractionInstrumentedTest.java)。

原有“焦点不切源、确认才切源”的测试已由上述新行为回归替代。编译仪器测试时还修复了一个已有测试替身遗漏 `onDanmakuStatusChanged` 的问题，仅补空回调，没有修改播放器逻辑。

Gradle 的 connected 测试入口在获取测试运行依赖时等待网络，本次改为通过 ADB 安装已构建的主 APK 和测试 APK，直接运行相同的 AndroidJUnitRunner，返回 `OK (4 tests)`。没有将 Gradle connected 任务记录为通过。

## 构建产物与范围

[TV arm64 Debug APK](app/build/outputs/apk/leanbackArm64_v8a/debug/app-leanback-arm64_v8a-debug.apk)。

这是 Debug 测试产物。本次未生成正式 Release、未提升版本号、未发布更新；真机不同 Android 版本、第三方仓库实际返回及 armv7 安装包不在此次验证范围。

项目其他优化建议已独立整理为 [设计与体验优化报告](PROJECT_UX_REVIEW_2026-09-06.md)，共 12 项，包含依据、优先级、建议与验收条件。
