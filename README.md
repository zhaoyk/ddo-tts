本仓库基于 https://github.com/wangz-code/ddo-tts, 做了如下的修改:

- 2026-07-12: 添加了 MiMo 语音引擎支持(当前 MiMo 语音引擎是免费的)
- 2026-06-03: 朗读时 UI 及翻页逻辑修改(仿照起点等app):
  1. 手动翻页时不会打断朗读(旧为在新页面开始朗读)
  2. UI 增加 "去朗读页", "从本页读" 两个按钮. 可以实现跳转到朗读页和从任意页面开始朗读的功能

---

# tts语音引擎

## ddo-tts/内置EdgeTTS 微软大声朗读
- Edgetts基于rany2/edge-tts https://github.com/rany2/edge-tts ✅

### 为什么会有这个仓库? 
- 我曾经提交过PR挂了几个星期也没人合并, So waht ever! 我=null, 我+GPT=无所不能
- 自己有这个需求,晚上不听几章睡不着 再安装TTS有些多余,现有所谓TTS引擎本质上仍是rany2/edge-tts
- 本地电子书推荐: https://github.com/BlankRain/ebooks

### 主要修改
- 修改音频流的暂存方式 (写硬盘=>写内存)
- 原作者来是把音频缓存硬盘上会频繁执行写入和删除(有多少段落就写多少次),
- 频繁执行写入影响寿命或许对于现代存储来说影响微乎其微😋 但是我改成了放在内存中, 每读完一章就释放已读完的的媒体, 
- 跟随rany2/edge-tts EdgeVersion 143.0.3650.75

### MiMo 内置朗读
- MiMo 朗读由用户自行配置 API Key，固定使用 `mimo-v2.5-tts` 和应用提供的预置音色；不提供音色设计、声音克隆或真流式播放。
- 音色和风格可在全局设置；单本书仅可覆盖风格，留空时继承全局风格。
- 每个非空、可朗读段落通过一次非流式 WAV 请求合成；纯标点或空白段使用静音 WAV，不会产生 MiMo 请求。播放开始后会继续串行合成本章剩余段落，并预取下一章前 10 个非空段落；这可能提高内存占用和 API 用量。

~~不定时合并主仓更新最近一次是在 2026-05-08。~~ 主仓已经删库跑路了, 估计是进去了在座的各位都有责任, 进去有吃有喝，踩缝纫机还有工资拿


![detail.png](https://raw.githubusercontent.com/WangSunio/img/main/images/pre.png)

### happy every day 😄 😄
