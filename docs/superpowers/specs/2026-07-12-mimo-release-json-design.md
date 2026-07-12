# MiMo Release JSON 协议稳定性修复设计

## 背景

MiMo TTS 在 Debug 构建中能够正常合成和播放，但 Release 构建会收到服务端 `BadRequest`。Release 启用了 R8；映射文件显示 `MiMoTtsProtocol.kt` 中供 Gson 反射使用的字段被重命名，例如 `role -> a`、`content -> b`、`messages -> a`。因此正式版发送的 JSON 不再符合 MiMo API 的字段契约，错误响应字段也无法正确解析。

## 目标

- 保证 MiMo 请求和响应 JSON 的字段名不受 R8 混淆影响。
- 保留现有 R8、资源裁剪和其他 Release 优化。
- 不扩大到其他 TTS 引擎或无关重构。

## 方案

在 `MiMoTtsProtocol.kt` 的每个网络协议属性上添加 Gson `@SerializedName`，显式声明服务端字段名：

- 请求：`model`、`messages`、`audio`、`role`、`content`、`format`、`voice`
- 成功响应：`choices`、`message`、`audio`、`data`
- 错误响应：`error`、`code`、`message`

选择字段注解而不是 `@Keep` 或全局 ProGuard 规则，是因为字段注解直接表达线上的 JSON 契约，同时仍允许 R8 优化类名及不相关实现。

## 测试策略

1. 先新增协议测试，利用反射检查所有 Gson 协议字段都具有预期的 `@SerializedName`；修改生产代码前该测试必须因缺少注解而失败。
2. 添加/保留 JSON 行为断言，确认嵌套请求序列化得到正确字段，成功和错误响应能够解析。
3. 修改协议 DTO 后重新运行 MiMo 单元测试，确认测试转绿。
4. 构建 `appRelease`，确认 R8 完成且 APK 可生成。
5. 安装新 Release APK 到已连接手机，在保留应用数据的情况下重新触发 MiMo 播放，并从 Logcat 确认不再出现因协议字段错误导致的 `BadRequest`。

## 变更范围

- 修改：`app/src/main/java/io/legado/app/service/mimo/MiMoTtsProtocol.kt`
- 新增：`app/src/test/java/io/legado/app/service/mimo/MiMoTtsProtocolTest.kt`
- 不修改现有 `app/build.gradle` 工作区改动。

## 成功标准

- 协议保护测试先失败、修复后通过。
- 全部 MiMo 单元测试通过。
- `:app:assembleAppRelease` 成功。
- 新 Release APK 在手机上能够完成 MiMo 合成并播放，且 Logcat 不再记录该请求的 `MiMo TTS failed type=BadRequest`。
