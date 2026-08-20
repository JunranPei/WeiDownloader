# WeiDownloader（Android）

原生 Android 微博媒体下载器，使用 Kotlin 编写，最低支持 Android 7.0。

## 功能

- 应用内打开微博官方登录页；会话由 Android WebView 管理，应用不会显示或写入 Cookie。
- 自动读取剪贴板中的微博分享链接（仍可手动编辑），识别图片、Live 图动态文件、视频和音频。
- 画质可选择“最高可用（默认）”“高清”或“标准”。最高模式会按 `largest`、`mw2000`、`large` 的顺序选择图片，并按视频码率选择最高可用档位。
- 默认保存到 `Download/Weibo File Downloader/作者_微博ID/`；可修改为其他英文子目录。使用系统下载管理器，支持通知栏进度和后台续传。

## 构建

1. 用 Android Studio 打开本目录。
2. 等待 Android Studio 下载 Android Gradle Plugin 和 SDK（项目使用 compile SDK 35）。
3. 选择 **Build > Build APK(s)**，调试包会位于 `app/build/outputs/apk/debug/app-debug.apk`。

## 使用限制

仅下载自己拥有或已获授权保存的内容，并遵守微博服务条款、版权与隐私规则。本项目不尝试绕过登录、验证码、私密微博、付费内容或任何平台访问限制。下载到的最高画质取决于微博对当前登录会话返回的可用源文件。
