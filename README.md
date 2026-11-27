<p align="center">
  <img src="docs/icon.png" width="140" alt="葡萄音乐" />
</p>

<h1 align="center">葡萄音乐</h1>

<p align="center">
  直连 WebDAV 的 Android 原生音乐播放器 —— 把自己的网盘、NAS 变成一个能听歌的音乐库
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android%209%2B-3DDC84?logo=android&logoColor=white" alt="Platform" />
  <img src="https://img.shields.io/badge/Kotlin-2.3-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white" alt="Compose" />
  <img src="https://img.shields.io/badge/Media3-ExoPlayer%201.8-FF6F00" alt="Media3" />
  <img src="https://img.shields.io/badge/minSdk-29-orange" alt="minSdk" />
</p>

---

## 这是什么

**葡萄音乐**是一个完全跑在手机上的 WebDAV 音乐播放器。它不依赖任何后端服务器、不需要 `strm` 指针文件，也不需要在 PC 上预先刮削整理——只要你的网盘/ NAS 通过 OpenList、CloudDrive2 或群晖等工具暴露了 WebDAV 接口，App 就能递归枚举目录里的音频、视频文件，边下边播。

数据全部存在本机（Room + DataStore），不上传任何服务器；除了听歌时向你填写的 WebDAV 地址发请求，以及主动刮削元数据时访问公开接口，App 不会把数据发往别处。

> 典型用法：手机上跑一个 OpenList（默认 `http://127.0.0.1:5244`），把夸克/阿里/115/OneDrive 挂进来，然后在葡萄音乐里填同一个地址。

## 功能

### 播放

- Media3 ExoPlayer 串流播放，`MediaSessionService` 前台服务，通知栏与锁屏可控
- 播放模式（顺序 / 单曲 / 随机）、进度拖拽、音量控制
- 视频文件可直接全屏播放，无需外部播放器
- 响度归一化：优先读 ReplayGain 标签，无标签时由 `AudioProcessor` 实测 LUFS / 峰值，覆盖率超过 60% 才写库
- 切歌预热：对下一首直链预发 `Range: 0-1MB`，带 TTL 去重与并发上限，弱网下减少卡顿

### 媒体库

- 纯 WebDAV 扫描：`PROPFIND` 递归枚举，生成稳定直链 `/dav/<路径>`（不签名、不过期）
- 音乐库四视图切换 + 多字段排序 + A–Z 首字母索引（含拼音首字母）
- 长按多选、批量加入歌单
- 单曲详情页：按文件夹 / 专辑 / 艺术家分组下钻，导航参数可序列化，支持前进后退与刷新
- 全局搜索
- 最近播放：常驻第一位的虚拟歌单，自动收录最近 100 首，不可改名/删除

### 歌词

- 三级优先级加载，逐行滚动，支持双语对照
- 逐字卡拉 OK 扫光：增强型 LRC 按字时间戳点亮，普通 LRC 按行进度均分，50ms 高频刷新
- 歌词时间偏移：±0.2s / ±1s / 重置，按歌曲持久化

### 元数据

- JAudiotagger 读取内嵌标签，配合 QQ 音乐 / 酷狗刮削补全（**只填空，不覆盖已有值**）
- 单曲对照编辑弹窗：综合搜索、单独搜封面 / 歌词、批量字段编辑
- 保存时只改可编辑字段，保留时长 / 响度 / 歌词偏移，并即时同步到正在播放的曲目
- 封面主色取色：Palette 提取封面主色驱动全站 Material 配色，按 URL 缓存，可在设置中关闭

### 界面

- Jetpack Compose + Material 3，深浅色跟随系统
- 响应式布局：窄屏底部 `NavigationBar`，宽屏（≥600dp）/ 折叠屏/ 平板改为左侧 `NavigationRail`，宽屏下分组详情与列表并排显示
- 通知栏 / 锁屏封面与元数据实时刷新，编辑元数据后无需重缓冲即可更新

## 技术栈

| 层 | 选型 |
|---|---|
| 语言 / UI | Kotlin 2.3.21 · Jetpack Compose（BOM 2025.08.00）· Material 3 |
| 构建 | Gradle Wrapper · AGP 9.5.2 · KSP 2.3.14 |
| 播放 | Media3 ExoPlayer 1.8.1 · MediaSessionService |
| 数据库 | Room 2.8.5（媒体 / 歌词 / 歌单 / 设置 / 元数据缓存等 7 张表） |
| 网络 | OkHttp 4.12（WebDAV `PROPFIND` / `Range`） |
| 元数据 | JAudiotagger 2.0.1 · QQ 音乐 / 酷狗接口 |
| 图片 | Coil 2.7 · Palette 1.1 |
| 设置 | DataStore Preferences |
| 序列化 | kotlinx.serialization 1.8.1 |

## 工作原理

```
┌──────────────┐   PROPFIND /dav (递归)   ┌──────────────┐
│  OpenList /  │ ◀─────────────────────── │              │
│  CloudDrive2 │                          │   葡萄音乐    │
│   (WebDAV)   │ ───────────────────────▶ │  (Android)   │
└──────────────┘   GET /dav/<path> +Basic │              │
                        （Range 分段）      └──────┬───────┘
                                                  │
                                    Room：媒体 / 歌词 / 歌单
                                    DataStore：WebDAV 配置
```

