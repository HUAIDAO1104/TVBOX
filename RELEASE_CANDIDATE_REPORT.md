# TV 首页 / 搜索 / 多仓改造交付报告

- 日期：2026-07-14
- 分支：`codex/feature/tv-home-stage-1-2`
- 基线：`5fdff00a602d`
- 应用：FongMi TV `5.5.6`（versionCode `556`）
- 主交付：Leanback `arm64-v8a` Debug APK

> 开始修改前已检查 Git 状态并保护原有内容。提交前共有 134 个已跟踪文件修改、266 个未跟踪文件；交付提交仅纳入真实代码、资源、数据库 schema、测试及本报告，排除本地截图和内部审查草稿。

## 修改摘要

### 启动与首页

- 移除启动/首页推广与公告型内容的展示链路，并在数据进入 UI 前过滤营销文案，不以透明或遮挡方式伪隐藏。
- 增加轻量启动图，使用同一 `brand_mark` 统一 Adaptive Icon、Launcher 图标、应用内 Logo 和 TV Banner。
- 首页先渲染基础布局、历史缓存和骨架，再异步初始化配置、仓库与首页内容；远端刷新失败时不覆盖已有缓存。
- 舞台海报增加低饱和取色光晕、阴影和克制的焦点缩放；标题、简介与操作区对比度重新分层。
- “首页精选”固定首屏最多 6 个 `3:4` 竖版内容，独立“更多”入口不占第六个内容位；列表使用稳定 ID 保持焦点。

### 搜索结果

- 提交关键词后进入独立聚合结果页，只保留返回、标题、作品/来源计数、实时进度和停止搜索；不再显示键盘、热词、推荐、线路或选集。
- 所有启用且允许搜索的仓库/站点并发执行，单源独立超时和失败；结果按差量实时加入，不等待全部结束，也不全量刷新列表。
- 新增保守的标题归一、繁简/全半角/标点处理、中文/阿拉伯/罗马序号识别、标签剥离、相关性过滤与冲突保护；纯数字片名（如 `2046`）和纯罗马标题不会误判为续集。
- 聚合作品保留每一条原始来源；卡片确认键直达当前推荐来源，右键打开临时来源面板，返回键优先关闭面板。
- 推荐来源综合仓库优先级、搜索成功/失败、响应时间、信息完整度、更新进度及登录要求；详情失败会按有界候选队列自动尝试下一来源。
- 作品和来源面板均使用稳定 ID、DiffUtil 与双焦点锚点，增量更新后不移动当前作品、来源或滚动位置。
- 修复两条真实崩溃链路：Android `Pattern` 不支持 `UNICODE_CHARACTER_CLASS`，以及切换线路时搜索 LiveData 先发出 `null`、`VodFallbackPolicy` 仍调用 `Result.getList()` 的空指针；均补充回归测试。

### 多仓与播放上下文

- 修复只使用当前默认配置的问题：完整遍历 Repository/RepositoryItem，支持启停、刷新、默认仓切换与离线缓存回退，不再按“王二小”“饭太硬”等名称硬编码。
- 仓库、配置 URL、配置类型、站点原始 Key 一起生成稳定命名空间；相同站点 Key、不同 Jar/Spider/解析器不再互相覆盖。
- 非默认仓详情可在进程重建后按持久化仓库信息重新解析 Site；超时、缺失与失败分别提示，不静默回落到默认仓。
- Site、Result、ParseJob、默认/指定/聚合解析和手动解析选择均携带 scoped `siteKey`；手动选解析器不会改写默认仓偏好。

### 详情、设置与播放

- 详情页改为稳定纵向布局流，海报/简介/来源/线路/剧集不再依赖互相覆盖的固定坐标；简介展开与剧集换行会重新测量。来源列表的程序化选中不再冒充用户焦点并推动外层页面，首屏返回按钮完整保留在安全区。
- 保存并恢复详情滚动、焦点、来源、线路和剧集；搜索来源详情只在进入后请求对应线路与选集。
- 设置列表保留轻微焦点缩放，同时关闭错误裁剪、增加首尾安全空间和滚动安全区，返回后恢复焦点/位置。
- 播放控制层改为局部半透明表面和上下渐变；主操作明确提供播放/暂停、快退 10 秒、快进 10 秒，两行控制之间设定可预测的遥控器焦点边界。缓冲提示缩为局部半透明卡片，延迟 350 ms，持续 8 秒后才显示重试/切源；已准备媒体在缓冲期仍可用确认键或媒体键暂停。
- 点击选集会先切入全屏再开始解析/播放，不再要求二次点击海报；从播放返回后恢复详情滚动、来源、选集与焦点。
- 自动切源候选去重、最多 8 个并且每个只尝试一次；退出后迟到详情不会再操作 UI。旧切源搜索为空时会退出进度态，不再悬挂遮罩。
- 播放器内核、解析入口、字幕、音轨、选集、历史和线路能力沿用原实现，没有替换播放核心。

