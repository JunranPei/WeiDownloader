# WeiDownloader（Android）

原生 Android 社交媒体下载器，使用 Kotlin 编写，最低支持 Android 7.0。当前主界面支持在 Wei（微博）和 Dou（抖音）之间横向切换。

## 功能

- 应用内打开微博官方登录页；会话由 Android WebView 管理，应用不会显示或写入 Cookie。
- 自动读取剪贴板中的微博分享链接（仍可手动编辑），识别图片、Live 图动态文件、视频和音频。
- 支持粘贴或分享公开抖音链接；优先通过公开分享页解析视频，失败时使用 HTML 兜底路径。
- 画质可选择“最高可用（默认）”“高清”或“标准”。最高模式会按 `largest`、`mw2000`、`large` 的顺序选择图片，并按视频码率选择最高可用档位。
- 默认保存到 `Download/WeiDownloader/`；可修改为其他目录。使用系统下载管理器，支持通知栏进度和后台续传，并按平台携带对应的来源页信息。

## 构建

1. 用 Android Studio 打开本目录。
2. 等待 Android Studio 下载 Android Gradle Plugin 和 SDK（项目使用 compile SDK 35）。
3. 选择 **Build > Build APK(s)**，调试包会位于 `app/build/outputs/apk/debug/` 下。

## 使用限制

仅下载自己拥有或已获授权保存的内容，并遵守微博、抖音及其他平台的服务条款、版权和隐私规则。本项目不尝试绕过登录、验证码、私密内容、付费内容或任何平台访问限制。下载到的最高画质取决于平台返回的公开源文件和当前访问权限。
