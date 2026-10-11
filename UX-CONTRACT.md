# 片多多移动端交互合同

范围：Android mobile flavor 的完整触屏体验。用户于 2026-10-11 要求独立重做手机版并推送发布。实际 Java/XML 与此合同共同维护；TV 的遥控器交互不是手机版规范。

## Canonical capability ownership

| 能力 | 权威实现 | 行为 |
|---|---|---|
| Navigation | mobile HomeActivity / FragmentStateManager | 首页、片库、我的；切换不重复请求；返回保留列表 |
| Insets / Scrollbar | mobile BaseActivity + Android RecyclerView/NestedScrollView | 系统栏/IME 边距；平台原生触摸滚动，每个页面一个主滚动区 |
| Buttons / Select/Listbox | MaterialButton、TabLayout、MaterialAlertDialogBuilder、SiteDialog | 48dp 目标，原生语义；选择立即反馈 |
| Form / Search | mobile SearchFragment | 显式搜索立即执行；建议 300ms 防抖、组合态保护、取消过期响应、可清空 |
| Async resilience | main SiteViewModel + mobile CollectFragment | 共享并发搜索及会话标识；局部结果即时出现，来源切换不重搜；取消/重试/分页 |
| Result count | SiteViewModel.SearchSnapshot + CollectFragment | 数字与该来源可显示去重条目一致；失败不伪装成零匹配 |
| Dialog / Sheet | Material dialogs + mobile ui/dialog | 原生返回关闭、焦点恢复、表单键盘不遮挡；MobileSheetDialog 统一播放弹层 |
| Toast | main Notify | 短反馈统一；错误恢复保留在所属页面 |
| CRUD / Library | mobile LibraryFragment、HistoryActivity、KeepActivity | 本地记录/收藏；删除确认，不改存储或同步策略 |
| Player | mobile VideoActivity + main PlaybackActivity/PlayerManager | 竖屏小窗、点击全屏、返回先退全屏；保持现有超时修复 |
| Settings | mobile SettingFragment 及子设置 | 我的内分组设置；保留数据备份、播放、来源配置能力 |
| Date / Table Selection | 不适用 | 无日期输入、表格或批量数据库选择 |

## Canonical UI Map

| Capability | Canonical owner | Source of truth | Allowed variants | Verification |
|---|---|---|---|---|
| Select/Listbox | Material TabLayout / MaterialAlertDialogBuilder | mobile CollectFragment / MobileSearchSources | 横向来源、原生多选 | MobileExperienceInstrumentedTest |
| Form | Material TextInputLayout | mobile SearchFragment / shared account and repository dialogs | 搜索、订阅、凭据 | IME/清空/重复提交与布局检查 |
| Scrollbar | Android RecyclerView / NestedScrollView | Android 原生滚动与 mobile XML | 网格/列表/详情正文 | 手机、平板和大字截图 |
| Toast | main Notify | main utils/Notify.java | 短结果反馈 | 运行时检查 |
| CRUD | RepositoryManager / CloudAccountManager / History / Keep | main 对应类，现有存储与删除策略 | 订阅、账号、本地片库 | 现有数据库回归与移动端入口检查 |
| Date | 不适用 | 无日期输入 | 仅已有日期格式化 | 静态审查 |
| Table Selection | 不适用 | 无表格 | 搜索范围是原生多选列表 | 静态审查 |

## 路由和状态

每个 Activity/toolbar 使用目的地标题（Android 的 Route document title policy）。子页返回原入口，片库进入详情返回时刷新进度。搜索返回保留输入，详情返回保留搜索源和滚动位置。全屏返回只退出全屏，第二次返回详情的来源页。

Route error / 403 page behavior：无远端网页路由。来源失败在内容区说明并重试；无配置展示添加来源入口，始终可返回和切换底部目的地。凭据和订阅失败沿用已有业务安全边界，不以重试绕过权限。

## 状态与恢复

- 首页：首次加载、无配置、来源无分类、失败重试、内容成功、下拉刷新及追加分页。
- 搜索：未输入、建议/历史、正在搜索、部分成功、无匹配、失败/超时、停止、重试失败源、指定源加载更多。
- 片库：继续观看/收藏、空记录说明和找片入口、打开详情、删除确认。
- 播放：获取详情、缓冲、播放、暂停、错误重试、竖横屏/PiP。沿用共享播放服务及进度检测。
- 设置：显示当前配置、编辑时保存输入、失败保留表单上下文。

## 验证与迁移

恢复 `src/mobile` 为独立构建入口，搜索消费最新共享搜索模型，播放器继续使用共享超时逻辑。旧手机 XML/Java 只作为原生能力基础，首页/搜索/片库/导航完成整体迁移，不将 TV 布局包装后继续使用。

2026-10-11 第二轮整体优化：移除残存的 TV glass surface/card 覆盖；首页/片库/搜索/播放/设置共享图标、控件、选择态与排版。全部选集及全屏选集使用 EpisodeListDialog 单一所有者；旧 EpisodeGridDialog 不再从播放流程调用。搜索结果的来源总览只改变 active source，不发起新搜索。来源多选应用仍代表更改搜索范围，触发新会话。播放设置的次级动作均保留，按钮代理仍调用现有控制器与持久化逻辑。

验证 Android 窄屏竖屏、键盘、横屏播放器、平板、大字、无配置/空/失败、返回状态与连续点击。运行 JVM 回归、手机仪器测试、四种发布构建和签名/版本/OTA 校验。本轮证据记录在 MOBILE_EXPERIENCE_REVIEW.md（上一轮记录见 MOBILE_REDESIGN_REPORT.md）；未执行项目必须如实标记。
