# Talkify

云端大模型驱动的 Android 语音合成([TTS](https://developer.android.com/reference/android/speech/tts/TextToSpeech))应用。  
目前接入了阿里云百炼的[通义千问3-TTS-Flash服务](https://bailian.console.aliyun.com/cn-beijing/?spm=5176.29619931.J_SEsSjsNv72yRuRFS2VknO.2.74cd10d7e5xOeO&tab=model#/efm/model_experience_center/voice?currentTab=voiceTts)和火山引擎的[豆包语音](https://console.volcengine.com/speech/new/experience/tts)作为功能引擎。

在阿里云百炼的[密钥管理](https://bailian.console.aliyun.com/cn-beijing/?spm=a2c4g.11186623.nav-v2-dropdown-menu-0.d_main_2_0.57a349e5ACzyY3&tab=model&scm=20140722.M_10904463._.V_1#/api-key)页面下申请对应的`ApiKey`以使用该引擎。  
在火山引擎的[ApiKey管理](https://console.volcengine.com/speech/new/setting/apikeys)页面下申请对应的`ApiKey`以使用该引擎。

## 应用截图
<div style="text-align: left;">
  <img src="doc/images/Screenshot_talkify.webp" width="260" style="margin-right: 10px;"  alt="应用截图"/>
</div>

## 推荐搭配阅读软件

[Legado / 开源阅读](https://github.com/gedoor/legado)  
[Legado / 开源阅读 APP书源](https://github.com/aoaostar/legado)

## 技术栈

- **语言**: Kotlin
- **UI**: Jetpack Compose + Material 3 Expressive
- **SDK**: minSdk 30, targetSdk 36
- **构建**: Gradle 8.13 + AGP 8.13.2

## 构建

```bash
# Debug 构建
./gradlew assembleDebug

# Release 构建
./gradlew assembleRelease

# 代码检查
./gradlew lint
```

**输出**: `app/build/outputs/apk/debug/app-debug.apk`

## 支持的引擎

| 引擎 ID | 名称 | 服务商 | 语言支持 |
|---------|------|--------|---------|
| qwen3-tts | 通义千问3 | 阿里云百炼 | zh, en, de, fr, es, pt, it, ja, ko, ru (10种) |
| seed-tts-2.0 | 豆包语音合成2.0 | 火山引擎 | zh, en (2种) |

## 开发文档
详细开发文档请参阅[开发指南](doc/开发指南.md)

## 感谢
- [Trae](https://www.trae.cn)
- [MiniMax M2.1](https://www.minimaxi.com/news/minimax-m21)
- [Kimi Code CLI](https://moonshotai.github.io/kimi-cli/zh/guides/getting-started.html)
- [K2.5](https://platform.moonshot.cn/docs/guide/kimi-k2-5-quickstart)

## Buy Me a Mixue 🍦
<div style="text-align: left;">
  <img src="doc/images/alipay_1769136488503.webp" width="245" style="margin-right: 10px;"  alt="支付宝打赏二维码"/>
  <img src="doc/images/wechat_1769136466823.webp" width="245"  alt="微信打赏二维码"/>
</div>