# 全局功能体验、交互与响应速度审查

> 归档说明：本文记录审查或早期修复时的状态。合并后的实现与验收请查看 [全局修复报告](UX_FIX_REPORT_2026-09-06.md) 和 [发布清单](UX_RELEASE_CHECKLIST_2026-09-06.md)。

日期：2026-09-06。重点是操作是否及时响应、等待是否有界、已有内容是否保留，以及功能是否按用户预期工作。范围覆盖首页启动与推荐、搜索输入与全源结果、分类筛选、详情和播放、直播切台、弹幕、历史收藏、仓库与设置。

搜索部分完成了代码修改和 Android TV 模拟器对照测试；其他模块为代码链路审查，下面明确区分“已修改”“代码确认”和“待压力测试”。没有把第三方站点的实际网络速度、真实电视帧率或所有播放内核的表现当作已验证结果。

## 先解释“全部源为什么慢”

这里有两个不同操作：

- **左侧选“全部”**：只显示已选择来源的缓存结果，不会因此发出一轮新搜索；卡片更多，界面数据处理和布局负担更高。
- **搜索源筛选里勾选所有源**：会让所有符合条件、经后端去重的站点参与搜索，等待队列显著增加。

目前主要瓶颈有两层：

1. **请求排队。** `type=3` 的 Spider 搜索队列只允许一个原生任务实际运行，普通 HTTP 源另有三个并发槽。Spider 的超时通知为 30 秒，但忽略中断的调用可能继续占用物理槽，后面的源无法启动。已有测试专门验证并保留了这个保护，因为直接释放槽可能让不安全的原生代码并发执行。假设 20 个串行源各需 3 秒，完整搜索约需 60 秒，这是队列示例，不是对实际仓库的测量。
2. **界面处理。** 一批数据到来后，主线程还要归一化标题、进行相关性判断、匹配来源分组、维护作品和海报、刷新列表。原先每一条结果都遍历全部来源，多个分组重复解析同一个关键词，同一作品的海报也逐条重复更新。

因此需要分别优化“第一批可看的结果出现时间”“当前操作响应时间”“所有源完成时间”。只减少动画时间，或只调大线程池，不能完整解决这个问题。

