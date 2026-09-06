# 项目设计、交互与使用体验优化报告

> 归档说明：本文记录审查或早期修复时的状态。合并后的实现与验收请查看 [全局修复报告](UX_FIX_REPORT_2026-09-06.md) 和 [发布清单](UX_RELEASE_CHECKLIST_2026-09-06.md)。

后续专项审查：[全局功能体验、交互与响应速度报告（24 项，含已落实的搜索优化和性能对照）](GLOBAL_PERFORMANCE_REVIEW_2026-09-06.md)。

日期：2026-09-06。范围：当前 Android TV 界面及其共用的 mobile 构建，重点检查首页、搜索、结果页、历史记录、网盘登录和仓库管理。本文是代码与布局审查；搜索切源和计数已在 API 36 TV 模拟器验证，其余条目按代码事实或待验证风险标注，未声称完成全项目真机体验测试。

本次指定的“焦点移入即切源”和“结果数字与展示一致”已经修复；详细原因和验证见 [搜索结果页修复记录](SEARCH_RESULT_FIX_2026-09-06.md)。以下为独立的后续建议，尚未修改这些产品行为。

## 优先级概览

P1：建议优先处理，涉及误操作、来源准确性或无法结束的等待。P2：影响日常体验和操作效率。工作量是实现范围的粗估，不是工期承诺。

| 编号 | 优先级 | 问题 | 涉及维度 | 工作量 |
|---|---|---|---|---|
| 01 | P1 | 清空观看历史直接执行，首页重复长按也可能清空 | 交互、数据保护 | 小 |
| 02 | P1 | 搜索源按名称子串匹配，具体来源可能互相混入 | 逻辑、结果准确性 | 中 |
| 03 | P1 | 网盘发现和登录缺少界面任务超时 | 等待反馈、可靠性 | 中 |
| 04 | P2 | 单源空结果与全局无结果使用相同提示 | 状态设计、理解成本 | 中 |
| 05 | P2 | 重试清空已有内容，并重搜所有勾选源 | 效率、稳定性 | 中 |
| 06 | P2 | 单条结果展示与“其他来源”面板的数据口径不一致 | 信息架构、操作效率 | 中 |
| 07 | P2 | “4K”标签来自源名称，未验证单条视频画质 | 信息可信度 | 小至中 |
| 08 | P2 | 从播放返回历史页时，历史内容没有主动刷新 | 状态同步 | 小 |
| 09 | P2 | 搜索建议逐字请求，缺少防抖和请求取消 | 输入体验、性能 | 小 |
| 10 | P2 | 搜索源及卡片长标题被截断，小字号影响远距离阅读 | 视觉、可读性 | 小至中 |
| 11 | P2 | mobile 共用 TV 搜索界面时仍关闭系统输入法 | 触屏输入体验 | 中 |
| 12 | P2 | 仓库详情点击刷新后缺少即时进行中反馈 | 操作反馈 | 小 |

## 01｜为清空历史提供明确的范围与撤销

**代码确认。** 历史页“清空”直接调用 `History.delete(VodConfig.getCid())`。首页长按先进入删除模式，再次长按会清空当前配置的历史；同一个手势在不同模式下具有明显不同的后果。

建议：保留长按进入管理模式，清空改为明确入口；显示“清空当前内容配置的观看记录，共 N 条”，配合确认或短时撤销。单条删除可以使用撤销，减少连续删除时的弹窗。

验收：普通长按和重复长按不会意外清空；删除范围与提示一致；撤销后播放进度和条目恢复。

