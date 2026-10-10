# SoundRecorderN

这是一个多年前的 Android 录音机项目。本次更新是在旧项目基础上进行 **Kotlin 重写与现代化维护**，修正录音、存储、权限及构建兼容问题，同时保留原版白底红色曲线、两页布局和主要交互；不是重新设计一个全新应用。

离线 Android 录音机，应用代码完全使用 Kotlin。支持 **Android 8.0+（API 26）**，保留应用 ID `com.android.soundrecorder`。

沿用旧版两页布局：白底红色声纹、原计时与图标、底部录音/暂停/停止按钮，录音列表保持独立页面、文件夹分类、长按操作栏和深色播放面板。声纹与对话框局部升级，不替换原有页面结构。

## 录音与管理

| 格式 | 文件 | 编码 | 配置 |
|---|---|---|---|
| AAC | `.m4a` | AAC / MPEG-4 | 48 kHz，单声道，128 kbps |
| AMR | `.amr` | AMR-NB | 8 kHz，单声道，12.2 kbps |
| 3GPP | `.3ga` | AMR-NB / 3GPP | 8 kHz，单声道，12.2 kbps |
| WAV | `.wav` | PCM，真正的 RIFF/WAVE 文件 | 48 kHz，单声道，16 bit |

3GPP 使用 Android `audio/3gpp` 对应的音频后缀 `.3ga`，避免媒体库把 `.3gp` 自动改名。旧版 WAV 分支实际写入 AMR 数据；重写版直接采集 PCM 并生成正确文件头。

1. 点击底部中央 **录音图标**；首次使用允许麦克风权限。录音中点击同一按钮暂停，再次点击继续。
2. 点击底部 **停止图标**，自动打开原版命名/保存对话框；保存后进入独立录音列表。选择 **丢弃** 才删除草稿；关闭对话框不会丢失录音。
3. 点击底部 **列表图标** 打开录音列表。点击录音播放，在底部深色播放面板暂停或定位；长按进入原版选择操作栏，可重命名、分享或批量删除。
4. 通过录音页菜单 **录音格式 / 存储位置** 选择编码或系统文件夹选择器中的 SD 卡目录。
5. 切换应用或关闭屏幕后，录音继续由麦克风前台服务管理。从通知停止后，点击待保存通知返回命名对话框。

应用不请求网络、电话状态、通话采集或挂载权限。通知权限被拒绝时仍可录音，但通知栏控制的可见性由系统决定。音频焦点丢失、麦克风被占用、达到时长/大小限制或存储不足时会停止录音并报告原因。

## 声纹与对话框

- 红色叠层曲线恢复旧版 VoiceLine 的流动曲线形态，以**实际麦克风峰值控制高度**，不再使用滚动历史包络。曲线形状是可视化设计，不是逐采样 PCM、频谱、声纹识别或环境声压测量；小声音也有反馈，不再使用旧版高阈值过滤。
- 电平采用 `−60…0 dBFS`（相对数字满幅的峰值），每 100 ms 接收录音状态；约 70 ms 上升、250 ms 回落。接近满幅（≥ `−1 dBFS`）显示深红曲线与提示，可据此适当远离麦克风；模拟器不证明真机测量精度。
- 暂停/停止冻结曲线，继续录音恢复流动；静音逐渐回到平线并停止动画。待机仅显示不超过 3dp 的静态参考曲线。隐藏或离开页面不运行帧循环；系统关闭动画时仅更新真实音量高度，不流动。
- 绘制复用 12 个路径、128 点预计算几何，每帧只计算一组相位正余弦，录音有声时约 30 fps，不逐帧分配对象；电平文字最多每秒刷新一次。计时移到上方，声纹居中、状态位于其下，底部保留原控制区；布局使用剩余空间，横屏保留左侧内容、右侧控制列，不再固定为 360dp 方块。
- 保存、重命名、格式、存储、恢复与删除使用统一的原生 Material 对话框：圆角浅色表面、深红强调、清晰字段标签和聚焦边框。命名支持键盘 **完成**；无效名称保留输入与草稿，关闭保存对话框仍不丢失录音。


## 存储与旧版升级

- Android 10+：新录音发布到共享媒体库 `Music/SoundRecorder/`，无需授予全部音频读取权限即可管理本应用的新录音。
- Android 8–9：新录音写入 `/sdcard/SoundRecorder/`，需要存储读写权限；Android 8 起不再自动授予同组的其他权限，应用会显式申请两项。
- 自选目录：使用系统 Storage Access Framework（文件夹访问授权），支持 SD 卡；授权失效或保存失败不会删除源草稿。
- 在录音列表的菜单中点击 **显示已有录音** 并授权后，媒体库中的 `SoundRecorder`、`Recording`、`FMRecording`、`CallRecord` 目录录音会出现在列表中。文件留在原位置，不批量搬迁或删除。自选目录额外显示该目录的直接音频子文件。
- 修改其他应用创建的媒体文件时，Android 10+ 可能要求系统确认。取消确认不会修改文件。

草稿位于应用私有目录 `files/drafts/`。发布采用流式复制与 SHA-256 校验，保存成功后才清除草稿；重名或其他保存失败保留草稿，可修改名称后重试。WAV 每秒更新持久化检查点，进程意外退出后可按实际 PCM 长度恢复。压缩录音若在编码器完成容器前被系统强杀，可能无法恢复为可播放文件；应用会报告问题，不保证强杀下所有格式无损恢复。

**覆盖安装还要求与旧版使用相同签名证书。** 相同包名不足以覆盖不同签名；先导出录音和草稿，不要为安装新版本直接卸载旧版。卸载或清除应用数据会删除私有草稿，自选目录权限也需要重新授予。Android 6–7 不在本次重写版的支持范围内。