### 网盘凭证

- Cookie/Token 使用 Android Keystore + AES-GCM 加密保存；界面只显示脱敏值，敏感输入禁用自动填充/状态保存并启用安全窗口，日志统一脱敏。
- 设置页会动态发现当前配置中真正承担网盘设置的 Spider，不依赖仓库名称；从其分类中排除“网页输入、清除 Cookie”等动作，只把真实登录动作接入账户列表。
- 扫码和手动凭证合并在原网盘账户行内，不再增加重复横向入口；支持扫码的平台显示“扫码登录 + 手动”，额外平台（例如 115、123、光鸭）也复用同一列表样式。仓库名称及其 emoji 不进入 UI。
- 点击扫码直接调用原仓库 `categoryContent → detailContent` 登录协议，已实机确认百度和夸克均弹出仓库原生的 Cookie/注册/扫描二维码窗口；过程不切换首页内容源，也不经过 `VideoActivity`。

## 主要根因

1. 原搜索、详情和播放高度依赖单例 `VodConfig`，站点 Key、Loader 缓存和解析器选择缺少仓库命名空间，导致同名站点覆盖和默认仓泄漏。
2. 搜索结果按标题字符串直接展示并粗暴刷新，既无法可靠聚合，也会在异步返回时重排焦点。
3. 详情页和部分设置项使用固定尺寸/坐标并由父容器裁剪，动态文本、缩放和大量剧集会破坏测量关系。
4. 播放缓冲/切源共用大面积全屏进度层，短暂缓冲立即出现，空结果路径没有完整关闭状态。
5. 启动阶段把配置、仓库和内容准备串在首屏路径上，远端失败还会覆盖已有本地内容。
6. 网盘扫码不是统一 JSON 字段，而是配置中心 Spider 在分类和详情动作中发布的能力；旧首页流程把设置动作误当影片详情，经 `VideoActivity` 执行并要求先切换首页内容源。
7. 详情页来源列表把“程序化 selection”与“用户焦点 reveal”混为一谈，首批来源绑定时会把外层页面精确推高 95 px，造成顶部返回按钮裁剪。

## 主要修改文件

- 首页：`app/src/leanback/java/com/fongmi/android/tv/ui/activity/HomeActivity.java`、`app/src/leanback/java/com/fongmi/android/tv/ui/home/`、`app/src/leanback/res/layout/activity_home.xml`
- 搜索：`app/src/leanback/java/com/fongmi/android/tv/ui/activity/CollectActivity.java`、`app/src/main/java/com/fongmi/android/tv/ui/search/`、`app/src/main/java/com/fongmi/android/tv/model/SearchSnapshot.java`
- 多仓：`app/src/main/java/com/fongmi/android/tv/repository/`、`app/src/main/java/com/fongmi/android/tv/model/SiteViewModel.java`、`app/src/main/java/com/fongmi/android/tv/api/config/VodConfig.java`
- 详情/播放：`app/src/leanback/java/com/fongmi/android/tv/ui/activity/VideoActivity.java`、`app/src/main/java/com/fongmi/android/tv/playback/vod/`、`app/src/main/java/com/fongmi/android/tv/player/parse/ParseJob.java`
- 设置：`app/src/leanback/java/com/fongmi/android/tv/ui/base/FocusSafeSettingsActivity.java`、`app/src/main/java/com/fongmi/android/tv/ui/setting/SettingsFocusPolicy.java`
- 网盘登录与安全：`app/src/main/java/com/fongmi/android/tv/cloud/`、`app/src/leanback/java/com/fongmi/android/tv/ui/activity/CloudAccountActivity.java`、`app/src/leanback/java/com/fongmi/android/tv/ui/adapter/CloudAccountAdapter.java`、`app/src/main/java/com/fongmi/android/tv/security/`、`catvod/src/main/java/com/github/catvod/utils/SecretRedactor.java`
- 数据库：`app/src/main/java/com/fongmi/android/tv/db/`、`app/schemas/com.fongmi.android.tv.db.AppDatabase/36.json`～`38.json`
- 测试：`app/src/test/java/`、`app/src/androidTest/java/`

## 验证结果