依据：[HistoryActivity.java:48](app/src/leanback/java/com/fongmi/android/tv/ui/activity/HistoryActivity.java#L48)、[HomeActivity.java:1007](app/src/leanback/java/com/fongmi/android/tv/ui/activity/HomeActivity.java#L1007)。

## 02｜用稳定来源标识筛选具体搜索源

**匹配方式由代码确认，实际混入取决于配置内容。** `isEnabled` 将站点名、配置名、仓库名和 key 拼在一起，再判断是否包含选中的名称。例如选中“甲源”时，“甲源Plus”也可能被包含；配置名命中时还可能连带其他站点。

建议：具体搜索源的选中值保存为站点稳定标识；名称仅用于显示。将“默认来源推荐”的模糊匹配与“用户明确选择某个源”的精确匹配分开，并迁移现有偏好。

验收：使用名称相近、同名但 key 不同、仓库名包含来源名的配置测试；各源归属可预测，“全部”按唯一来源条目统计，选择不会随改名丢失。

依据：[SearchSourcePreference.java:55](app/src/main/java/com/fongmi/android/tv/ui/search/SearchSourcePreference.java#L55)。

## 03｜为网盘入口发现和登录增加超时、取消与重试

**代码确认缺少页面级超时，是否长期卡住取决于 Spider。** `loadRepositoryLoginRoutes` 和 `onRepositoryLogin` 使用普通 `Task.submit`，直接调用外部站点逻辑；开始后禁用刷新，结束后恢复。页面销毁时有取消操作，但停留在页面时没有有界等待保障。

建议：入口发现和登录分别设置合理的超时；等待期间显示当前操作及取消入口；超时后恢复操作，保留已有账户列表。超时后迟到回调必须按任务标识丢弃。

验收：模拟永久不返回、超时后返回、退出页面后返回；均不会无限禁用操作或覆盖新任务的界面。

依据：[CloudAccountActivity.java:80](app/src/leanback/java/com/fongmi/android/tv/ui/activity/CloudAccountActivity.java#L80)、[CloudAccountActivity.java:130](app/src/leanback/java/com/fongmi/android/tv/ui/activity/CloudAccountActivity.java#L130)。

## 04｜区分“这个源没有结果”与“所有源都没有结果”

**代码确认。** 当前展示内容取自选中的源，但运行、完成、超时、失败状态来自全局 `SearchProgress`。某源已经返回空结果，其他源仍在运行时，该源仍可能显示搜索中；选中空源后，空态文案也没有说明其他源是否已有结果。

建议：为每个实际搜索源维护等待、搜索中、有结果、无匹配、超时、失败状态。当前源为空而其他源有结果时，显示“当前源未找到匹配内容”，提供“查看全部 N 条”；全局都为空时再建议改关键词。

验收：A 源为空、B 源有结果、C 源超时这三类状态同时存在时，各自显示准确，切换不会让用户误以为所有结果丢失。

依据：[CollectActivity.java:584](app/src/leanback/java/com/fongmi/android/tv/ui/activity/CollectActivity.java#L584)、[CollectActivity.java:734](app/src/leanback/java/com/fongmi/android/tv/ui/activity/CollectActivity.java#L734)。

## 05｜重试失败源时保留已获得的结果

**代码确认。** 重试调用 `resetAndSearch(true)`，清空缓存、卡片和选择锚点，再对所有勾选源发起搜索。只想重试一个失败源时，也会失去已经浏览的内容与位置。

建议：有结果时提供“重试失败源”，保留已成功结果及当前焦点；单源空态可提供“重试当前源”。“重新搜索全部”作为独立操作保留。

验收：浏览到第二屏时重试失败源，当前卡片和滚动位置保持；新结果追加；同一源重复返回同一条目不会多算。

依据：[CollectActivity.java:210](app/src/leanback/java/com/fongmi/android/tv/ui/activity/CollectActivity.java#L210)。

## 06｜让“选择其他来源”真正列出该作品的替代来源

**代码确认。** 当前每个分组（包括“全部”）通过 `addUnaggregated` 展示一条来源一个卡片；但长按、菜单键及最右侧卡片的右键，仍打开 `work.rankedSources()` 面板。这些卡片通常只有一个来源，后台用于播放失败兜底的跨源分组没有用于这个面板。

建议：保留目前按源浏览的卡片方式，打开来源面板时查询保守聚合后的同作品候选；只有一个来源时不打开冗余面板。不要仅凭同名把不同年份、季数或版本合并。

另建议把顶部“X 部作品 · Y 个来源”改为与当前数据含义一致的文案，例如“X 条结果 · 来自 Y 个搜索源”；当前 `sourceCount()` 统计的是来源条目，不是去重后的站点数量。

验收：同作品三源可手动选择三个来源；同名不同季不串联；单源时不会出现只有一个选项的“其他来源”操作。

依据：[CollectActivity.java:477](app/src/leanback/java/com/fongmi/android/tv/ui/activity/CollectActivity.java#L477)、[CollectActivity.java:840](app/src/leanback/java/com/fongmi/android/tv/ui/activity/CollectActivity.java#L840)、[SearchWorkAdapter.java:133](app/src/leanback/java/com/fongmi/android/tv/ui/adapter/SearchWorkAdapter.java#L133)。

## 07｜区分来源特色与视频实际画质

**代码确认。** `isFourKDefault` 根据四个预设源名称判断是否添加“4K”，并将其用于来源行和卡片来源文字；该标签不是从本条视频分辨率得出。

建议：来源分类可以显示“高画质源”等来源特色；卡片上的 4K 应来自条目元数据或播放信息。尚未验证时避免让来源标签看起来像当前影片的画质承诺。

验收：同一来源的 1080p 与 4K 条目不会被统一标成 4K；未知画质不会显示确定结论。

依据：[SearchSourcePreference.java:121](app/src/main/java/com/fongmi/android/tv/ui/search/SearchSourcePreference.java#L121)、[SearchWorkAdapter.java:133](app/src/leanback/java/com/fongmi/android/tv/ui/adapter/SearchWorkAdapter.java#L133)。

## 08｜从播放页返回时刷新历史，并保留焦点

**代码确认。** `HistoryActivity` 在初始化与删除后调用 `loadHistory`，没有返回页面时的刷新或数据库观察。因此播放后返回已有历史页，顺序、剧集或进度的最新值可能不会及时反映。

建议：观察历史数据变化，或在返回前台时增量刷新；保存当前条目标识，刷新后恢复焦点与滚动位置，删除最后一项时回到可操作入口。

验收：播放一集后返回，最新剧集与进度更新；更新不把用户带回列表第一项。

依据：[HistoryActivity.java:38](app/src/leanback/java/com/fongmi/android/tv/ui/activity/HistoryActivity.java#L38)、[HistoryActivity.java:56](app/src/leanback/java/com/fongmi/android/tv/ui/activity/HistoryActivity.java#L56)。

## 09｜搜索建议增加防抖与生命周期取消

**代码确认。** 每次 `afterTextChanged` 都调用 `getWord/getSuggest`；已有“结果对应当前关键词”的校验，可以防止多数旧结果覆盖，但没有取消旧网络请求，也没有输入防抖。

建议：输入停顿约 200–300 ms 再请求建议，取消前一请求；清空输入立即展示本地热词缓存；离开页面取消请求并校验页面生命周期。

验收：快速输入十个字符不发出十次建议请求；删除、连续修改关键词、离开再返回时没有闪回旧建议。

依据：[SearchActivity.java:111](app/src/leanback/java/com/fongmi/android/tv/ui/activity/SearchActivity.java#L111)、[SearchActivity.java:228](app/src/leanback/java/com/fongmi/android/tv/ui/activity/SearchActivity.java#L228)。

## 10｜改善长标题与远距离可读性

**布局事实确认，实际可读性需要真机验证。** 来源名称仅一行、字号 12sp，数字区固定 23dp、字号 10sp；卡片标题也为单行省略。长来源名、三位以上结果数、相同前缀但不同版本的标题容易难以区分。

建议：数字区随位数适度伸缩；焦点所在行展示完整来源名；卡片聚焦时展示完整标题或补充说明。提供适合远距离观看的字号选项，并让列数随可用宽度和字号变化。

验收：在 720p、1080p、4K、不同系统字体倍率与电视安全区设置下检查；长标题可完整读取，数字不省略，放大不遮住邻近卡片。

依据：[adapter_search_source_family.xml:35](app/src/leanback/res/layout/adapter_search_source_family.xml#L35)、[adapter_search_work.xml:50](app/src/leanback/res/layout/adapter_search_work.xml#L50)。

## 11｜为 mobile 构建保留更高效的触屏输入

**代码确认配置现状，体验需在手机实测。** mobile 构建已经复用 leanback 的 Java 与资源，搜索页仍无条件 `setShowSoftInputOnFocus(false)`。这保持了横屏视觉一致，但触屏用户点输入框不会按通常方式弹出系统键盘。

建议：保持统一视觉，根据输入设备允许触屏直接使用系统键盘，遥控器保留屏幕键盘；软键盘出现时保持搜索提交按钮和输入内容可见。

验收：手机点击输入框可以直接输入中文并提交；TV 遥控器路径不受影响；外接键盘、触屏和方向键交替使用不丢焦点。

依据：[app/build.gradle](app/build.gradle)、[SearchActivity.java:99](app/src/leanback/java/com/fongmi/android/tv/ui/activity/SearchActivity.java#L99)。

## 12｜仓库详情刷新应立即显示进行中

**代码确认。** 仓库详情的刷新监听只处理成功和失败，没有在 `onStart` 重绘；同步管理器虽会设置 SYNCING 并防止重复任务，但页面可能仍显示旧状态，用户会反复确认是否点到了。

建议：接入 `onStart`，立即显示“正在刷新”，禁用重复操作；成功或失败后恢复。保留“使用缓存”的区别，避免把离线可用误报为完全不可用。

验收：模拟 5 秒响应与网络失败，点击后立即有反馈；不能重复排队；失败后还能访问已有缓存。

依据：[RepositoryDetailActivity.java:71](app/src/leanback/java/com/fongmi/android/tv/ui/activity/RepositoryDetailActivity.java#L71)。

## 建议实施顺序

1. 优先修正清空历史的误操作路径、搜索源身份匹配、网盘有界等待。
2. 接着完善单源状态、定向重试、手动选择其他来源，统一结果页的信息含义。
3. 再处理历史返回刷新、输入防抖、来源画质标签和仓库刷新反馈。
4. 最后进行电视观看距离、字体、长标题与手机输入的专项真机验收。

当前没有修改以上建议对应的产品行为。直播频道、不同播放器内核、第三方仓库与网盘账号的完整联调未在本次审查中覆盖，不能据此判断其全部功能已经通过验收。