旧版工程里的通话源、FM 采集、EVRC/QCELP 和厂商多声道按键路径依赖系统/厂商私有能力，不作为普通应用功能提供；已有通话、FM 录音仍可浏览和播放。

## 构建

| 工具 | 版本 |
|---|---|
| JDK | 17 |
| Gradle Wrapper | 8.9，固定分发校验值 |
| Android Gradle Plugin | 8.7.0 |
| Kotlin | 2.0.21 |
| compile / target SDK | 35 |

安装 Android SDK 35 后设置 SDK 位置。macOS 示例：

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew :soundrecorder:assembleDebug
```

Linux/Windows 使用对应 SDK 路径与 JDK 17；Windows 调用 `gradlew.bat`。也可在未纳入版本控制的 `local.properties` 中设置 `sdk.dir`。仓库不保存开发者的 SDK 路径或签名密钥。

- 调试 APK：`soundrecorder/build/outputs/apk/debug/soundrecorder-debug.apk`
- Release：`./gradlew :soundrecorder:assembleRelease`，产物 `soundrecorder/build/outputs/apk/release/soundrecorder-release-unsigned.apk`。已启用 R8 与资源压缩；正式分发须自行使用原有证书签名。
- 静态检查：`./gradlew :soundrecorder:lintDebug`

## 设备验证

`RecordingSmoke` 使用 Android 原生 Instrumentation，无额外测试框架。它以受控峰值检查曲线音量反馈、暂停冻结和静音回落，并在设备上真实录制四种格式，检查暂停计时、容器和编码、WAV 长度/恢复、保存失败保留草稿、重名/路径拒绝、重命名和删除；还覆盖实际按钮/命名/保存、无效名称保留对话框与草稿、Android 11+ 保存/重命名的原生 IME 完成操作、旋转、播放暂停与无障碍拖动、后台启动/取消、精确文件大小上限及无界面绑定时的保存结果交付。**只在测试设备运行；先保存已有草稿。** 测试只删除自己创建的录音。

```bash
./gradlew :soundrecorder:assembleDebug :soundrecorder:assembleDebugAndroidTest
adb install -r soundrecorder/build/outputs/apk/debug/soundrecorder-debug.apk
adb install -r soundrecorder/build/outputs/apk/androidTest/debug/soundrecorder-debug-androidTest.apk
adb shell pm grant com.android.soundrecorder android.permission.RECORD_AUDIO
# Android 13+：
adb shell pm grant com.android.soundrecorder android.permission.POST_NOTIFICATIONS
# Android 8–9 另需：
# adb shell pm grant com.android.soundrecorder android.permission.READ_EXTERNAL_STORAGE
# adb shell pm grant com.android.soundrecorder android.permission.WRITE_EXTERNAL_STORAGE
adb shell am instrument -w com.android.soundrecorder.test/com.android.soundrecorder.RecordingSmoke
```

输出必须包含 `PASS:`；`adb am instrument` 的进程退出码不能单独代表测试成功。GitHub Actions 在 API 26 / 35 模拟器上执行相同检查，同时构建 Debug、Release 并运行 lint。本机验证设备为 Android 14 / API 34；模拟器不证明真机麦克风音质或所有 SD 卡提供程序兼容性。

## 实现与优化

应用仅一个模块、两个原生 Activity 和一个录音服务。复用原版 XML、主题、图标和方向布局；旧波形依赖替换为固定缓冲的原生声纹绘制。除 Kotlin 标准库外，仅直接依赖 AndroidX Core，用于安全文件 URI 与窗口 Insets；不引入 Compose、数据库或依赖注入。

| 文件 | 职责 |
|---|---|
| `Recording.kt` | 格式、状态和录音条目 |
| `AudioCapture.kt` | 平台压缩编码器、固定缓冲 PCM/WAV 采集 |
| `SoundRecorderService.kt` | 前台服务、资源生命周期、中断处理及跨界面保存结果 |
| `RecordingStore.kt` | MediaStore / 自选目录发布、校验和文件管理 |
| `SoundRecorder.kt` | 原版录音界面、格式/存储选择与命名保存 |
| `FileListActivity.kt` | 原版列表、文件夹、播放、分享及系统修改授权 |

PCM 循环复用样本/字节缓冲区，不逐帧分配；麦克风准备、WAV 写入、目录查询和录音发布移出界面线程。复制时同步计算源摘要，避免再读一遍原文件。列表使用可复用的原生行控件。未使用隐藏 API、反射、`layoutlib.jar`、JCenter 或 `largeHeap`。这些是可检查的实现变化，不宣称未经对照测量的性能倍数。

## 3.0 变更

Java 应用实现完整替换为 Kotlin，保留原版两页界面、主题和控件交互；现代化构建与权限、修正 WAV、支持安全共享/自选存储和草稿恢复，移除旧 Java 实现及无行为价值的示例测试。原有翻译、布局和图片复用；资源内的 Android Open Source Project / The Linux Foundation 版权与许可声明继续保留，仓库未替原有资源重新授权。

红色叠层声纹的视觉来源为旧版使用的 [VoiceLine](https://github.com/ws123/VoiceLine)（Copyright 2016 carlos，Apache-2.0）。当前恢复其曲线收束、层间相位与振幅关系，改为预计算几何、真实峰值控制高度和状态感知动画；来源、修改说明及完整许可仍保存在 `soundrecorder/src/main/assets/licenses/VoiceLine.txt`，不改变其他资源的原有许可。