依据：[搜索调度](app/src/main/java/com/fongmi/android/tv/model/SiteViewModel.java#L126)、[原生队列限制](app/src/main/java/com/fongmi/android/tv/model/ViewModelSearchRunner.java#L40)、[超时路径](app/src/main/java/com/fongmi/android/tv/model/ViewModelSearchRunner.java#L206)。

## 本轮已实际完成的优化

| 改动 | 现在的行为 |
|---|---|
| 首批快照立即处理 | 第一批返回结果不再固定等待 320 ms；后续流式更新仍合并，避免每个回调都刷新布局 |
| 来源归属按站点复用 | 同一批次中，同一 Site 的结果复用来源匹配，不再逐条扫描所有源 |
| 关键词归一化复用 | 每个聚合器使用已解析的搜索词，避免每加一条结果都重新解析关键词 |
| 海报共享按作品批量更新 | 一批同作品的来源更新完成后，只处理该作品最终状态；完整重建时也只按最终分组处理 |
| 焦点来源优先排队 | 搜索中移到某个源，将它尚未执行的请求移到队列前面；不重复请求、不丢掉其他源，也不强行打断原生调用 |

源码：[结果批处理](app/src/leanback/java/com/fongmi/android/tv/ui/activity/CollectActivity.java#L354)、[聚合器](app/src/main/java/com/fongmi/android/tv/ui/search/SearchAggregator.java)、[队列优先级](app/src/main/java/com/fongmi/android/tv/model/ViewModelSearchRunner.java#L99)。

### 同数据对照结果

环境：同一台 API 36 Android TV arm64 模拟器；固定 40 个来源、每源 20 条，共 800 张结果卡片；使用构造数据，不访问真实搜索源。

| 指标 | 修改前 | 修改后 |
|---|---:|---:|
| 一次大快照同步处理的主线程墙钟时间 | 2450 ms | 603 ms |
| 对应主线程 CPU 时间 | 2399 ms | 577 ms |
| 展示卡片数 | 800 | 800 |
| 各来源计数 | 20 | 20 |

原始指标记录：[性能对照数据](PERFORMANCE_BENCHMARK_2026-09-06.json)。

这个对照样本的同步处理耗时减少约 **75%**。它不是网络搜索提速 75%，也不是多次测量平均值或 P95；样本不含真实海报下载，不能代表弱性能电视的最终帧率。**603 ms 仍然足以造成明显卡顿**，后续需要将重计算移出主线程，不能据此宣称全源搜索已经流畅。

## 全局优化清单：24 项

P1：优先解决明显等待、卡顿或功能缺口。P2：提升高频操作效率、状态一致性与长期稳定性。

### A. 搜索：最值得投入的部分

**01｜P1：处理“超时了但后续源仍不启动”。代码确认，仍待架构优化。**

目前逻辑超时和原生调用实际结束是两回事。应给受控 HTTP 请求接入真正的取消；对不可控 Spider 采用可终止、可重建的隔离执行环境，再决定安全并发范围。短期应明确显示“等待前一来源结束”，允许停止并继续浏览已有内容。

原生队列是各 ViewModel 的实例，并不是整个应用的全局锁；搜索退出、重新进入或进入详情时，还需审查旧原生调用与新页面请求的重叠。不要把简单创建更多线程当作解决方案。

验收：构造忽略中断的源，后续操作必须有明确反馈；HTTP 源继续可用；隔离执行方案下超时源可回收；反复搜索和进入播放没有原生并发崩溃。依据：[ViewModelSearchRunner](app/src/main/java/com/fongmi/android/tv/model/ViewModelSearchRunner.java)、[SiteViewModel](app/src/main/java/com/fongmi/android/tv/model/SiteViewModel.java)。

**02｜P1：把大结果集计算移出主线程。本轮已减少重复计算，仍有剩余瓶颈。**

目前聚合、来源排序、海报索引和部分 Diff 仍同步完成。建议在单独的数据处理执行器上生成不可变的展示快照，主线程仅提交列表；用搜索会话编号丢弃旧结果。大批更新分段交付，优先当前来源和可见区域。

验收：500/1000/3000 条数据返回时，连续按上下键与返回键仍及时响应；按帧记录 P95/P99，核对卡片数、去重规则和焦点恢复，不用减少结果数换取速度。依据：[CollectActivity.appendResults](app/src/leanback/java/com/fongmi/android/tv/ui/activity/CollectActivity.java#L404)。

**03｜P2：首批结果无需等待固定合并窗口。本轮已修改。**

保留后续回调合并，首批快照立即提交。下一步可将固定批次策略改为受主线程耗时预算控制的自适应策略。

验收：源返回第一条有效结果后，不再额外等待原先的 320 ms；后续源集中返回时不会重复全量刷新。已有仪器测试覆盖首批同步可见。依据：[applySearchSnapshot](app/src/leanback/java/com/fongmi/android/tv/ui/activity/CollectActivity.java#L354)。

**04｜P1：用户正在查看的来源应优先执行。本轮已修改队列优先级。**

此前只是切换缓存展示，未返回的来源仍按原始顺序排队。现在焦点切入来源后，会稳定重排尚未启动的请求。已经启动且不响应中断的源仍需要等待，未把这个限制隐藏掉。

验收：先启动 A，B/C/D 排队；用户依次查看 C、D 后，执行顺序为 A/D/C/B，每个源只执行一次。已补充单元测试。依据：[prioritize](app/src/main/java/com/fongmi/android/tv/model/ViewModelSearchRunner.java#L99)。

**05｜P1：标题已经返回，不应再被补图请求阻塞。代码确认，待修改。**

部分 HTTP 类型的 `searchContent` 会先搜索，再同步调用 `fetchPic` 请求详情；第二个请求完成后才交付结果。补图失败还会让整个搜索任务进入失败路径，尽管已有标题和 ID。

建议：先交付可导航的基础条目，再按需补图、补元数据；补全失败保留原结果；处理原有分类过滤和排序规则，不能因分阶段返回造成内容串源或重复。

验收：模拟搜索接口 100 ms 返回、补图接口 5 秒返回或失败，标题应先出现且可进入详情，海报随后更新。依据：[SiteApi.searchContent](app/src/main/java/com/fongmi/android/tv/api/SiteApi.java#L197)、[fetchPic](app/src/main/java/com/fongmi/android/tv/api/SiteApi.java#L230)。

**06｜P1：同关键词再次搜索和页面重建应复用快照。代码确认，待修改。**

结果数据主要在 Activity 内；`initView` 会调用 `resetAndSearch`，即使 ViewModel 仍存在，也会停搜、清空并重新开始。重新打开同关键词亦没有短时结果缓存。

建议：将搜索会话及快照交由 ViewModel/有容量限制的缓存持有，缓存键包括配置、来源选择、关键词及必要的账号状态；命中先展示，再显式刷新或后台更新。

验收：页面重建不重复启动完整搜索；短时间返回同关键词立即显示；切换仓库、账号和来源集合不复用错误内容。依据：[CollectActivity.initView](app/src/leanback/java/com/fongmi/android/tv/ui/activity/CollectActivity.java#L127)、[resetAndSearch](app/src/leanback/java/com/fongmi/android/tv/ui/activity/CollectActivity.java#L211)。

**07｜P1：来源优先级应反映“快速返回有效结果”。代码确认，待修改。**

当前首页来源优先于健康排序，即使它最近失败，也可能先占用队列；成功计时在严格相关性过滤前更新，接口成功但一直返回无关结果的源也可能获得较高健康评价。

建议：在保留用户偏好的同时，将近期超时与无效返回纳入排名；区分“接口返回成功”和“有可展示结果”；慢源放后面，仍保留全源参与，不擅自删除用户勾选项。

验收：故障首页源不再持续挡住快速有效源；恢复后的源可以逐步回到合理优先级；用户主动查看某源仍享有优先权。依据：[compareSearchPriority](app/src/main/java/com/fongmi/android/tv/model/SiteViewModel.java#L183)、[onSearchResult](app/src/main/java/com/fongmi/android/tv/model/SiteViewModel.java#L242)。

**08｜P1：搜索页缺少后续分页入口。代码确认，待功能设计。**

聚合搜索仅使用第一页；底层 SearchTask 和部分 Spider 支持页码，但结果页没有请求后续页的交互。因此“本页加载完成”不等于已经取得该源所有结果。

建议：优先让当前来源在滚动到底部时加载下一页，支持幂等去重与失败重试；明确显示“已加载 N 条”，不要盲目为所有源预取所有页。

验收：返回多页的源能继续浏览；空页、重复页、错误页不死循环；已显示内容和焦点保留。依据：[SearchTask](app/src/main/java/com/fongmi/android/tv/model/SiteViewModel.java#L324)、[CollectActivity](app/src/leanback/java/com/fongmi/android/tv/ui/activity/CollectActivity.java)。

**09｜P1：重试应保留已有结果，优先重试失败或当前源。代码确认，待修改。**

目前重试会清空整个会话再请求全部勾选源。建议拆成“重试当前源”“重试失败源”“重新搜索全部”，对前两者增量合并。

验收：浏览到第 50 张卡片时重试失败源，当前位置与卡片不消失；不会重新请求所有已成功源。依据：[resetAndSearch](app/src/leanback/java/com/fongmi/android/tv/ui/activity/CollectActivity.java#L211)。

**10｜P1：每个来源应有独立的排队、搜索中、无结果、失败状态。代码确认，待修改。**

目前当前源卡片数量与全局进度组合显示状态；已返回空结果的源仍可能显示“…”；失败和真正无匹配也不易区分。

建议：维护按站点的任务状态，显示正在查哪个源、已得到多少条；空源可直达“查看全部”，失败源可单独重试。保留有结果即可播放的行为，不要求等待全部结束。

验收：A 有结果、B 排队、C 失败、D 无匹配时同时显示准确状态。依据：[renderState](app/src/leanback/java/com/fongmi/android/tv/ui/activity/CollectActivity.java#L758)。

**11｜P2：切回某个源应回到上次浏览的位置。代码确认，待修改。**

`onSelect` 清除当前卡片锚点并 `scrollToPosition(0)`。检查多个源的不同版本时，每次回来都要重新找。

建议：每个源保存最后卡片 ID 和 LayoutManager 状态；源切换时恢复，数据更新后按稳定 ID 兜底；新关键词清空这些状态。

验收：A 源滚动到第二屏，切 B 再回 A，仍停留在原卡片；焦点在左侧时不被恢复动作强行拉到网格。依据：[onSelect](app/src/leanback/java/com/fongmi/android/tv/ui/activity/CollectActivity.java#L603)。

### B. 高频输入与浏览操作

**12｜P2：分类多个筛选条件应合并请求，并保留旧内容。代码确认，待修改。**

每次选择年份、类型等条件都会立即刷新，`checkFilter` 移除旧卡片。连续设置三个条件，会产生被替换的请求和多次空白。

建议：短暂合并连续选择或增加明确的应用按钮；请求期间保留旧列表并标示更新中，成功后替换，失败可保留旧结果。

验收：连续变更三个条件只执行最终需要的查询；请求失败不会丢失已有浏览内容。依据：[TypeFragment.setClick](app/src/leanback/java/com/fongmi/android/tv/ui/fragment/TypeFragment.java#L220)、[checkFilter](app/src/leanback/java/com/fongmi/android/tv/ui/fragment/TypeFragment.java#L325)。

**13｜P2：关键词建议请求应防抖、取消和绑定页面生命周期。代码确认，待修改。**

当前逐字触发网络建议；已有当前关键词校验，但没有取消旧请求。建议输入停顿后请求，离开页面取消，缓存最近建议。

验收：快速输入十个字符不会产生十个仍在执行的请求；返回、清空、重输相同关键词时，旧回调不覆盖当前内容。依据：[SearchActivity](app/src/leanback/java/com/fongmi/android/tv/ui/activity/SearchActivity.java#L111)。

**14｜P2：热启动或已有内容时减少品牌遮罩等待。代码确认，产品取舍待确定。**

首页带最短约 1400 ms 的品牌动画展示逻辑，并在遮罩期间拦截按键。内容已准备好时，这部分属于人为增加的可操作等待。

建议：完整品牌动画保留在首次冷启动；后续启动缩短，或让用户按确认跳过并聚焦可用内容。

验收：单独测量“进程启动到可操作时间”，不能只测第一帧；缓存命中时不用等固定动画结束。依据：[HomeActivity.dismissBrandSplash](app/src/leanback/java/com/fongmi/android/tv/ui/activity/HomeActivity.java#L537)。

**15｜P1：首页补简介不应连续占用多个来源请求。代码确认，待优化。**

推荐信息补全可能依次尝试豆瓣、直接详情、最多四个候选源的搜索及详情。已有焦点防抖和进程内详情缓存，但每个未命中条目仍可能触发长链路，并使用通用任务池。

建议：先稳定显示现有标题和海报，元数据分级补全；焦点离开取消不再需要的补全；命中数据缓存包含来源与有效期；补简介任务的优先级低于用户真实播放请求。

验收：快速扫过十张卡片不启动十条完整补全链；进入播放不被简介获取拖延。依据：[HomeFeaturedViewModel.resolveInternal](app/src/leanback/java/com/fongmi/android/tv/model/HomeFeaturedViewModel.java#L46)。

### C. 详情、播放、切台与弹幕

**16｜P1：自动换源需要整体等待预算和清晰阶段反馈。代码确认，实际时延需故障测试。**

已经有候选数量上限、取消操作和阶段提示，但详情、播放解析、其他来源搜索各自等待，缺少贯穿一轮换源的整体时限。多个慢阶段叠加时仍可能感觉一直转圈。

建议：记录当前阶段耗时、已尝试来源数，给整轮自动恢复设置预算；超出后保留已知候选供用户选择，而非继续长时间自动尝试。

验收：详情失败、解析失败、播放失败组合发生时，总等待可预测；取消立即关闭等待交互，迟到结果不重启播放。依据：[VideoActivity](app/src/leanback/java/com/fongmi/android/tv/ui/activity/VideoActivity.java)、[DetailSourceFallbackPolicy](app/src/main/java/com/fongmi/android/tv/playback/vod/DetailSourceFallbackPolicy.java)、[Constant](app/src/main/java/com/fongmi/android/tv/Constant.java)。

**17｜P1：直播取播放地址应有前台任务优先级。代码确认，拥塞影响待压力测试。**

点播的 SiteViewModel 已使用独立 interactiveExecutor；LiveViewModel 仍走通用 executor。仓库同步、缓存扫描、其他维护任务占满通用线程池时，切台取地址也要排队。

建议：至少把 URL/回看地址请求与后台维护分离，EPG/XML 保持较低优先级；同频道请求去重，旧频道地址任务取消。

验收：模拟通用线程池占满，切台请求仍能及时启动；EPG 更新不能阻塞播放。依据：[LiveViewModel.execute](app/src/main/java/com/fongmi/android/tv/model/LiveViewModel.java#L126)、[Task](app/src/main/java/com/fongmi/android/tv/utils/Task.java)。

**18｜P2：直播连续切台减少重复停播与黑屏。代码确认操作链路，体验需真机比较。**

每次 `selectChannel` 都立即刷新，请求新地址、显示进度并停止旧播放。快速连按频道键会重复启动并取消这些步骤。

建议：频道名称立即响应，实际调台可短暂合并连续输入；在能力允许时保留旧画面至新地址准备好。不要引入明显的固定切台延迟，应以真机对照决定合并窗口。

验收：连续按十次只准备最后需要的频道，名称及时变化；单次切台不会因合并机制显著变慢。依据：[LivePlaybackController](app/src/main/java/com/fongmi/android/tv/playback/live/LivePlaybackController.java#L37)。

**19｜P2：首次播放缓存初始化、缓存设置生效时机需要专项验证。代码确认路径，调用耗时待采样。**

缓存首次创建会递归计算目录大小并初始化 SimpleCache；上限在静态缓存实例首次建立时确定。缓存文件多时初始化成本可能增加，修改缓存上限后也应明确何时应用。

建议：预先异步准备缓存容量统计，避免重复遍历；设置页说明立即生效还是下次初始化生效，并安全处理播放器持有的缓存实例。

验收：不同缓存文件数量下分别测首次播放、第二次播放；修改大小设置后实际容量符合界面说明。这里未断言所有初始化都发生在主线程。

依据：[MediaSourceFactory](app/src/main/java/com/fongmi/android/tv/player/exo/MediaSourceFactory.java#L56)。

**20｜P2：多源弹幕结果合并继续减少主线程全量工作。代码确认，收益需压力测试。**

单源结果已经在 OkHttp 回调线程预处理，但 `merge` 中仍对累计结果再次整理、排序和提交；首个结果到来还可能主动切走输入框焦点。

建议：累计去重与排序也生成后台快照；用户正在编辑新关键词时，不因旧搜索第一批结果抢焦点；只更新改变的行。

验收：多个接口同时返回大量剧集，播放器控制仍响应；输入过程中不被结果列表抢走焦点。依据：[DanmakuSearchPanel.merge](app/src/main/java/com/fongmi/android/tv/ui/dialog/DanmakuSearchPanel.java#L182)。

### D. 仓库、维护与状态连续性

**21｜P1：仓库管理减少主线程查询和全量刷新。代码确认，待修改。**

仓库页在主线程调用 `manager.getAll()`；每一行绑定时又查询该仓库条目数量。同步事件不断触发 refresh，Adapter 使用全量刷新。详情中的映射数统计还逐项查询配置。

建议：一次异步聚合取得仓库、条目数和映射数，返回完整显示模型；按仓库 ID 差量刷新，合并短时间的同步状态事件。

验收：配置几十个仓库并“全部同步”，滚动、方向键和返回保持响应；记录 SQL 次数，避免每次全量重绑再逐行查询。

依据：[RepositoryActivity.refresh](app/src/leanback/java/com/fongmi/android/tv/ui/activity/RepositoryActivity.java#L60)、[RepositoryAdapter.meta](app/src/leanback/java/com/fongmi/android/tv/ui/adapter/RepositoryAdapter.java#L81)、[RepositoryManager](app/src/main/java/com/fongmi/android/tv/repository/RepositoryManager.java#L55)。

**22｜P2：长时间使用时，海报索引需要总容量和过期策略。代码确认，内存影响待长稳测试。**

PosterResolver 限制每个标题最多 12 个候选地址，但没有限制标题总数。长时间搜索、翻分类会持续增加索引，且读取仍会归一化标题。

建议：对标题总量设置 LRU/TTL；预计算重复使用的标题键；到期或确认失效的地址淘汰，保留当前可见内容所需项。

验收：持续搜索、翻页数小时后索引有上限；海报不会因清理串季、丢失当前有效候选。依据：[PosterResolver](app/src/main/java/com/fongmi/android/tv/utils/PosterResolver.java#L20)。

**23｜P2：历史与收藏返回后刷新、异步取数和焦点连续性统一。代码确认，待修改。**

历史页已经异步加载，但没有从播放返回后的主动刷新；收藏页直接调用 `Keep.getVod()`，点击不同配置的收藏还可能触发配置切换等待。

建议：数据观察或前台增量刷新，保留条目 ID 和滚动位置；收藏列表读取移到后台；跨配置打开时给出阶段反馈和失败恢复入口。

验收：播放后返回立即看到新进度；大收藏列表打开不阻塞输入；跨配置失败时仍能回到原条目。依据：[HistoryActivity](app/src/leanback/java/com/fongmi/android/tv/ui/activity/HistoryActivity.java)、[KeepActivity](app/src/leanback/java/com/fongmi/android/tv/ui/activity/KeepActivity.java)。

**24｜P1：网盘、备份、清缓存等长操作统一反馈与生命周期。代码确认，待修改。**

网盘发现和登录缺少页面级任务超时；备份、清缓存等虽然已在后台执行，但界面没有统一的开始、执行中、完成/失败状态及重复点击控制，部分回调直接访问页面。

建议：每类操作有稳定的任务标识、明确的超时或进度、重复操作保护和页面存活检查；切回页面能读到任务状态，不重复发起。清缓存还应与活跃播放器持有的缓存协调，避免在使用中直接清理。

验收：任务卡住、反复点击、执行中返回、执行中进入播放四种场景都可预测；按钮不会长期失效，也不会误报完成。

依据：[CloudAccountActivity](app/src/leanback/java/com/fongmi/android/tv/ui/activity/CloudAccountActivity.java#L80)、[SettingActivity](app/src/leanback/java/com/fongmi/android/tv/ui/activity/SettingActivity.java#L312)、[FileUtil](app/src/main/java/com/fongmi/android/tv/utils/FileUtil.java#L83)。

## 下一步建议的实际顺序

1. **全源搜索核心链路**：后台快照计算（02）、基础结果先展示/补图后置（05）、分源状态（10）、失败源定向重试（09）。这些直接减少卡顿、无谓等待和重复操作。
2. **慢源与重搜治理**：结果缓存（06）、有效结果排名（07）、分页（08）；并单独开展 Spider 执行隔离（01），从根本上解决不可取消任务的队头阻塞。
3. **播放入口与全局前台优先级**：直播独立取址（17）、首页补全降级（15）、换源整体预算（16）、仓库数据库异步化（21）。
4. **操作连续性与长期体验**：筛选合并、输入防抖、按源恢复浏览位置、历史收藏更新、缓存边界和维护任务状态。

## 建议验收指标

以下是要建立的验收口径，不是当前已达到的承诺：

- **输入响应**：按键/点击到可见反馈，记录 P50/P95，建议以 P95 低于 100 ms 为初始目标。
- **首结果**：提交搜索到第一条可展示结果；同时拆出排队、源请求、结果处理、列表提交耗时。
- **全源完成**：与首结果分开统计；超时源、未启动源和取消源分别计数。
- **首帧与切台**：点击播放/频道到第一帧，区分取址、解析、播放器准备、解码耗时。
- **流畅度**：500/1000/3000 条搜索结果、600 集选集、几十个仓库同步时，测主线程长任务及帧时间 P95/P99。
- **连续性**：返回、重试、切源、重新进入页面后，结果、焦点和滚动位置是否保留。
- **长稳**：搜索与播放交替使用数小时，线程、队列、海报索引、缓存和内存是否有界。

## 本轮验证

- Leanback arm64 JVM：245 项通过，0 失败/错误/跳过。
- API 36 Android TV：6 项结果页仪器测试通过，包括上一轮切源和计数、首批立即显示、800 条压力样本。
- 新增队列优先级单测：当前原生调用不被打断，所有来源只执行一次；原生超时不提前释放物理槽的已有测试仍通过。
- TV Debug 主 APK 与测试 APK 构建通过，mobile arm64 Java 编译通过。
- TV Debug Lint 通过；项目已有警告未进行全量清理。
- `git diff --check` 通过。

[测试与压力样本源码](app/src/androidTest/java/com/fongmi/android/tv/ui/SearchResultInteractionInstrumentedTest.java) · [队列测试源码](app/src/test/java/com/fongmi/android/tv/model/ViewModelSearchRunnerTest.java) · [TV arm64 Debug APK](app/build/outputs/apk/leanbackArm64_v8a/debug/app-leanback-arm64_v8a-debug.apk)。

没有发布正式版本。真实仓库各源耗时、用户电视的硬件瓶颈、真实海报下载和各播放器内核仍需要在目标设备上采样，不能用本次模拟数据处理收益代替端到端搜索速度。
