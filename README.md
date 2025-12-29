📅 自动日程助手

![alt text](https://img.shields.io/badge/Kotlin-2.0-purple.svg)

![alt text](https://img.shields.io/badge/UI-Jetpack%20Compose-green.svg)

![alt text](https://img.shields.io/badge/OCR-Google%20ML%20Kit-blue.svg)

![alt text](https://img.shields.io/badge/License-MIT-orange.svg)
自动日程助手 是一款基于 Android 无障碍服务和 AI 大模型的智能日程管理应用。它能够通过屏幕截图自动识别当前界面（如微信、钉钉聊天记录）中的日程信息，利用本地 OCR 提取文字，结合 LLM（大语言模型）进行语义分析，并在后台静默创建日程和提醒。
无需繁琐的复制粘贴，只需下拉通知栏点击一下，日程即刻生成。


✨ 核心功能

👆 一键识别：通过系统快捷设置磁贴（Quick Settings Tile），在任意界面下拉即可触发识别。

📷 隐私安全的 OCR：使用 Google ML Kit 在本地完成图片转文字，仅将纯文本发送给 AI，保护截图隐私。

🤖 自定义 AI 大脑：支持配置任意兼容 OpenAI 接口的大模型（如 DeepSeek、通义千问、ChatGPT 等），自定义 API URL 和 Key。

⚡ 后台静默处理：截图后自动收起通知栏，在后台完成分析与存储，通过横幅通知告知结果，不打断当前操作流。

⏰ 智能提醒：支持设置多个提醒时间（如日程开始前 15 分钟、1 天前等），利用精确闹钟（AlarmManager）发送通知。

🎨 现代 UI 设计：完全基于 Jetpack Compose 开发，采用 Material 3 设计规范，包含丝滑的滚轮选择器动画。

📂 数据备份：支持将日程数据导出为 JSON 文件或从文件导入，方便迁移。


🚀 快速开始

1. 安装
下载最新版本的 Releases APK 并安装到 Android 设备（推荐 Android 11+）。
2. 权限授予
为了实现自动化功能，App 需要以下权限：
无障碍服务：用于模拟操作收起通知栏、截取屏幕内容。
通知权限：用于发送识别结果和日程提醒（重要：国产 ROM 需手动在设置中开启“悬浮通知/横幅通知”权限）。
精确闹钟：用于准时发送日程提醒。
3. 配置 AI 模型
首次使用前，请点击 App 左上角菜单 -> 设置：
API 地址：例如 https://api.deepseek.com/chat/completions
模型名称：例如 deepseek-chat
API Key：填入你的密钥
点击保存。
4. 使用方法
在手机下拉通知栏，点击编辑按钮，将 “识别日程” 磁贴拖入常用区域。
打开任意包含日程信息的界面（如聊天窗口）。
下拉通知栏，点击 “识别日程”。
等待顶部弹出“成功创建日程”的通知即可。


🛠️ 技术栈

语言: Kotlin

UI: Jetpack Compose (Material3)

架构: MVVM (ViewModel + Repository pattern)

网络: Ktor Client (Coroutines based)

OCR: Google ML Kit (Text Recognition Chinese)

序列化: Kotlinx Serialization

核心组件:

AccessibilityService: 屏幕控制与截图。

TileService: 下拉快捷开关。

AlarmManager & BroadcastReceiver: 定时任务调度。

📄 开源协议

本项目基于 MIT License 开源。

注意事项 (Troubleshooting)

点击磁贴没反应？

请检查“无障碍服务”是否被系统杀后台，建议在多任务管理中锁定 App，并允许自启动。

通知不弹出？

请检查系统设置中，App 的“悬浮通知”或“横幅通知”权限是否已开启。

OCR 报错？

项目已内置 ML Kit 模型，无需联网下载。如果依然报错，请确保设备支持 Google Play 服务或相关组件完整。