1. 扫描阶段用 `PROPFIND` 递归遍历目标目录，把文件落成 Room 里的媒体记录；
2. 每条记录对应一个稳定直链 `http://host:port/dav/<encode(路径)>`；
3. 播放时由自定义 `DataSource` 统一注入 `Authorization: Basic` 头，ExoPlayer 直接按 `Range` 拉流，不落盘缓存整首。

因为直链不签名、不过期，升级 App 或重新扫描不会让歌词、响度、歌单关联失效。

## 快速开始

### 1. 准备 WebDAV

手机或局域网内任意设备上运行一个 WebDAV 服务，把网盘挂载进去：

- [OpenList](https://github.com/OpenListTeam/OpenList)（推荐，默认 `http://127.0.0.1:5244`，WebDAV 路径为 `/dav`）
- CloudDrive2
- 群晖 / 威联通 NAS 自带的 WebDAV Server

### 2. 构建安装

环境要求：**JDK 17+**、**Android SDK 36**（Gradle Wrapper 已内置，无需本地安装 Gradle）。

```bash
git clone <your-repo-url>
cd webdav播放器_forAndroid

# 指向你的 SDK（或直接改 local.properties 里的 sdk.dir）
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

Windows 下用 `gradlew.bat assembleDebug`。Release 构建默认开启 R8 混淆与资源收缩。

> 仓库路径含中文时，`gradle.properties` 里的 `android.overridePathCheck=true` 已经处理了 AGP 的路径校验；若你 clone 到纯英文路径，可忽略。

### 3. 首次配置

打开 App → 设置页，填写：

| 配置项 | 说明 | 示例 |
|---|---|---|
| WebDAV 地址 | 支持 `http://host:5244`、`.../dav`、带或不带结尾斜杠四种写法 | `http://127.0.0.1:5244/dav` |
| 用户名 / 密码 | OpenList 的 WebDAV 账号（默认 `admin` / 见其初始密码） | — |
| 网盘根目录 | WebDAV 根下的扫描起点，留空表示从 `/` 开始全量扫描 | `/quark`、`/music` |

保存后回到音乐库点击扫描即可。目录下文件较多时首次扫描耗时较长，之后增量扫描只比对变化项。

## 目录结构

```
app/src/main/kotlin/com/grape/player/
├── WebDAVPlayerApp.kt      # Application：初始化 Room / DataStore
├── MainActivity.kt         # 权限申请 + Compose 入口
├── data/                   # Room Entity / DAO / Repository / 设置管理
├── network/                # WebDAV 客户端 / 网盘扫描 / 预热 / 元数据刮削
├── playback/               # Media3 前台服务 / 播放控制器 / 响度处理 / 自定义 DataSource
├── util/                   # LRC 解析 / 标签读写 / 拼音首字母 / 封面取色
└── ui/                     # Compose 界面
    ├── library/            # 音乐库四视图、分组详情、A–Z 索引
    ├── player/             # 播放页、歌词页
    ├── playlist/           # 歌单、最近播放
    ├── search/             # 全局搜索
    ├── settings/           # WebDAV 配置、扫描、响度、外观
    ├── meta/               # 元数据编辑弹窗
    └── theme/              # Material 3 主题与动态配色
```

## 兼容性

| 项 | 值 |
|---|---|
| minSdk | 29（Android 10） |
| targetSdk / compileSdk | 36 |
| ABI | 通用（纯 Kotlin，无原生库） |
| 网络 | 明文 HTTP 已放行（适配本机 OpenList），HTTPS 同样支持 |

## 已知限制

- 依赖 WebDAV 的目录列表性能；网盘接口本身限速时，扫描会比本地目录慢很多。
- 首次构建需要联网下载 Gradle 与依赖。
- 播放列表、歌词、响度等数据只存于本机，卸载 App 会丢失（后续计划支持导出/导入）。
- 元数据刮削依赖第三方公开接口，接口变动可能导致搜索无结果，此时仍可手动填写。

## 免责声明

本项目仅供学习交流，请仅用于播放你**合法拥有**的媒体资源。App 不提供、不托管、不分发任何音视频内容，也不内置任何第三方源；元数据刮削仅在你的主动操作下访问公开搜索接口，结果准确性由第三方服务决定。

## 致谢

开源项目与工具：

- [Media3 / ExoPlayer](https://github.com/androidx/media)
- [Jetpack Compose](https://developer.android.com/jetpack/compose)
- [OpenList](https://github.com/OpenListTeam/OpenList) —— 让 WebDAV 挂载网盘变得简单
- [JAudiotagger](https://bitbucket.org/ijabz/jaudiotagger)

AI 辅助开发：本项目的架构设计、编码与文档撰写过程中，使用了以下大模型协助。

- DeepSeek V4.1 Flash
- GLM 5.3 Flash
- HY4 Flash

---

<p align="center"><sub>如果这个项目对你有用，欢迎点个 Star。</sub></p>