| 检查 | 结果 |
|---|---|
| `git diff --check` | 通过 |
| `xmllint --noout`（`app/src` 全部 XML） | 通过 |
| Leanback arm64 JVM 单测 | 122/122，通过；0 failure/error/skipped；含配置中心识别、网盘分类提取、搜索源偏好、技术剧集名归一与登录动作筛选 |
| Mobile arm64 JVM 单测 | 111/111，通过；0 failure/error/skipped |
| API 36 Android TV Connected Test | 19/19，通过；0 failure/error/skipped |
| Leanback arm64 Debug Java 编译 | 通过 |
| Mobile arm64 Debug Java 编译 | 通过 |
| Leanback arm64 Lint | 通过；0 error、221 个非阻断 warning |
| Leanback arm64 Debug APK | 构建成功 |
| Mobile arm64 Debug APK | 构建成功 |
| 模拟器覆盖安装 | `Success` |
| API 36 模拟器端到端 | 搜索结果进入详情、纵向滚动、选集直达全屏、实际视频播放、暂停及两行控制焦点均通过；无 FATAL/ANR |
| API 36 网盘扫码联调 | 当前配置发现 8 个网盘能力；百度、夸克统一账户行均直达仓库原生扫码窗口；页面未越界、未跳转详情/播放、无 FATAL |

最终无缓存构建命令：

```bash
bash gradlew \
  :app:testLeanbackArm64_v8aDebugUnitTest \
  :app:testMobileArm64_v8aDebugUnitTest \
  :app:lintLeanbackArm64_v8aDebug \
  :app:assembleLeanbackArm64_v8aDebug \
  :app:assembleMobileArm64_v8aDebug \
  --rerun-tasks --stacktrace

bash gradlew \
  :app:connectedLeanbackArm64_v8aDebugAndroidTest \
  --rerun-tasks --stacktrace
```

Lint 的 221 项均为 warning，主要是既有未使用资源、`notifyDataSetChanged`、旧 API、默认 Locale 和重复 included id；没有阻断构建。资源合并仍会提示既有翻译占位符命名空间和 `detail_title` 非位置参数警告。

## APK

主 APK：`app/build/outputs/apk/leanbackArm64_v8a/debug/app-leanback-arm64_v8a-debug.apk`

- 大小：68,756,121 bytes（约 65.57 MiB）
- SHA-256：`2314eb72ece082c46a12936395d9f975219b3882d7882cca5491828f9c92ca8e`
- 签名：APK Signature Scheme v2，1 个 signer
- 证书：Android Debug；SHA-256 `b8ed9c579e8671d24f6d4d18b296528ca655c627fa437f57a217450c0b548aa7`

兼容构建：`app/build/outputs/apk/mobileArm64_v8a/debug/app-mobile-arm64_v8a-debug.apk`

## 已知限制

- 网盘扫码已复用当前配置中心 Spider 的真实协议，但第三方仓库没有统一能力描述标准；更换为不发布网盘设置分类/登录动作的仓库时，只保留安全手动录入。
- 已确认扫码窗口、二维码按钮和仓库调用链可达；未使用真实个人账号完成手机确认，因此各网盘服务端后续授权成功率仍受远端协议变化影响。
- 首页会先显示历史缓存/基础骨架，但完整远端首页 feed 与配置尚未做磁盘快照；无网或首次启动时可能停留在骨架/空态。
- 非默认仓已使用 scoped key 隔离；当前默认配置仍保留原有 key/cid 以兼容历史、收藏和既有播放入口。
- App、JS、Python Loader 与解析路径已隔离；无法修改的第三方 Java Jar 若在内部硬编码旧代理 Key，仍可能受其实现限制。
- 未使用真实网盘账号，也未覆盖所有第三方仓库、DRM、实体电视/遥控器、厂商硬解、极端弱网与完整字幕/音轨组合。
- 第三方来源数量和成功率会随网络及远端仓库变化；本次运行样本将“野狗骨头”稳定聚合为 1 个作品、50 个可用来源，没有把来源数量写成固定产品承诺。
- 已验证 17～19 集的动态换行与滚动；数百至上千集的极端剧集量尚未在低内存实体电视上做压力测试。
- 交付物为 Debug 证书签名 APK，不是可上架的生产 Release；正式发布仍需用户的发布 Keystore。

## 结论

当前分支已形成可编译、可安装、可回归的 Leanback arm64 Debug 候选。搜索聚合、多仓上下文、详情布局、设置焦点、播放遮罩、凭证安全及仓库扫码直达均有代码、测试与模拟器验证；第三方仓库协议变化及生产签名仍是明确的外部边界。
