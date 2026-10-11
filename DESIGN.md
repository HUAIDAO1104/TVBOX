---
version: alpha
name: 片多多 Mobile
description: 面向触屏的原生 Android 影视片架，独立于遥控器电视界面。
colors:
  primary: "#FF6575"
  background: "#101318"
  surface: "#1B2028"
  outline: "#687587"
  foreground: "#F4F5F7"
  secondary: "#ADB7C6"
typography:
  sans:
    fontFamily: "sans-serif"
  title:
    fontFamily: "sans-serif-medium"
    fontSize: "22px"
  body:
    fontFamily: "sans-serif"
    fontSize: "14px"
rounded:
  DEFAULT: "12px"
  sm: "8px"
spacing:
  page: "16px"
  gap: "12px"
  target: "48px"
components:
  button: {}
  poster:
    textColor: "{colors.foreground}"
  navigation: {}
  search: {}
  dialog: {}
---

# 片多多手机设计规范

## Overview

用户要在手机上快速找片、切换来源、续播和管理收藏。当前用户需求及应用内容以中文为主；不推断特定国家市场。使用 Android 系统中文字体回退。此规范只管 `src/mobile`，电视端继续使用 `src/leanback`。

Creative North Star：安静的私人电影片架。海报作为视觉主体，片名、更新状态和观看进度提供实用信息；保留品牌红色，减少大面积装饰和遥控器焦点效果。此轮以电影片架的克制排版为方向：墨色底、细线图标、稳定海报基线、下划线分类；避免巨大占位播放标志、整页独立设置卡片、满屏文字工具栏。

运行时 Android resources 是权威来源（Model B），本文件镜像其值。`mobile_theme.xml` 的 mobile_background/surface/outline/text/muted/accent 对应以上 background/surface/outline/foreground/secondary/primary。文档 px 数值映射原生 dp，字体数值映射 sp。`scripts/check_mobile_design.py` 检查漂移。

## Colors

深灰底配较亮的内容表面；正文 foreground，辅助文字 secondary。primary 只用于选中、主操作与品牌标识。选择同时用下划线/文字/原生 checked 语义表达。沿用深色播放环境，避免原有白字落在浅色 Material 系统主题。

## Typography

标题 22sp medium，正文 14–16sp，辅助 12sp。采用系统 sans-serif 及 Android 中文回退；尊重系统字体缩放。重要操作不截断；片名两行，详情提供全文。技术地址可复制，避免大号配置文字抢占内容。

## Layout

默认竖屏；首页、片库、我的三个有文字标签的底部目的地。直播从首页明确入口进入。首页固定搜索入口、来源和横向分类、三列海报；窄屏/大字减少列数，平板按可用宽度增加列数。页面边距 16dp、内容间距 12dp；触摸操作最小 48dp。

搜索结果使用横向来源标签和全宽结果列表，立即切换；列表自身滚动，工具栏和搜索进度可见。详情播放器保持 16:9，下方紧凑工具栏固定展示全屏、投屏、设置；收藏随片名、换源随来源显示。更新/年份/地区/类型合并为简要信息，线路与选集为核心内容区，简介及演职员通过展开/收起按钮查看。下面自然滚动；全屏仅在播放时进入，返回先退出全屏。BaseActivity 负责系统栏与键盘边距，播放器负责全屏时的例外。

## Elevation & Depth

通过 surface 和 outline 区分层次。普通片架不叠加阴影/玻璃；浮层沿用 Material dialog/bottom sheet 的平台遮罩与焦点处理。手机禁止 DialogGlass 的 surface/card 重绘和 TouchClickWindowCallback 触控代发。MobileSheetDialog 统一约束可用高度，选择/设置为 86%，投屏为 66%，短窗口为 96%；顶边圆角 24dp。选集保持展开，关闭按钮和系统返回退出，纵向拖动只滚动剧集，横向线路不参与嵌套纵向滚动；横屏短窗口采用左侧线路/排序、右侧剧集网格。

插件向页面内容层添加的纯进度面板由 InlineProgressHost 移到底部并预留空间，移除后释放空间；全屏/PiP 隐藏其视觉层但不中断初始化。弹幕状态显示在视频下方工具栏内，不盖住视频或正文。

## Shapes

表面与搜索框 12dp，海报 8dp。图像固定比例，加载失败保留占位；列表操作用原生 ripple。

## Components

默认、按下、焦点、选中、不可用和加载使用 Android Material 状态。主动作使用 MaterialButton，次要动作使用文字按钮。重要图标按钮有 contentDescription，底部导航始终展示标签。Mobile.Action/Icon/Section/Tabs/Sheet 为统一资源样式；mobile_chip 统一选中背景与描边，mobile_tab 为内容分类下划线，TabLayout 指示条不延迟切换。设置使用分组行，值作为次级信息；影片名称保留两行稳定基线。

加载使用保留空间的 progress indicator；已有结果持续可用。不用假进度、不等待全部源返回、不以动画延迟导航。无配置/空片库/无结果/超时分别说明并提供配置、搜索或重试。

表单、列表选择与确认框由原生 Material 控件管理；网络建议 300ms 防抖、输入法组合态不发请求、过期回调失效。清空搜索按钮立即生效。列表删除、清空记录遵循明确范围确认。

图标采用 mobile 独立的 24dp / 1.8dp 线宽 vector（ic_mobile_*），操作语义配短标签；图标按钮以 contentDescription 命名。正常导航直接显示；平台动画随系统动画倍率，业务代码不添加入场等待。短提示由 Notify 统一管理。

## Do's and Don'ts

- 使用中文动作标签，把常用动作放在可触达位置。
- 搜索计数来自已去重、已展示条目，并显示搜索进度和失败状态。
- 不把电视布局缩放成手机，不用悬停或长按作为唯一操作入口。
- 选集每 50 集一段；当前集、正倒序、线路切换与关闭入口明确，点击当前集只关闭面板，不跳全屏。
- 倍速显示 0.75/1/1.25/1.5/2× 预设；精细倍速、内核、解码、片头片尾与重置放在可展开的高级项。
- 不让 UI 更换修改搜索调度、播放器超时、数据库和云端凭据协议。
