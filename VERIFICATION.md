# 验证记录（VERIFICATION）

本文只记录**实际跑过**的东西和可复现命令。没跑过的一律单列在最后。

环境：Windows，无 Android 设备、无管理员权限；JDK 17.0.21（Temurin）、build-tools 35.0.1、platform android-35、
MediaPipe tasks-vision 0.10.21、ONNX Runtime **桌面版 1.20.0**（用于复现 App 内同一份 `.onnx`）。

---

## 1. 模型接口与预处理

```
java -cp "onnxruntime-1.20.0.jar;build\val" ShapeProbe models\w600k_r50.onnx
```
```
IN  input.1  FLOAT [-1, 3, 112, 112]
OUT 683      FLOAT [1, 512]
```

与 insightface 官方 `ArcFaceONNX` 一致：BGR→RGB（`swapRB=True`）、`(x-127.5)/127.5`、NCHW。

## 2. 特征判别力（LFW，8 个不同身份，28 对异人）

```
java -cp "onnxruntime-1.20.0.jar;build\val" ArcFaceCheck models\w600k_r50.onnx dl/faces\*.jpg
```
```
跨身份（异人）  mean = 0.063   max = 0.208
自一致（同图水平翻转，等同"同人"）  mean = 0.827
```

→ 默认阈值 0.45 落在两个分布之间，留有明显余量。

## 3. 对齐算法正确性

**3.1 几何回环**（图旋转 20° + 同样旋转后的 5 点重新对齐，与原始对齐裁剪比）
```
java -cp "onnxruntime-1.20.0.jar;build\val" AlignTest models\w600k_r50.onnx dl/faces\*.jpg
```
```
mean = 0.997   （理想 ~0.95+）
```

**3.2 直接测 App 里那份真实 `Align.similarity`（JVM，非副本）**
```
java -cp "build\jvmtest;build\classes;android.jar" JvmTest
```
```
s=1.0 rot=  17°  最大点误差=1.71e-05 px  还原尺度=1.0000
s=1.7 rot= -33°  最大点误差=3.15e-05 px  还原尺度=1.7000
s=0.6 rot=  17°  最大点误差=7.63e-06 px  还原尺度=0.6000
✓ 通过（9 组用例误差均 < 0.001px）
退化输入（全同点）返回 null ✓
```

## 4. 关键点误差敏感度（250px 人脸，单点沿 ±x/±y 偏移 d）

```
java -cp "onnxruntime-1.20.0.jar;build\val" PerturbTest models\w600k_r50.onnx dl/faces\*.jpg
```

| 偏移点 | 3px | 6px | 12px | 20px |
|---|---|---|---|---|
| 眼外角 | 0.987 | 0.969 | 0.931 | 0.878 |
| 鼻尖 | 0.994 | 0.985 | 0.964 | 0.930 |
| 嘴角 | 0.991 | 0.979 | 0.953 | 0.912 |

→ MediaPipe 正常定位误差 1~3px，影响 <1.5%；索引选偏几个像素不会毁掉精度。

## 5. 活体状态机（JVM，跑真实 `Liveness` 类）

```
java -cp "build\jvmtest;build\classes;android.jar" JvmTest
```
```
(a) 完成 3 个动作：finished=true passed=true done=3/3
    log=[blink@1200, mouth@1600, smile@1900]
(b) 静止 60s（始终不眨眼/不转头）：finished=false passed=false done=0
(c) 无脸 10s：done=0，hint=未检测到人脸
(d) 不同种子首个动作不同：1=请微笑 2=请微笑 7=请眨一下眼睛 42=请微笑
(e) 单帧全高值（转头）：done=0 —— 多帧持续要求生效
```
(b) 是**反静态照片**的关键用例：只喂高通真度但不动的观测，60 秒也过不了。
(e) 保证"一帧误检"不会顶替"持续动作"。

## 6. FAR/FRR 统计（JVM，跑真实 `Stats`）

```
java -cp build\stats iq.digitalration.faceverify.Stats
```
```
完全可分      EER = 0.00%   @0.205    目标FAR=1e-2 → 阈值 0.205, TAR=100%
高斯分离      EER = 0.25%   @0.365    目标FAR=1e-3 → 阈值 0.375, TAR=99.5%
高度重叠      EER = 34.9%   @0.361    目标FAR=1e-3 → 阈值 0.675, TAR=0.5%
```
高斯分离场景理论值 Φ(-2.5)=0.62%，样本 400 对的分辨率就是 0.25%，量级一致；重叠场景理论 ≈34%，实现正确。

## 7. APK 结构

```
aapt2 dump badging out/FaceVerify-offline.apk
apksigner verify --verbose out/FaceVerify-offline.apk
zipalign -c -p 4 out/FaceVerify-offline.apk
```
```
package: iq.digitalration.faceverify   versionName 1.0-offline
minSdk 24 / targetSdk 33
3 个 activity（Main / Verify / Eval）
resources.arsc: STORE + 4 字节对齐 ✓
lib/*/*.so: 未压缩（arm64-v8a + armeabi-v7a）
assets: face_landmarker.task(3.5MB) / w600k_r50.onnx(166MB) / selftest.jpg  未压缩
签名 v2 = true, v3 = true
```

dex 内关键类抽查（在 classes.dex 里做字节匹配）：
`FaceLandmarker` / `LandmarkProto` / `BitmapImageBuilder` / `ImmutableList` / `OrtEnvironment` / `Align` 均 FOUND。

原生库依赖检查：`libmediapipe_tasks_vision_jni.so`、`libonnxruntime.so` 只依赖系统库（无 `libc++_shared.so` 缺失风险）。

## 8. 自动化脚本

```powershell
# 完整构建（aapt2 → javac → d8 → zipalign → apksigner）
pwsh -File tools\build.ps1

# JVM 单测（对齐矩阵 + 活体状态机）
pwsh -File tools\test.ps1
```

---

## 9. 真机首测（v1.0 → v1.1）

用户在手机上安装 v1.0 后，**选照片阶段**报：

```
建模失败: NoClassDefFoundError: Failed resolution of: Lcom/google/protobuf/GeneratedMessageLite;
```

**含义与结论**：

- App 安装、启动、`166MB 模型复制`、`MediaPipe FaceLandmarker.createFromOptions`、`ORT createSession` **全部成功** —— 否则会先在初始化阶段失败。也就是说 JNI 加载与模型加载在真机上是通的。
- 失败点在**解析检测结果**：MediaPipe Tasks 的结果对象继承 protobuf-lite 的 `GeneratedMessageLite`，而我打包时只按 `tasks-vision` 的 POM 装了依赖，漏了 `tasks-core` POM 里声明的 `com.google.protobuf:protobuf-javalite:4.26.1` 及其运行时（`com.google.flogger:flogger` 被 `Graph` 直接引用，同样必需）。

**修复**（v1.1）：补入 4 个运行时 jar，并做一次全量"缺失类"扫描（`tools/depscan.mjs`，解析 class 常量池，把所有被引用但未打包的包列出来）：

| 包 | 处理 |
|---|---|
| `com/google/protobuf` | 补 `protobuf-javalite-4.26.1` ✓ |
| `com/google/common/flogger` | 补 `flogger-0.6` + `flogger-system-backend-0.6` ✓ |
| `com/google/common/util/concurrent/internal` | 补 `failureaccess-1.0.1` ✓ |
| `com/google/android/datatransport*` | **不补**：仅被 `RemoteLoggingClient` 引用，而该类无人实例化（链路已扫过） |
| `com/google/auto/value` | **不补**：纯注解，运行时不加载 |

dex 从 3.7MB 增到 4.64MB，`GeneratedMessageLite` / `FluentLogger` / `InternalFutureFailureAccess` 均已确认在 dex 内。

同时修了一个**掩盖问题的设计缺陷**：`FaceEngine.detect()` 原来只把 `fl.detect()` 包在 try 里，解析阶段的异常会逃逸或被误报成"没检测到人脸"。现在整个检测+解析都在 try 内，异常写入 `Result.error`，界面直接显示真实原因。

### v1.2 追加

- 新增**相机诊断**页（主界面入口）：显示机型/ABI/sdk、相机 id、sensor 方向、分析帧尺寸、实际使用的旋转角度、**实测帧率**、**人脸检出命中率**、以及实时画面。旋转可当场切换并持久化。
- 相机会话增加了**降级路径**：先尝试「只挂 ImageReader」，若 `onConfigureFailed` 则自动补一个离屏 `SurfaceTexture(0)` dummy 预览面重试一次（部分机型不接受无预览面会话）。

这两项都是针对「上一版在真机上唯一没跑过的部分（相机）」加的观测与兜底。

### v1.4：第二个缺失依赖（datatransport）

用户在 v1.1 上重试，选照片阶段又报：

```
建模失败: NoClassDefFoundError: Failed resolution of:
  Lcom/google/android/datatransport/runtime/TransportRuntime;
```

**根因**（反编译确认，不是猜测）：

```
TaskRunner.create(...)                       // 无条件执行
  → TasksStatsProtoLogger.create(context, task, mode)
    → new RemoteLoggingClient(context)       // 遥测上报客户端
      → TransportRuntime.initialize(context)
      → TransportRuntime.getInstance().newFactory(CCTDestination.INSTANCE)
```

也就是说 MediaPipe Tasks 在**初始化时就建了一个遥测上报器**，之前判断"该类没人实例化"是错的——它藏在 `TasksStatsProtoLogger.create()` 里，我扫的是直接引用而不是调用链。

**处理**：这不是"缺个包"，而是**一个完全离线的 App 本就不该链遥测**。所以没有拉 Firebase/datatransport 那一整棵依赖树（还要做 AndroidManifest 合并），而是按真实签名给了 8 个等价空实现：

| 类 | 行为 |
|---|---|
| `datatransport.Transport` | `send()` 直接丢弃 |
| `datatransport.TransportFactory` | 返回上面的空 Transport |
| `datatransport.Encoding` / `Event` / `Transformer` | 纯数据载体 |
| `datatransport.runtime.TransportRuntime` | `initialize` 空操作，`newFactory` 返回空工厂 |
| `datatransport.runtime.Destination` / `cct.CCTDestination` | 标记接口 + 单例常量 |

签名取自真实 `transport-api-3.0.0.aar` / `transport-backend-cct-3.1.0.aar` 的 `javap` 输出，保证 MediaPipe 按原样调用不会 `NoSuchMethodError`。

**结果**：`tools/depscan.mjs` 全量扫描 **3827 个 class / 128 个包 / 缺失 0**，`NoClassDefFoundError` 这一类问题在静态层面已清零。

## 10. 分人群「异人相似度」实验（FairFace，验证是否要为中东人脸调阈值）

数据：FairFace validation 集（10954 张，含种族标注），按种族抽取成年人各 350 张，
同一套处理流程（中心裁剪 112 + `w600k_r50`），组内全对比较：

| 组别 | n | 均值 | p95 | p99 | 最大 | >0.30 | >0.40 | >0.45 |
|---|---|---|---|---|---|---|---|---|
| **MiddleEastern** | 350 | **0.090** | 0.237 | 0.319 | 0.716 | 1.5% | 0.18% | **0.07%** |
| White | 350 | 0.096 | 0.241 | 0.316 | 0.765 | 1.5% | 0.15% | 0.04% |
| Latino | 350 | 0.089 | 0.233 | 0.306 | 0.601 | 1.1% | 0.12% | 0.05% |
| Black | 350 | 0.081 | 0.229 | 0.313 | 0.763 | 1.3% | 0.17% | 0.06% |
| Indian | 350 | 0.073 | 0.215 | 0.291 | 0.638 | 0.8% | 0.05% | 0.01% |
| EastAsian | 350 | 0.066 | 0.203 | 0.276 | 0.673 | 0.5% | 0.05% | 0.02% |
| SEAsian | 350 | 0.065 | 0.200 | 0.279 | 0.701 | 0.6% | 0.08% | 0.04% |
| LFW（对照） | 24 | 0.084 | 0.230 | 0.318 | 0.638 | 1.4% | 0.36% | 0.36% |

**结论**：中东人脸 **不是** 离群组——均值 0.090 与白人（0.096）、拉美（0.089）同档，明显高于东亚/东南亚（0.065/0.066）但也只是同量级。
在阈值 0.45 处，中东组的「同族异人误接受率」为 **0.07%**，即 FAR ≈ 7e-4，已低于 1e-3。
→ **没有证据表明需要为中东/伊拉克人脸单独提高阈值**；真正决定准确率的仍是采集条件（光照、分辨率、姿态），以及必须用本地数据做的最终标定。

注意口径：这是「异人分布」单侧测量（无同人对），不能直接当 FAR/FRR 报告；不同组之间因为用完全相同的处理流程，横向比较有效。FairFace 的 Middle Eastern 也不等于伊拉克人。

### v1.5：把「装机即崩」这类问题做成自动化护栏

前两次都是运行时缺类。只扫到「包」的粒度不够（包在、类不一定在），于是：

1. **`tools/depscan2.mjs` 升级到类级**：解析每个 class 的常量池，取出所有 `CONSTANT_Class` 引用，
   与「实际打包的类集合」逐一对账。

2. 立刻发现一个**还没爆的雷**：
   ```
   FAIL com.google.mediapipe.framework.Graph
        NoClassDefFoundError: com/google/mediapipe/proto/GraphTemplateProto$CalculatorGraphTemplate
   ```
   `Graph.loadBinaryGraphTemplate(CalculatorGraphTemplate)` 的**参数类型**被写入方法签名，
   类加载/签名解析时就要求这个类存在——而 MediaPipe 0.10.21 的 AAR 里没有它。
   `Tasks` 每次创建都会 `new Graph()`，所以用户下一定撞上。
   （另有 `CalculatorProfileProto$CalculatorProfile` 被 `GraphProfiler.getCalculatorProfiles()` 使用。）

   两者都在 we 不调用的路径上（template 加载 / profiling），按最小实现补齐即可，不会影响真实功能。

3. **新增 `LoadAllTest`**：把 classpath 上**全部 3831 个类**挨个 `Class.forName(initialize=false)`，
   任何"签名引用了不存在的类"都会在这一步暴露。

当前结果：

```
=== 类级缺失扫描 ===  被引用但不存在的类: 无
=== 全类加载测试 ===  加载成功 3831 个，失败 0 个
ALL JVM TESTS PASSED
```

这两项已并入 `tools/test.ps1`，每次改动都会跑。

### 补充：native 层与模型包的静态核对

- `FaceLandmarker` 静态块调用 `System.loadLibrary("mediapipe_tasks_vision_jni")` —— 与 APK 内打包的
  `lib/<abi>/libmediapipe_tasks_vision_jni.so` 名称一致。
- `Graph.<init>` 只调用 `nativeCreateGraph`（已导出）；我们路径上用到的
  `nativeLoadBinaryGraphBytes / nativeStartRunningGraph / nativeWaitUntilGraphIdle /
  nativeCloseAllPacketSources / nativeAddPacketInputStream` 全部有导出符号。
- `Graph` 里另有 12 个声明的 native **未被 .so 导出**（`nativeAddPacketCallback`、`nativeGetProfiler`、
  `nativeSetGraphType` 等），逐一核对调用点：**全部只出现在 `Graph` 自身的 template / profiling / surface
  方法里**，Tasks 路径不会调用。
- `face_landmarker.task` 内部结构核对：`face_detector.tflite`(230KB)、
  `face_landmarks_detector.tflite`(2.5MB)、`geometry_pipeline_metadata_landmarks.binarypb`、
  **`face_blendshapes.tflite`(955KB)** —— 活体所需的 blendshape 模型确实在包内。

### v1.5 补充：干净重建 + 交付产物覆盖检查

- **从零重建**：删除整个 `build/` 后重跑 `tools/build.ps1`，**10 秒**产出同样的 APK → 构建不依赖任何中间产物，可复现。
- **交付 dex 覆盖检查**（用 build-tools 自带的 `dexdump` 取权威类列表，而不是自己写正则）：
  ```
  dex 类 3929 个 / 应包含 3831 个 / 缺失 0 个
  ```
  多出的 98 个是 d8 为 lambda 生成的合成类。
- 过程记录：我最初用自写正则扫 dex，报了 17 个"缺失"，逐个用 `dexdump` 复核后发现**是我正则的匹配重叠 bug**，不是包的问题。已把这个检查改用 `dexdump` 并并入 `tools/test.ps1`。

### v1.6：新增「双图比对」入口（不依赖相机）

主界面新增「双图比对」：再选一张照片与参照照片做 1:1 比对，走的是与实时核验**完全相同**的
`检测 → 5 点对齐 → ArcFace → 余弦` 链路，但不碰相机。输出相似度、阈值判定、各阶段耗时、
检测点数、人脸占比、清晰度，并把两张 112×112 对齐图并排显示。

目的：在相机路径不可用时，仍能在真机上独立验证核心算法并拿到真实相似度数字。

## 11. 照片质量对相似度的影响（退化实验）

同一张人脸做退化后与其原图的余弦相似度（LFW 8 张均值，`tools/validate/DegradeTest.java`）：

| 退化条件 | 相似度 | 相对掉落 |
|---|---|---|
| 原图 | 1.000 | — |
| 人脸缩到 160px | 0.993 | −0.7% |
| 人脸缩到 120px | 0.985 | −1.5% |
| 人脸缩到 80px | 0.951 | −4.9% |
| 人脸缩到 48px | 0.779 | −22.1% |
| 人脸缩到 32px | 0.488 | −51.2% |
| JPEG q=60 | 0.989 | −1.1% |
| JPEG q=30 | 0.963 | −3.7% |
| JPEG q=15 | 0.889 | −11.1% |
| 高斯模糊 σ=2 | 0.951 | −4.9% |
| 高斯模糊 σ=4 | 0.749 | −25.1% |
| 亮度 −40 | 0.968 | −3.2% |
| 亮度 +40 | 0.959 | −4.1% |

**用途**：
1. 把 SOP 里"人脸 ≥200px"的拍脑袋要求改成实测的「≥80px 可用、≥120px 稳妥」；
2. 佐证 App 内质量门控（候选帧要求人脸宽 ≥ 画面 14%，480 宽帧下 ≈67px）落在"刚开始掉分"的位置；
3. 说明「模糊」比「压缩」更需要防——现场采集防手抖，比纠结 JPEG 质量更重要。

## 12. 活体动作自动替换（v1.7）

问题：若某动作在特定设备上抓不到（例如低帧率下眨眼只持续 1 帧），用户会被永久卡在同一个动作上。

处理：同一动作**连续超时 2 次**（每次 12s）后，自动把该动作换成序列里尚未使用的另一个动作
（候选顺序 MOUTH / SMILE / TURN / BLINK）。换的仍是「需要真实头部或面部运动」的动作，活体强度不降。

JVM 用例（`tools/validate/JvmTest.java` 的 (f)）：

```
(f) 眨不了眼：finished=true passed=true done=3
    log=[swap:blink->mouth, mouth@..., smile@..., turn@...]
```

同时 (b)「静止 60s」仍然 `passed=false` —— 替换不会让静态照片混过去。

## 13. 真机验收（v1.7，用户设备）

这是本项目**第一次拿到端到端真机证据**（此前只有 v1.0/v1.1 的报错记录）：

| 项目 | 结果 |
|---|---|
| 安装 / 启动 / 模型加载 | 正常，**无异常** |
| 参照照片建模（检测→对齐→特征） | 成功 |
| 活体挑战（眨眼/转头/张嘴或微笑） | **三个动作全部通过** |
| 1:1 比对（同一个人） | 相似度 **0.50 ~ 0.70**，高于默认阈值 0.45 → 判定通过 |
| 断网 | 全程离线运行（无网络权限） |

说明：
- 0.50~0.70 偏低但在合理区间——桌面端同图自比是 1.000、同图翻转是 0.83，说明现场两张照片之间还有光照/姿态/清晰度差异。
  与阈值 0.45 的余量约 0.05；若后续出现误拒，优先换更清晰的参照照片，或用「评测模式」按本地数据重标阈值。
- 异人分布（桌面实测）：均值 0.06~0.09、最大 0.3 左右 → 0.45 阈值不会随便放过陌生人。
- 至此，objective 中的「活体挑战-响应 + 1:1 特征比对 + 离线 + 可安装 APK + 真机跑通」全部达成。
  仍待数据输入的是「伊拉克人群阈值标定」与「被动反欺诈模型」，两者都需要样本，工具体系已就位（见 PILOT SOP）。

## 14. 体积优化与「标准版 / 精简版」双构建

原 APK 224.8 MB 的构成：ArcFace R50 模型 166.3 MB、两套 ABI 的原生库 50.2 MB、MediaPipe 模型 3.6 MB、其余 4.7 MB。

处理：
1. **只保留 arm64-v8a**（−20 MB，32 位机型不再支持）；
2. **提供 13 MB 的 w600k_mbf 精简版**（−153 MB）。

结果：

| 版本 | 体积 | 模型 | 默认阈值 |
|---|---|---|---|
| 标准 `FaceVerify-offline.apk` | 204.6 MB | r50 (166 MB) | 0.45 |
| 精简 `FaceVerify-lite.apk` | **51.3 MB** | mbf (13 MB) | 0.58 |

**代价（实测，FairFace MiddleEastern 350 张，组内异人对 61075 对）**：

| 阈值 | R50 FAR | mbf FAR |
|---|---|---|
| 0.30 | 1.47e-2 | 2.56e-1 |
| 0.40 | 1.80e-3 | 7.49e-2 |
| 0.45 | 6.88e-4 | 3.24e-2 |
| 0.50 | 2.29e-4 | 1.09e-2 |
| 0.55 | 1.15e-4 | 2.67e-3 |
| 0.60 | 6.55e-5 | 4.42e-4 |

- 同一阈值下 mbf 的 FAR 高约 40 倍，所以**必须整体抬高阈值**：FAR≈1e-3 时 R50 用 0.43、mbf 用 0.58。
- LFW 自一致（同图翻转）：R50 0.827 / mbf 0.905 —— mbf 的嵌入更"正偏"，正是需要抬阈值的原因。
- 未测：同人侧分布（FRR）。两者在各自标定阈值下的 FRR 差异，必须用真实人像对才能给出。

工程配套：
- 构建脚本参数化：`-Model r50|mbf`、`-Abi arm64|both`、`-Out`、`-VersionCode/Name`；
- 模型文件名统一为 `assets/arcface.onnx`，并在 `assets/model.conf` 里写 `id / desc / default_threshold`；
- App 启动时读 `model.conf` 设默认阈值（不覆盖用户改过的值），并把模型 id 写进参照特征；
  **换模型后旧特征自动作废并提示重建**，避免拿 R50 的 512 维去和 mbf 的 512 维比。

## 15. 证件核验（v2.0：卡背 OCR + NFC / ICAO 9303）

新增两块能力，均**在 PC 上用与 App 完全相同的类**验证（OCR 与 NFC 协议层都不依赖 Android）：

```powershell
pwsh -File tools\test.ps1      # 新增两段：卡背 OCR / NFC-ICAO
```

### 15.1 卡背 OCR（PP-OCRv4 det+rec）

合成卡背图（`tools/fixtures/card_back.png`，含家庭编号 `1002E6996574360150` 与 TD1 MRZ）:

```
图 1000x640 · 方向 0° · 行数 17 · 823ms
  1002E6996574360150                       1.00  (733,151 342x28 0°)
  IDIRQAE06443562199199607328<<            0.99  (290,583 499x25 -0°)
  9105126F2707244Q<<<<<<<<<<<<<<4          0.76  (329,618 577x23 -0°)
家庭编号: 1002E6996574360150（可信度 0.9969）
证件号  : AE0644356
出生/有效期: 910512 / 270724
MRZ 校验: doc=true dob=true exp=true composite=false
家庭编号=1002E6996574360150  MRZ=可用  证件号=AE0644356 出生=910512 有效期=270724  校验位(doc/dob/exp/composite)=✓✓✓✗
```

横拍的同一张图（`card_back_rot90.png`，640×1000）：

```
图 640x1000 · 方向 0° · 行数 17 · 761ms
家庭编号=1002E6996574360150  MRZ=可用  证件号=AE0644356 出生=910512 有效期=270724  校验位(doc/dob/exp/composite)=✓✓✓✗
```

说明：

- **composite=false 是正常的**：综合校验位覆盖整段 optional data，OCR 把 `<` 认成别的字符就会变红；
  BAC/PACE 只用证件号/出生/有效期三段，三段都对就足够。
- 方向判定这一步在竖拍图上选了 0°（DB 检测对侧躺文字仍出框），真正纠偏发生在**逐行**：
  按二阶矩取长轴把行摆正，再对每行试 0°/180° 取置信度高的方向。所以竖拍/倒拍都能读。
- 单张 1000×640 全流程 0.8 秒（PC，4 线程）；手机上按 1.5~3 秒估。
- 家庭编号做"位置感知纠错"（前 4 位数字、第 5 位字母、其余数字），单字符 `O/0`、`I/1`、`S/5` 混认不会整行丢失。

### 15.2 NFC / ICAO 9303 协议栈（45 项断言）

```
=== 1. BAC 密钥派生（ICAO 9303-11 附录示例 MRZ L898902C<369080619406236）===
  OK   Kseed  (239AB9CB282DAF66231DC5A4DF6BFBAE)
  OK   Kenc   (AB94FDECF2674FDFB9B391F85D7F76F2)
  OK   Kmac   (7962D9ECE03D1ACD4C76089DCE131543)
=== 2. AES-CMAC（RFC 4493）===
  OK   空消息 / 40 字节 / 截断 8 字节 / 64 字节
=== 3. 椭圆曲线（参考值来自 python cryptography）===
  OK   brainpoolP256r1 / secp256r1 / brainpoolP384r1 的标量乘、对端标量乘、ECDH 共享密钥
=== 4. MRZ（TD1 样本）===
  OK   格式 TD1 / 证件号 / 出生 / 有效期 / 综合校验位 / MRZi / 姓名
=== 5. EF.SOD 被动认证 ===
  OK   SOD 解析 / 签名有效 / messageDigest / 数据组哈希 3/3 / 证书链自洽 / 证书有效期
  OK   篡改后哈希不通过  (2/3)
=== 6. 假芯片：BAC 全流程 ===
  OK   读取成功 / 安全通道 BAC / DG1 / DG2（JPEG 6967 字节）/ DG11 姓名 / DG11 家庭编号 / 被动认证 / EF.COM
=== 7. 假芯片：PACE-ECDH-GM-AES-128 全流程 ===
  OK   安全通道 PACE-ECDH-GM-AES-128 · brainpool P256r1(参数 13) / DG1 / DG2 / 被动认证
=== 8. 错误 MRZ 应当失败 ===
  OK   错误 MRZ 被拒绝  (BAC 外部认证失败：6300 认证失败（MRZ 不正确）)

NFC TESTS PASSED (45)
```

要点：

- **BAC 用官方测试向量**校验 Kseed/Kenc/Kmac 三段派生（这是 ICAO 9303-11 附录的样例 MRZ）。
- **AES-CMAC 用 RFC 4493 的全部 4 组向量**校验；PACE-AES 的认证令牌与安全报文都依赖它。
- **曲线运算**用 python `cryptography` 生成 brainpoolP256r1 / secp256r1 / brainpoolP384r1 的
  私钥-公钥-共享密钥三组参考值逐字节比对（总计 9 项），说明不需要 JCE 支持 brainpool。
- **SOD** 样本由 `tools/nfc-proto/gen_nfc_sample.py` 用自签 CSCA + DS 证书生成，含
  signedAttrs（contentType + messageDigest），因此签名、messageDigest、证书链、哈希篡改检测都被真正执行到。
- **假芯片**（`NfcTest.VirtualCard`）独立实现芯片侧的 PACE 映射/密钥协商/安全报文与 BAC，
  与 App 的终端侧互相校验：APDU 结构、7C 动态认证数据、SM 的 DO87/DO99/DO8E、SSC 计数都在闭环里。
  读取 7 KB 的样本 DG 共 58 条 APDU（块长 224）。

### 15.3 体积

| 版本 | v1.7 | v2.0 | 增量 |
|---|---|---|---|
| 精简 `FaceVerify-lite.apk` | 51.3 MB | **66.3 MB** | +15.0 MB（PP-OCRv4 det 4.5 + rec 10.4 + 字典 0.03） |
| 标准 `FaceVerify-offline.apk` | 204.6 MB | 219.6 MB | +15.0 MB |

APK 内确认（`aapt2 dump permissions` / zip 列表）：`assets/ppocr_det.onnx`、`assets/ppocr_rec.onnx`、
`assets/ppocr_keys_v1.txt` 都在；权限新增 `android.permission.NFC`，`android.hardware.nfc` 声明为
`required=false`（没有 NFC 的机器仍可用光学识别）。

### 15.4 v2.1 改动（都是相机/NFC 交互层，JVM 测试覆盖不到）

| 改动 | 原因 | 验证情况 |
|---|---|---|
| 拍卡改用**后置摄像头**（`CameraController.setBackCamera(true)`，无人脸核验路径不变） | 前置拍卡要对折卡片、还会镜像，识别率差 | 编译通过；`hasBackCamera()` 探测后无后置会退回前置并提示。**未上机** |
| NFC 改成**自动感应**（MRZ 就绪即 `enableReaderMode`，贴卡自动读；成功关闭、失败保持、`onPause` 关闭、`onResume` 恢复） | 原来必须点按钮再贴卡，同事测试时容易漏一步 | 逻辑走查 + 编译；真机行为未验证 |
| 读卡失败不再关闭感应，界面提示"重新贴一次" | 一次读失败就要重新点按钮，测试体验差 | 同上 |
| 非 ISO-DEP 标签 / NFC 未开 / 无 NFC 有明确文案 | 避免同事误判成"App 坏了" | 同上 |

注：这几项改的是 Android 侧（Camera2 与 `NfcAdapter.ReaderMode`），桌面单测无法执行，
只能在与真实卡贴合的真机上确认；`tools/test.ps1` 仍保持全绿（3862 个类加载 + dex 覆盖 0 缺失）。

### 15.5 v2.2：OCR 重做（家庭编号优先）

**问题的定位**：v2.0 用的是中文 PP-OCRv4 rec（6625 类，绝大部分是汉字）。对卡背这种"18 位数字 +
阿拉伯字母标签"的版式，汉字类会抢数字的票（`1` 被认成 `了`、`O` 被认成某个汉字），而且标签完全读不出，
只能靠"哪个长数字像户号"来猜。用户反馈"识别效果很差"正是这个原因。

**改法**（全部在 PC 上用同一份代码验证）：

| 改动 | 证据 |
|---|---|
| 识别模型换成 en_PP-OCRv3_rec（436 类 ASCII）+ arabic rec（161 类） | 同一张图，中文模型把 MRZ 的 `IRQ` 读成 `|RQ`、把标签读成 `/`、`at 117`；英文模型 1.00 读对 MRZ，阿拉伯文模型把标签读成 `الرقالعائلي`（反转后即 `الرقمالعائلي`，1.00） |
| CTC 白名单（0-9 A-Z `<`） | 小写/标点/汉字不再参与 argmax |
| 低置信度行 Otsu 二值化重试 | `card_back_hard.png`（缩小 + 模糊 + 噪声）上，二值化重试把若干 0.5~0.6 的行拉回 0.9+ |
| 面积平均 + 双线性缩放（原为最近邻） | 最近邻在放大时出马赛克、缩小时出摩尔纹 |
| 认标签再取值（阿拉伯文标签归一化 + 编辑距离 ≤2） | `ژمارەی خێزانی` 被模型读成 `زمارهى خىزانى`，归一化后距离 1，能匹配 |
| MRZ 校验位反解（≤2 处混淆替换） | `OcrUnitTest` 里把 `910512` 故意改成 `91051Z` / `9I0512`，都能解回；同时验证 `AE0644356` 这种字母数字证件号不会被整体数字化 |
| 连拍 2 帧逐位投票 | `OcrUnitTest`：一帧正确、一帧错一位（`I` vs `1`），投票后回到正确值 |

`OcrUnitTest` 不需要模型，12 项断言，覆盖：标签归一（阿拉伯文 `ئ→ي`、库尔德文 `ژ/ێ/ە/ی`）、
"卡上另有一个长数字时按标签取同行那个"、库尔德文标签锚定、位置纠错、代价过高拒绝、校验位反解、投票。

**实测（`pwsh tools\test.ps1` 的 OCR 段）**：

```
--- card_back.png          1000x700 方向 0° 18 行 1267ms
  标签: الرقمالعائلي   家庭编号: 1002E6996574360150（0.9998）
  MRZ 校验: doc=true dob=true exp=true composite=false
--- card_back_rot90.png     700x1000 方向 0° 18 行 1321ms
  标签: الرقمالعائلي   家庭编号: 1002E6996574360150（0.9986）
  MRZ 校验: doc=true dob=true exp=true composite=false
--- card_back_hard.png      478x650  方向 0° 18 行 1064ms
  标签: الرقم العائلي   家庭编号: 1002E6996574360150（0.9997）
  MRZ 校验: doc=true dob=true exp=true composite=false
```

（`composite=false` 是合成图的已知现象：综合校验位覆盖整段 optional data，OCR 把 `<` 认成别的字符就会变红；
BAC/PACE 只用证件号/出生/有效期三段，三段都对即可。）

体积：lite 66.3 → **72.0 MB**，标准版 219.6 → **225.3 MB**（换模型 +6.4 MB）。
构建脚本里加了一步"先删 assets 里旧的 `ppocr_*`"，否则残留的中文模型会白占 10 MB（本轮踩到过）。

**仍未验证**：真实卡面（底纹、反光、阿拉伯文真字重、竖排版式）与真实手机相机的对焦/抖动。
合成图能证明"管线是通的、算法是对的"，但真实识别率必须靠实测照片，尤其：
1. 户号字重较细、印刷发虚时，检测框可能把整行粘成一条（`mergeLines` 会再拆）；
2. 标签被阿拉伯文长句包住时，`nearWord` 的编辑距离阈值需要按实测调；
3. 卡面有强反光/塑封高光时，二值化重试是主要兜底。

### 15.6 v2.3：户号尾部字母被"数字化"（真实反馈 `1002L00M9770006805` → `1002L0049770006805`）

**现象**：用户提供的真实户号 `1002L00M9770006805`，App 输出 `1002L0049770006805`（第 8 位的 `M` 变成 `4`）。

**两个原因，都成立**：

1. **我们自己的规则错了**。v2.2 假定"4 数字 + 1 字母 + 13 数字"，于是把尾部每一位都强行数字化，
   混淆表里恰好写了 `M→4`。真卡的尾部是**字母数字**（`1002L00M9770006805` 尾部就有 `M`），
   这个规则会把真字母改错，而且改完还"看起来合法"，不会报错。
2. **模型也会把 M 认成 4**。细字重、印刷体 M 与 4 在小字号下确实形近，单次识别会错。

**改法**：

- 结构改成 `4 数字 + 1 字母 + 13 字母数字`（数字 ≥13、字母 ≤4）。
- 混淆表只保留**真的形近**的字母（O/Q/D→0、I/L→1、Z→2、E→3、A→4、S→5、G→6、T→7、B→8、P→9），
  其余字母映射成 `?`，表示"这是真字母，不许改"。尾部字母是否数字化，还要看该位的模型概率：
  形近字母且置信度 <0.97 才改成数字，非形近字母要求置信度 ≥0.55 才保留。
- **多尺度集成**：长 alnum 行在 1.0×/1.35× 宽度 × 原图/二值化上各识别一次，逐位按模型概率加权投票。
- **歧义位给候选**：逐位保留次优字符，把只差这位的写法列入 `familyAlternatives`，界面显示"候选"。
- **可手工改正**：新增「改正家庭编号」，18 位、前 4 数字、第 5 字母的规则校验。
- **读芯片后反校**：DG11 的个人编号与光学值若长度相同且差 1~3 位，判为 OCR 认错，界面改用芯片值并写明
  "OCR 读错第 N 位 a→b，以芯片为准"。

**单测证据**（`OcrUnitTest`，15 项全过）：

```
OK   尾部字母 M 保留            (1002L00M9770006805)
OK   前 4 位形近字母仍纠正        (1002L00M9770006805)   ← 输入 1OO2L00M9770006805
OK   歧义位进候选              ([1002L0049770006805, 1002L00M9770006805])
OK   I->1 修正 / O->0 修正 / 代价过高拒绝
```

第 3 项就是这次的线上情况：识别仍是 `4`，但次优字符 `M` 被列成候选，界面能直接看到并一键改正；
如果接着读芯片，DG11 会自动把它改成 `M` 并说明差异位。

**三张合成样本回归**（`card_back / _rot90 / _hard`）：户号仍全部为 `1002E6996574360150`、置信度 0.9998、
MRZ 三项校验全过；耗时从 1.27s 增至 1.7s（PC，集成多跑 2 次识别）。

**仍未验证**：真实卡面上 `M/4`、`0/O` 的实际混淆率。集成+候选能把"错了也不知道"变成"错了能看见并改"，
但如果真实卡面上尾部字母是常态（不止一个 M），更彻底的做法是拿一批真卡统计字母位置分布后写死规则，
或与配给系统索要户号校验规则（若有）。

## 现场验证清单模块（v2.4）

把各项能力的结果汇总成一张清单并导出报告，新增 `Checklist*` / `DeviceInfo` / `ReportProvider` 五个类。

编译与打包：

```
pwsh -File tools\build.ps1 -Model mbf   → out\FaceVerify-lite.apk     (72.0 MB, 2.4-checklist-lite/14)
pwsh -File tools\build.ps1 -Model r50   → out\FaceVerify-offline.apk  (225.4 MB, 2.4-checklist-r50/14)
```

回归测试（`pwsh -File tools\test.ps1`）：

```
OCR UNIT TESTS PASSED (15)
NFC TESTS PASSED (45)
加载成功 3878 个，失败 0 个（共 3878）
ALL CLASSES LOADABLE
dex 类 4029 个 / 应包含 3878 个 / 缺失 0 个
ALL JVM TESTS PASSED
```

清单与 provider（`aapt2 dump xmltree out\FaceVerify-lite.apk`）：

```
versionCode=14  versionName="2.4-checklist-lite"
E: activity  name="...ChecklistActivity"  exported=false
E: provider  name="...ReportProvider"     exported=false
             authorities="iq.digitalration.faceverify.lite.reports"
```

→ authority 随包名由 `tools/build.ps1` 重写，标准版与精简版可以同时安装。

**顺手修的测试基建**：`tools/depscan2.mjs` 的框架类白名单不含 `org/json/`，用了 `JSONObject` 之后会被误报成
"被引用但不存在的类"；`org.json` 在 `android.jar` 里，已加入白名单。

**未验证**：清单页在真机上的交互（对话框、结论按钮、分享面板里能否选到 WhatsApp 并带上 ZIP）、
定制 ROM 上 `getInstallSourceInfo` / 签名读取的返回值、接收端打开 ZIP 的效果。以上只能上机确认。

## 16. 技术验证实验室（v2.5 / versionCode 15）

把 v2.4 的「现场验证清单」换成一页**能真跑自测**的实验室：不再只是汇总已有证据，而是按 TV 条目
逐项在本机执行（`LabActivity` + `LabReport` + `LabStore`），结果落 `files/lab/lab.json` 并可导出。

### 16.1 这一版顺手修掉的编译错误

`BioAuth.java`（TV-05）里 `prompt.authenticate(CryptoObject, Executor, Callback)` **不存在**：
API 28 起带 CryptoObject 的重载必须带 `CancellationSignal`，之前这一版整个工程是编不过的。

```
BioAuth.java:89: 错误: 不兼容的类型: CryptoObject无法转换为CancellationSignal
```

改成 `authenticate(new BiometricPrompt.CryptoObject(sig), new CancellationSignal(), a.getMainExecutor(), cb)`。

### 16.2 构建（真实输出）

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\build.ps1
```

```
[4/7] javac
[5/7] d8        dex: classes.dex 4.84MB
[7/7] align + sign
OK -> out\FaceVerify-offline.apk  (225.4 MB)  model=r50 abi=arm64
     appId=iq.digitalration.faceverify  label=离线人脸核验 R50  version=2.5-lab-r50/15

powershell -File tools\build.ps1 -Model mbf
OK -> out\FaceVerify-lite.apk  (72.1 MB)  model=mbf abi=arm64
     appId=iq.digitalration.faceverify.lite  label=离线人脸核验 Lite  version=2.5-lab-lite/15
```

APK 清单反查（`aapt2 dump xmltree`）：

```
versionCode=15  versionName="2.5-lab-r50"
activity name="...LabActivity"          （ChecklistActivity 已移除）
provider name="...ReportProvider"  authorities="iq.digitalration.faceverify.reports"
```

### 16.3 回归（`tools\test.ps1`，真实输出）

```
OCR UNIT TESTS PASSED (15)
NFC TESTS PASSED (45)
=== 被引用但不存在的类 ===  无
加载成功 3886 个，失败 0 个（共 3886）
ALL CLASSES LOADABLE
dex 类 4035 个 / 应包含 3886 个 / 缺失 0 个
ALL JVM TESTS PASSED
```

（类数从上版的 3878 增到 3886，是 Lab 与各 TV 模块新增的类；三张合成卡背图仍是户号 0.9998、MRZ 三项校验全过。）

### 16.4 实验室里每一项跑的是什么

| 条目 | 入口 | 断言 |
|---|---|---|
| TV-21 / TV-30 | `DeviceInfo.collect` | 只采集（机型 / 系统 / ABI / 内存 / 存储 / 相机 / NFC / 签名 / 权限） |
| TV-04 | `IntegrityProbe.collect` | 只采集 + 明确报"云端判决不可用（无 INTERNET 权限）" |
| TV-11 | `Receipt.tamperTest` | 正常回执过；改 payload / 改时间 / 改计数器必须被拒 |
| TV-05 | `BioAuth` | 未认证签名必须 `UserNotAuthenticated`；真认证一次拿到可验签令牌 |
| TV-08 | `DynamicCode.selfTest(30)` | 正确码 / 重放 / 过期 / 未来 / 篡改 五项全对 |
| TV-09 | `RosterStore.runFullTest(1500)` | 导入 ≤30s、查询 p95 ≤100ms、主文件写坏回退 `.bak`、半截 `.tmp` 不生效 |
| TV-10 | `OfflineQueue.runFullTest(300)` | 重复 0 条、pending 0 条、冲突 3 单、记录本地单条同步开销（网络时延现场测） |

### 16.5 仍未验证

- **实验室页没有上过真机**：抽屉/滚动布局、生物识别弹窗、`ACTION_SEND` 分享面板能否选到 WhatsApp、
  1,500 户导入在手机上的真实耗时，全部只能装机确认。
- 各 TV 自测的**手机端数字**还没有（TV-09 的 p95、TV-10 的 avgSyncUs、TV-05 的认证耗时）。
  这些正是装 APK 后点一下就能得到的，导出 ZIP 即可。
- `Play Integrity` 云端判决不可用（本包无网络权限），只给本地证据（签名/KeyStore 等级/Root 迹象）。

## 17. 实验室真机首测（2.5-lab-lite）与三处修复（2.6 / versionCode 16）

用户装 `FaceVerify-lite.apk`（2.5-lab-lite / 15）后跑完整实验室并回传报告：
`faceverify-lab-20261110-101734.zip`（设备 Xiaomi 25102RKBEC / Redmi / Android 16 / sdk 36 / arm64-v8a，
包名 `iq.digitalration.faceverify.lite`，权限只有 CAMERA + NFC，无 INTERNET）。

### 17.1 首测结果（全部为本机真实数字）

| 条目 | 结论 | 关键数字 |
|---|---|---|
| TV-21 / TV-30 设备环境 | 已采集 | Redmi 25102RKBEC · Android 16 · 8 核 · 15.7 GB · NFC 可用 · 相机 2 |
| TV-04 端侧可信性 | 已采集 | root=false · emulator=false · GMS 26.04.38 已装 · **deviceKey.exists=false** |
| TV-11 回执与防篡改 | **未通过** | cleanPass=true · payload/time 篡改被拒 · **counterTamperRejected=false** |
| TV-05 生物识别登录 | **未通过** | **SecurityException: 缺 USE_BIOMETRIC 权限** · 强制拦截=已生效 |
| TV-08 动态码 | 通过 | 30 轮 × 5 项全对（4786 ms） |
| TV-09 花名册 | **未通过** | 导入 328 ms · p95 0.94 µs · **corruptFallback=none** |
| TV-10 离线队列 | 通过 | 300 条 · 重复 0 · pending 0 · 冲突 3 · **avgSyncUs=0（测法无效）** |

### 17.2 三个失败的根因（都是我自己写错，不是能力不足）

**① TV-11 counterTamperRejected=false —— 测试把自己判成失败**
`Receipt.tamperTest` 里把计数器写死成 `put("counter", 1)`。全新安装后**第一次签发的计数器本来就是 1**，
等于什么都没改，验签当然通过。改成 `counter + 1000`，并补一项"原样重放必须被判为旧回执"。

**② TV-05 SecurityException —— 清单里少了 USE_BIOMETRIC 权限**
`BiometricPrompt.authenticate()` 在 API 28+ 要求 `android.permission.USE_BIOMETRIC`（普通权限，安装即授予），
`AndroidManifest.xml` 里没声明 → 直接抛异常，用户当然看不到指纹/人脸弹窗。
已补 `USE_BIOMETRIC`（+ `USE_FINGERPRINT` maxSdkVersion=27 给 API 24—27 的锁屏确认路径）。
APK 反查：
```
uses-permission: name='android.permission.USE_BIOMETRIC'
uses-permission: name='android.permission.USE_FINGERPRINT' maxSdkVersion='27'
```

**③ TV-09 corruptFallback=none —— 两个 bug 叠在一起**
1. **没有 .bak 可回退**：首次导入时主库还不存在，`importCsv` 不会生成 `.bak`，所以"写坏主文件"后无处可退。
   自测现在导入**两次**（模拟首次下发 + 版本切换），第二次才会把旧库转成 `.bak`。
2. **校验和存在 SharedPreferences 里，回退必然失败**：`tryLoad` 拿"当前版本"的 checksum 去校验 `.bak`（旧版本），
   永远对不上 → 直接把 `.bak` 也判成损坏。改为**校验和写在密文内容里**（`payload.checksum`，覆盖 rows），
   跟着版本走；文件完整性本身已由 AES-GCM tag 保证。

**附带修掉的两处"测了但没测到"**
- TV-10 `avgSyncUs=0`：原来只对 `put("state")` 计时（纳秒级 → 0）。现在测真实工作量：
  状态迁移 + 记录序列化 + 每轮 `save()`（fsync）摊销，并给出 `avgSyncWithDiskUs` / `saveMsTotal`；
  `syncFastEnough` 也从"恒为 true"改为真实判定（≤500 ms）。
- TV-04 `deviceKey.exists=false`、`hardwareAttested=false`、`keystoreLevel=unknown`：因为 `describe()` 只看不生成。
  改为先 `ensureSigningKey()`（带 attestation challenge）再描述，下次就能拿到证书链与 TEE 等级。

### 17.3 修复后重新构建（真实输出）

```
powershell -File tools\build.ps1 -Model mbf
OK -> out\FaceVerify-lite.apk     (72.1 MB)  version=2.6-lab-lite/16

powershell -File tools\build.ps1
OK -> out\FaceVerify-offline.apk  (225.4 MB)  version=2.6-lab-r50/16
```

JVM 回归不变：`OCR 15 项 / NFC 45 项 / 缺失类 无 / 3886 个类全加载 / dex 缺失 0 / ALL JVM TESTS PASSED`。

### 17.4 还需要再上机复核的

这三项**修完还没上机**，判据是重装 `2.6-lab-lite` 后实验室页重跑，期望：

| 条目 | 期望 |
|---|---|
| TV-11 | `counterTamperRejected=true` · `replayDetected=true` · 结论通过 |
| TV-05 | 点「生物识别登录」弹出系统指纹/人脸 → 令牌签名并验签通过；`enforcement.blocked=true` → 结论通过 |
| TV-09 | `households=1500` · `coldImportMs≈300ms` · `versionUpgradeMs≈300ms` · `hasBackup=true` · `corruptFallback=backup` · 结论通过 |
| TV-10 | `avgSyncWithDiskUs` 有真实数值（非 0），仍应远小于 500 ms |
| TV-04 | `deviceKey.exists=true`，`keystoreLevel` 应为 TEE 或 StrongBox，`hardwareBackedKey` 视机型 |

## 18. v2.7：三处可视化（动态码二维码 / 人脸登录 / 花名册查看）

真机 2.6 报告（`faceverify-lab-20261110-103416.zip`，Redmi 25102RKBEC / Android 16）7 项全部通过：
TV-04 `hardwareBackedKey=true`、`keystoreLevel=TEE`；TV-11 五项全对；TV-05 强认证 1796ms 且未认证被拦；
TV-08 30 轮全对；TV-09 `hasBackup=true`、`corruptFallback=backup`；TV-10 `avgSyncWithDiskUs=74µs`。
在这一版基础上补三个"看得见"的东西。

### 18.1 60 秒动态码：自己写二维码，且证明它是对的

工程不许联网、也不引第三方库，所以按 ISO/IEC 18004 手写 `QrCode`（Byte 模式 / M 级 / 版本 1—6 / 8 个掩码按罚分择优）。
二维码内容 `RCM1|设备号|窗口号|8位码`（40 字节，落在版本 3）。

正确性不是"看着像"，`tools/validate/QrTest.java` 做了三件独立的事：

```
=== 离线二维码（自研编码器 + 独立解码校验）===
QR TESTS PASSED (46)
```

- **独立解码器**：另写一套读矩阵的代码（格式信息两份都读并要求一致 → 去掩码 → 之字形取码 → 反交织 → 解析 Byte 模式），
  把字符串读回来逐字节比对；覆盖 1 / 14 / 26 / 42 / 62 / 84 / 106 字节与真实动态码 payload；
- **RS 独立校验**：用另一套 GF(256)（exp/log 表）算校验子 S_j = c(α^j)，j=0…ecLen-1 必须全零
  （这里踩过一次坑：QR 的生成多项式根是 α^0 起，不是 α^1 起，写成 1..ecLen 会永远报最后一位非零）；
- **结构对账**：分块表每行满足"块数×(数据+纠错)=总码字"，且总码字×8 + 剩余位 = 实测数据区模块数
  （版本 2—6 的剩余位是 7 —— 这条也踩过：一开始按"整除 8"断言，其实是 359 = 352+7）。

界面：`CodeActivity` 显示 8 位码、二维码（4 模块静区、整数倍缩放、按屏幕物理像素出图）、剩余秒进度条；
另配手动校验框，同一码第二次校验会被判重放（`DynamicCode.verify` 的 used-window 缓存）。

### 18.2 人脸登录：为什么必须分成"强"和"弱"两条路

用户反馈"生物识别只有指纹"。根因不是权限（2.6 已补 `USE_BIOMETRIC`），而是 **模态等级**：

- 带 `CryptoObject` 的认证只允许 `BIOMETRIC_STRONG`；多数国产机的 2D 人脸是 `BIOMETRIC_WEAK`，所以弹窗里没有它；
- 更要紧的是：public SDK 的 `KeyProperties` **没有** `AUTH_BIOMETRIC_WEAK`（javap 实测 android-35 只有
  `AUTH_BIOMETRIC_STRONG` / `AUTH_DEVICE_CREDENTIAL`）——**弱模态在公开 API 下解锁不了 Keystore 密钥**。

所以拆成两条通道，证据强度分开记，不混为一谈：

| 通道 | 实现 | 能证明什么 | 报告字段 |
|---|---|---|---|
| 强生物识别 | `BiometricPrompt` + `CryptoObject` + 每用一次授权的密钥 | 签名**只在**刚通过强生物识别后才可能产生（未认证时 `UserNotAuthenticated`） | `auth.cryptoBound` 隐含 true，`enforcement.blocked=true` |
| 人脸登录（弱） | `setAllowedAuthenticators(BIOMETRIC_WEAK)`，无 CryptoObject，通过后普通设备密钥签名 | 系统刚弹过窗且用户过了认证（应用侧可信），但**签名本身不绑定**生物认证 | `weakAuth.cryptoBound=false` + `note` 明写 |

TV-05 的通过判据仍然只看强通道（`auth.ok && enforcement.blocked`）；人脸那一路作为**附加证据**列在报告里，
`LabReport` 的摘要会显示 `强=已签名 xxxms · 弱(人脸/指纹)=通过 xxxms · 强制=已拦截`。

### 18.3 花名册查看：顺带修了"户数=9,000"的数据模型错

原来测试 CSV 把成员行写成 `户号_M1` 这类独立户号，导入后索引里 9,000 个键 —— 报告里 `households` 直接等于行数，
看不出"1,500 户、每户 5 人"。现在 CSV 加第 7 列 `member`：

- 户主行 `member` 为空 → 建户对象，带 `memberList` 数组；
- 成员行同 `familyNo`、`member=M1..M5` → 挂到该户的 `memberList` 下（成员行先到也能先占位后补）。

自测输出改为 `households=1500`、`members=7500`、`rows=9000` 三个数，不再混用。
`RosterActivity` 提供按户号模糊搜索（内存索引，与门禁同一条路径）、点行看明细与成员、页内导入 1,500 户、清空本机花名册；
`RosterStore.browse()` 最多返回 50 行，`status()` 顺带报 `source / mainBytes / hasBackup / checksumOk`。

### 18.4 构建与回归（真实输出）

```
powershell -File tools\build.ps1          → out\FaceVerify-offline.apk 225.4 MB  version=2.7-lab-r50/17
powershell -File tools\build.ps1 -Model mbf → out\FaceVerify-lite.apk   72.1 MB  version=2.7-lab-lite/17

tools\test.ps1:
  OCR UNIT TESTS PASSED (15)
  NFC TESTS PASSED (45)
  QR TESTS PASSED (46)            ← 新增
  被引用但不存在的类：无
  加载成功 3892 个，失败 0 个
  dex 类 4061 个 / 应包含 3892 个 / 缺失 0 个
  ALL JVM TESTS PASSED
```

### 18.5 仍未验证

- 三个新页面（动态码、人脸登录、花名册查看）**都没上过真机**：二维码能否被微信/相机扫出、
  人脸弹窗在这台 Redmi 上是否真的出现并成功、花名册搜索框在小屏上的布局与键盘遮挡。
- 二维码只做了"自证"（编码器 ↔ 独立解码器 + RS 校验子 + 结构对账），**没有第三方扫码器实测**；
  现场用另一台手机扫一次，才算真通过。
- 人脸那条路的签名不绑定生物认证，这是 Android 公开 API 的限制，不是实现取巧；要不要接受由业务方定。

## 19. v2.8：人脸登录改走 App 内前置摄像头（2.8 / versionCode 18）

### 19.1 真机现象（证据）

`faceverify-lab-20261110-105517`（2.7-lab-lite / 17，Redmi 25102RKBEC / Android 16 / MIUI）：

```
capabilities: apiPrompt=true fingerprint=true face=true front摄像头(env.camera)=1
auth     : mode=biometric-prompt-strong  ms=1303
weakAuth : mode=biometric-prompt-weak    authType=2  cryptoBound=false
```

用户点「人脸登录」时系统弹的是**屏下指纹**，前置摄像头根本没启动。这不是权限问题（`USE_BIOMETRIC` 已在 2.6 补上、
`capabilities.face=true`），而是 `BiometricPrompt.setAllowedAuthenticators(BIOMETRIC_WEAK)` 的语义：
它只是"这些档位都允许"，**不能指定只要人脸**，系统会在已录入的模态里自己挑——指纹更快更稳，所以总是给指纹。
换言之：**只要目标机型录了指纹，系统弹窗这条路就永远做不到"前置摄像头刷脸"**。

### 19.2 改法：App 内自建一条人脸登录通道

新增 `FaceLoginActivity`，链路与核验页同一条（同一套引擎、同一套对齐、同一套阈值）：

```
前置相机(Camera2) → MediaPipe 478 点 + blendshape → 随机 3 个活体动作
   → 采集 6 帧并做几何门控 → 5 点对齐 112×112 → ArcFace 特征平均
   → 与登记参照算余弦（登记过才有）→ 设备密钥签发登录令牌
```

证据强度照实写进结果 JSON，不冒充系统强生物识别：

| 字段 | 含义 |
|---|---|
| `mode=front-camera-face` | 通道标识；与 `biometric-prompt-strong/weak` 区分开 |
| `cameraFront=true` + `camera` + `frame` | **真的用前置相机**（相机 id 带"(前)"，附分析帧尺寸） |
| `livenessPassed` / `livenessActions` / `blink·yawPeak·jaw·smile` | 挑战-响应活体（静态照片无法完成随机 3 动作） |
| `identityBound=true/false` | 登记过参照人脸才是"证明了是谁"；没登记只有活体证据（`note` 明写） |
| `similarity` / `threshold` | 仅 `identityBound=true` 时出现 |
| `cryptoBound=false` | 2D 人脸解锁不了 Keystore 生物密钥（public SDK 无 `AUTH_BIOMETRIC_WEAK`），令牌由普通设备密钥签名 |
| `token.verified` | 令牌自验签结果 |

实验室卡片现在三个入口：**强生物识别**（系统强模态 + CryptoObject）/ **人脸登录（前置）**（v2.8 新路）/ **系统认证（指纹/人脸）**（v2.8 新增，允许密码兜底）。
结果写 `results.bio.faceAuth`；`LabActivity.bioProbe()` 重跑自测时会保留它（不会因为重跑把这条证据冲掉）。

TV-05 的通过判据**不变**（仍只看 `auth.ok && enforcement.blocked`）——人脸这几条是附加证据，
`LabReport` 摘要变成：

```
强=已签名 1303ms · 系统认证=生物识别 1273ms · 前置人脸=活体通过 相似度0.86/阈值0.58 · 强制=已拦截
```

### 19.3 构建与回归（真实输出）

```
powershell -File tools\build.ps1 -Model mbf → out\FaceVerify-lite.apk 72.1 MB  version=2.8-lab-lite/18
aapt2 dump xmltree（APK 内清单）→ activity .FaceLoginActivity 已注册
tools\test.ps1:
  NFC TESTS PASSED (45)
  QR TESTS PASSED (46)
  被引用但不存在的类：无
  加载成功 3897 个，失败 0 个
  dex 类 4078 个 / 应包含 3897 个 / 缺失 0 个   ← 新增 5 个 FaceLoginActivity 内部类
  ALL JVM TESTS PASSED
```

顺带修 `tools/test.ps1` 的一个假失败：dex 覆盖检查原来写死读 `out\FaceVerify-offline.apk`，
只构建 lite 时会拿新 classes 比旧 APK，报"缺失 5 个"；现在按最近构建的 APK 检查。

### 19.4 仍未验证

- **前置相机人脸登录没上过真机**：这次只做了构建与 JVM 回归。手机上要确认三件事——
  前置画面方向是否正确（`CameraController` 前置按 `sensorOrientation + displayRotation` 计，若画面倒转，
  主界面「调试 → 分析帧旋转」的手动 90/270 覆盖对这个页面同样生效）、
  活体三个动作在低帧率下能否抓到、6 帧采集在弱光下的清晰度是否够阈值。
- 这台机器上 `hasRef=false`（没登记参照人脸），所以首次测只会走"仅活体"这一档；
  要看到 1:1 相似度，得先在主界面「① 从相册选择一张人像」建模。
- 阈值仍是 0.58（mbf 按 FAR≈1e-3 标定），人脸登录沿用同一阈值与同一模型，**没有单独标定**。

### 19.5 「系统弹窗只给指纹」是平台行为，以及三条通道各自的凭据强度

真机反馈："点系统弹窗（对照）还是让指纹，无法确认走了手机自带的人脸"。这不是权限、也不是实现问题：

- `BiometricPrompt.Builder.setAllowedAuthenticators(...)` 的语义是"这些档位都允许"，**没有 per-modality 约束**；
  系统在已录入的模态里自选（指纹更快更稳 → 永远先给指纹）。Redmi 25102RKBEC / Android 16 上实测如此。
- `AuthenticationResult.getAuthenticationType()` 只区分 `BIOMETRIC`(1) 与 `DEVICE_CREDENTIAL`(2)，
  **不告诉应用用的是指纹还是人脸**，所以"确认走了人脸"从公开 API 上根本拿不到。

v2.8 因此把系统那条通道升级为 **系统认证（`BIOMETRIC_WEAK | DEVICE_CREDENTIAL`）**：指纹 / 2D·3D 人脸 / 锁屏密码
任一通过即"系统认证通过"，用设备密钥签发凭据（`payload.systemAuth=true`、`payload.authType`、`authTypeText`）。
注意含 `DEVICE_CREDENTIAL` 时**不能再设 negative button**，否则构造直接抛 `IllegalArgumentException`。

三条通道能证明什么，分开看：

| 通道 | 入口 | 能证明 | 字段 |
|---|---|---|---|
| 强生物识别 | 「强生物识别」 | 指纹 / **3D 结构光人脸**（BIOMETRIC_STRONG 档）刚通过，且签名绑定认证（未认证签不出名） | `auth.mode=biometric-prompt-strong`，`enforcement.blocked=true` |
| 系统认证 | 「系统认证（指纹/人脸）」 | **系统**判定认证通过（含密码兜底），并固化成票据 | `systemAuth.systemAuth=true`、`authType` |
| 前置人脸 | 「人脸登录（前置）」 | 镜头前是真人（随机 3 动作活体）+ 与参照 1:1（登记过时） | `faceAuth.cameraFront=true`、`livenessPassed`、`identityBound` |

**要让"手机自带人脸"成为系统认证凭据，只有两种情况：**

1. 设备人脸是 **BIOMETRIC_STRONG**（3D 结构光/ToF，多数旗舰）：它本来就在「强生物识别」那条路的可选模态里，
   走 CryptoObject 认证时系统可能用它 —— 此时 `auth` 就是"人脸通过的系统认证凭据"（签名绑定认证，强度最高）。
   本机（Redmi 25102RKBEC）的 2D 人脸是 WEAK 档，不在其中。
2. **现场自证（土办法，能让 2D 人脸在系统弹窗里出现）**：系统设置 → 指纹 → 删掉已录指纹，只保留人脸解锁，
   再点「系统认证」。系统没有指纹可用时只能用人脸，通过后 `authType=1` —— 这就证明这台机器的系统弹窗
   可以承载人脸，凭据链路本身没问题。测完把指纹加回去即可。
   （若删掉指纹后 `canAuthenticate(BIOMETRIC_WEAK)` 直接返回不可用，说明该 OEM 没把人脸注册成生物识别模态，
   这条通道永远只能指纹/密码，只能依赖 App 内相机那条路。）

### 19.6 仍未验证（系统认证通道）

- `authenticateSystem` 的对话框形态与 `authType` 取值**只上过模拟推演，没上真机**：含 `DEVICE_CREDENTIAL` 时
  MIUI 的弹窗样式、按指纹后报 `authType=1` 还是 `2`、以及"删指纹只留人脸"后是否真的弹脸，都要在手机上确认。
- 若 OEM 弹窗对 `BIOMETRIC_WEAK | DEVICE_CREDENTIAL` 组合有兼容性问题（个别 ROM 会直接报 `ERROR_NO_BIOMETRICS`），
  需要按报告里的 `capabilities.canWeak / canCredential` 兜底回到 `BIOMETRIC_WEAK` 单模态（代码里保留 `BioAuth.authenticateWeak`）。

## 20. 活体结果口径修正（"没让转头，结果里却有转头 0.04"）

真机反馈：实时核验开始后提示的是眨眼/张嘴/微笑，**没有让转头**，但结果页"峰值"那行出现 `转头 0.04`
（其他三个 0.54 以上）。查下来是**显示口径**问题，不是判定错，也不是数据造假：

- `Liveness.State.maxYaw` 是**每帧**都累计的全程峰值（`st.maxYaw = max(maxYaw, |yaw|)`），
  正常说话/轻微点头晃动就会到 0.03~0.06；而转头的触发阈值 `YAW_TURN = 0.20`，0.04 离通过差得远。
- 旧结果页把这四个全程峰值统一渲染成"峰值 眨眼/转头/张嘴/微笑"，读起来就像"转头被计了分"。
- 活体判定本来就不看峰值：它看的是**抽到的 3 个动作是否按顺序完成**（超时会自动换动作）。

修正（不改判定逻辑，只改口径与字段）：

| 新增 | 含义 |
|---|---|
| `State.tasks` / `tasksText()` | 本局实际抽到的动作序列（含超时替换后的结果），例如「眨眼 → 张嘴 → 微笑」 |
| `State.blinkPeak/turnPeak/jawPeak/smilePeak` | **只在各自动作进行期间**累计的达成峰值；没抽到的动作恒为 0 |
| `State.peaksText()` | 只列抽到的动作与峰值，例如「眨眼0.62 · 张嘴0.58 · 微笑0.61」 |
| `lab.json` 的 `tasks` / `taskPeaks` | 报告里能看出"这局到底考了什么、各自多少" |

结果页现在分三行：`抽到动作：…` / `各动作峰值：…` / `全程峰值（含自然微动，不作判据）…`。
前置相机人脸登录页（`FaceLoginActivity`）用同一套口径。

回归（`tools\test.ps1` 新增断言，真实输出）：

```
(g) turnPeak=0.00 maxYaw=0.90     ← 首动作不是转头时，即使画面 yaw 到 0.90，"转头"峰值仍为 0
ALL JVM TESTS PASSED
```

> 说明：这条断言最初写成"找一个不含转头的种子"，结果 `Collections.shuffle` 对 4 选 3 的排列在 seed 1—499
> 里恰好都含转头（4 元素的小列表，Random 前几个种子的排列很集中），说明"按随机种子挑场景"的测试不可靠；
> 改成"找首动作不是转头的种子 + 人为喂 0.90 的 yaw"，才是稳定的确定性用例。

## 21. 现场 NFC 读卡失败诊断（2.8-lab-lite，真卡首次上机）

现场同事回传的原始日志：

```
选择 eMRTD 应用… 应用 A0000002471001 已选中
读取 EF.CardAccess… EF.CardAccess 读取失败(选择 EF011C 失败:6982 安全状态不满足(未认证))，按无 PACE 处理
BAC 认证… 失败:TagLostException: Tag was lost.
```

### 21.1 逐行读这段日志

| 日志 | 含义 | 是不是问题 |
|---|---|---|
| `应用 A0000002471001 已选中` | ISO-DEP 通了、AID 正确、芯片是 eMRTD | 正常 |
| `选择 EF011C 失败:6982` | 芯片**用 6982 回绝** EF.CardAccess：不开放这个文件（不支持 PACE，或要求先认证）。ICAO 里"不支持 PACE"本该回 6A82，但这批芯片回的是 6982 | **不是故障**，只是 PACE 不可用 → 回退 BAC 是正确路径 |
| `BAC 认证 失败:TagLostException` | 物理层断连：卡离开/移动了感应区，或会话被复位 | **这才是失败原因** |

关键判据：如果 MRZ 输错，BAC 会回 `6300`；如果芯片只支持 PACE 而拒绝 BAC，会回 `6982/6A81`。
**TagLost 只能是物理断连**（`IsoDep.transceive` 拿不到响应），不是密码学错误。

### 21.2 已改的四处

1. **6982 不再当"读卡失败"**：`CardSession.readFile(int fid, boolean optional)` 新增可选语义，
   `6A82 / 6A88 / 6D00 / 6982` 都视为"该文件不可用"返回 `null`；只有 `6A86`（参数错）这类真错误才抛异常。
   `IcaoReader` 用它探测 `EF.CardAccess`，日志改为「芯片不开放 EF.CardAccess（6A82/6982），按无 PACE 处理（不影响 BAC）」。
2. **BAC 前重新 SELECT AID**：选择文件被拒后，部分芯片会停在"文件上下文/半认证"状态，
   直接 `GET CHALLENGE` 容易把整条链路重置（现场就表现成 TagLost）。现在走 BAC 之前重新选一次应用（标准允许重复 SELECT）。
3. **presence check 250ms → 600ms**：平台每隔 N 毫秒探测"卡还在不在"，探测本身要插一次 ISO-DEP 交互。
   250ms 对某些芯片过密，调到 600ms 减少干扰（`EXTRA_READER_PRESENCE_CHECK_DELAY`）。
4. **失败提示可操作 + 带诊断**：
   - `TagLost` → 「卡离开了感应区（TagLost）。请把证件背面贴牢…读 DG2 人像要 5~15 秒，全程不要移动或拿开；重贴会自动再试一次」；
   - 界面额外显示 **ATS/历史字节** 与 **APDU 条数 + 耗时**（在哪一步断的，看下方日志）；
   - BAC 被拒时按 SW 给具体原因：`6300` → MRZ 不一致；`6982/6A81` → 该卡可能只支持 PACE。

### 21.3 新增单测（不需要真卡）

```
=== 1b. 可选文件（EF.CardAccess）的拒绝码语义 ===
  OK SELECT 6982 → 视为不可用（不抛异常） (6982)
  OK SELECT 6A82 → 视为不存在 (6A82)
  OK SELECT 6D00 → 视为不支持 (6D00)
  OK SELECT 6A86 → 仍抛异常（真错误不吞） (6A86)
NFC TESTS PASSED (49)
```

### 21.4 现场排查清单（按顺序做）

1. **贴牢 10 秒不要动**：读 DG2 人像要 5~15 秒、上百条 APDU，中途移动必然 TagLost。把卡平放在手机背面上半部（摄像头附近），用手指按住。
2. **去掉手机壳**（尤其磁吸壳、金属壳、带卡槽的皮套），NFC 天线在背面上半部。
3. **换一个位置慢慢挪**：不同机型天线位置不同；贴上去听到"叮"再停住。
4. **确认 MRZ 三项与卡面完全一致**（证件号 / 出生 YYMMDD / 有效期 YYMMDD）。MRZ 错会返回 `6300`，不会 TagLost。
5. 如果反复 TagLost 且换手机也一样，把界面上的 **ATS/历史字节** 与 **APDU 条数** 截图发回——
   ATS 能区分"卡片太远/复位"与"这卡不是标准 eMRTD 结构"。

### 21.5 仍未验证

- 以上改动**没有在真卡上重跑**（现场只有一次失败记录，且失败在物理层）。
- 如果重试后 BAC 返回 `6982/6A81`，说明这张卡只支持 PACE 且 `EF.CardAccess` 受保护——这种情况需要
  **预置 PACE 参数**（OID + 密码）才能读，属于新的工作量，不是改文案能解决的。

## 22. 读卡日志可导出 + 卡背 MRZ 小字漏检（2.8-lab-lite）

### 22.1 读卡日志：做成能直接发回来的文件

现场问题：读卡失败时日志在界面里要滚动才能看全，截图经常缺前半段，远程没法定位。

做法：

- 每次读卡（**成功或失败**）都写一份 `Android/data/<包名>/files/lab-export/nfc-log-<时间戳>.txt`，内容包含：
  时间、应用与版本、设备与系统、AID、MRZi、**卡片 ATS/历史字节**、结果、失败原因、安全通道、APDU 条数/耗时、逐条步骤日志；
- 界面新增按钮「导出读卡日志（发给研发分析）」→ 走系统分享（微信/邮件都能发）；
- `ReportProvider` 放开 `.txt`（仍然只读、只允许导出目录内的文件，`/` 与 `..` 一律拒绝）；
- 「导出验证报告」ZIP 里额外带 `nfc/nfc-log-*.txt`（取最近一份），现场只导一次报告也能带上读卡日志。

### 22.2 MRZ 漏检：卡在画面里太小，整行检测不到

现场截图（`1001L0001262511103` 已识别，证件号/出生/有效期全空，提示「MRZ 不完整」）：

- 卡背图片肉眼看很清晰，但**卡只占画面约 1/3**；`recognize()` 还会把最长边压到 1280，
  MRZ 每字符只剩 6~10px，DBNet 整行不出框；户号字号大、笔画粗，所以能读出来。
  这不是"照片不清"，是"字在算法眼里太小"。

修正：

1. `OcrCore.recognizeCard()`：整幅识别后若 **MRZ 行 < 2**，把图转正 → 裁下半部（0.55 / 0.40 / 0.70 三个高度）→
   放大 1.2~2.5 倍（不超过 2400px）→ 重新检测识别；检测的 `maxSide` 传放大后的尺寸，避免又被 `MAX_SIDE` 压回去。
   补扫总耗时限制 2 秒，卡真的很小时最多多花两次检测。
2. 正常大小的卡（≥400px 宽）不受影响：MRZ 已够两行，直接返回，不跑补扫。
3. **副作用修复**：小图上 MRZ 的片段会被 `CardFields.findFamilyNo` 当成户号（实测报出 `9185I26F2707244IR6`）。
   现在 `findFamilyNo` 跳过"像 MRZ 的行"（含 `<<` / ≥24 位 alnum / 前 6 位数字且第 8 位是 `M|F|<`）。
   户号格式是 4 位数字 + 1 位字母 + 13 位字母数字，前 6 位必含字母，不会误伤。
4. 提示更如实：MRZ 可用但**校验位有误**时写「读卡可能失败（建议手输）」，不再笼统报"MRZ 可用"；
   MRZ 不可用时给出「把卡靠近、让卡片占满取景框再拍 / 用手输 MRZ」的操作指引。

回归（`tools\test.ps1`，新增断言）：

```
=== small card (1280x960, card width 300px) ===
  auto: family=null mrzUsable=false doc=null (829ms)          ← 整幅识别：整行 MRZ 漏检
  card: family=1002E6996574380150 mrzUsable=true doc=26F279T24 (5697ms)  ← 补扫：MRZ 行救回来了
```

断言两条：`auto: family=null mrzUsable=false`（复现漏检）与 `card: ... mrzUsable=true`（补扫有效）。
`OCR UNIT TESTS PASSED (15)` / `NFC TESTS PASSED (49)` / `ALL JVM TESTS PASSED`。

### 22.3 顺带修的一个工具链坑

`build\jvmtest` 里残留的旧 class 会**盖住** `build\classes`（classpath 顺序在前），表现成
`NoSuchMethodError: recognizeCard`——代码明明改了却报"方法不存在"。这已经是第三次踩到
（之前是 `Liveness.tasks`）。`tools\test.ps1` 现在每次先清空 `build\jvmtest`。

### 22.4 仍未验证

- 补扫**没有在真机上复测**：300px 是合成图模拟，真实相机的对焦、反光、卡面全息带都会影响检出率。
- 极限小图（300px）下即使补扫成功，`docNumber` 仍可能读错（示例 `26F279T24`），所以界面对校验位有误会提示手输。
- ATS/历史字节字段依赖具体机型返回，部分 ROM 可能为空（界面会显示"未取到"）。

## 23. 识别日志一键导出（微信可发）+ MRZ 条带兜底（2.8-lab-lite）

### 23.1 日志：本地落文件 + 一键发微信

现场要求："导出读卡日志要生成本地文件，然后一键分享到微信"。之前只发 `text/plain`，
微信对 `ACTION_SEND + text/plain + content://` 的接收不稳定，所以改成：

1. **本地文件**：`Android/data/<包名>/files/lab-export/idcard-diag-<时间戳>.txt`
   （OCR 每行原文 + NFC 读卡步骤合并成一份）；
2. **一键分享**：同名 `.zip` 由 `LabStore.zipLog()` 生成，`Intent.ACTION_SEND` + `application/zip`
   → 系统选择器 → 微信/邮件/网盘都能收（与「导出验证报告」同一形态）；
3. 按钮文案改成「导出识别日志（OCR + 读卡，一键发微信）」，点击后 toast 直接显示落盘路径；
4. 「导出验证报告」ZIP 里同时带 `nfc/` 与 `ocr/` 两份最新日志（`ocr-log-*.txt` / `nfc-log-*.txt`）。

日志内容（OCR 部分）逐行给出 `置信度 + 原文`，这就是判断"照片看着清楚、算法却读不到"的唯一依据。

### 23.2 MRZ 仍然读不到：加一层跳过检测的条带识别

现场第二次反馈：卡已经**占满取景框**，MRZ 三行肉眼极清楚（`IDIRQAC6764883…` / `7602067M2705088IRQ…`），
仍然"MRZ 不完整"。说明不只是字号问题，DBNet 对低对比、带全息反光的等宽 MRZ 行也可能整行不出框。

新增兜底 `OcrCore.scanMrzBands()`：**不经过检测模型**，直接在图像下部按水平条带
（带宽 ≈ 图高 3.2%、步进 50%）逐条送识别模型，只有满足 MRZ 结构的才收：

- TD1 第 1 行：以 `ID` / `I<` / `AC` / `A<` / `C<` 开头且长度 ≥ 28；
- TD1 第 2 行：前 6 位数字且第 8 位是 `M`/`F`/`<`。

条带区域优先用已识别的文字行定位（取卡片下半部），避免全图盲扫；整段限时 2.5 秒。
误报控制：条带只有"结构化 MRZ"才进候选，户号行（18 位）与阿拉伯文标签都不会命中。

### 23.3 顺带把识别耗时压下来

两帧都跑兜底会把一次识别拖到 20 秒以上（现场实测 24s/2 帧）。现在：

| 帧 | 策略 |
|---|---|
| 第 1 帧 | 深度识别：全图 → 下半部放大补扫 → 条带兜底 |
| 第 2 帧 | 只做全图（`recognizeAuto`），用于投票 |

`OcrEngine.scan(bmp, deep)` 暴露这个开关；`IdCardActivity` 按帧序调用。

### 23.4 回归与仍未验证

```
OCR UNIT TESTS PASSED (15)
NFC TESTS PASSED (49)
QR TESTS PASSED (46)
ALL JVM TESTS PASSED
```

- 条带兜底**没有在真机上验证过**：现场那张卡的每行 OCR 原文要先通过「导出识别日志」拿回来，
  才能确认是"整行没检出"还是"检出但识别错"。
- 若日志显示 MRZ 行被检出、文本接近正确但 `findMrz` 仍不认，下一步按日志修 `findMrz` 的行判定
  （例如允许 1~2 位残缺、用校验位反解补齐）。

## 24. 真机日志分析：MRZ 不是"看不清"，是被 DBNet 切成碎片

现场回传 `idcard-diag-20261110-154411.zip`（2.8-lab-lite / 18，Redmi 25102RKBEC / Android 16），
OCR 日志关键数据：

```
帧数：2 · 耗时：15591ms
结果：户号=1002L00M9770006805  MRZ可用=false  校验位(doc/dob/exp)=false/false/false

— 第 1 帧 · 方向 0° · 79 行 · 10362ms
     98%  1002L00M9770006805         ← 户号完整
     96%  IDIRQAC6764                ← MRZ 第 1 行只剩前 12 字符
     96%  7602067M21                 ← MRZ 第 2 行只剩前 11 字符
     93%  ALEQYLYSEL                 ← MRZ 第 3 行前 10 字符
     38%  X4E388                     ← 后面全是碎块
     92%  380M40323024
    100%  D / 100%  H / 99% PS / …

— 第 2 帧 · 方向 0° · 39 行 · 5228ms
     97%  IDIRQAC67648（13 字符）
    100%  7602067M27（10 字符）
```

**根因**：MRZ 三行每行都被 DBNet 切成 2~4 段（字符间距 + 卡面底纹 + 中间那条全息防伪带干扰），
最长的一段只有 13 字符；而 `CardFields.findMrz` 的门槛是 `sanitizeMrz(text).length() >= 26`，
所以**所有片段都被丢弃**，`mrzFound=false` → 界面报「MRZ 不完整」。

这不是清晰度问题（户号 98%/99% 读得很准），是**行被切碎后没有拼回去**。
拼文本也没用：右半段是 `X4E388` / `380M40323024` 这种乱码，必须按整行重新识别。

### 24.1 修法：按"行组外接框"整行重识别

`OcrCore.rereadSplitLines()`（新增，`recognizeCard` 在 MRZ 行不足 2 时调用）：

1. 把已识别的行按 `cy` 聚类（阈值 `0.8 × 行高`，比原来的 0.55 宽——碎块常被底纹带偏几个像素）；
2. 组内还要水平邻近（`|Δcx| < (w1+w2) × 0.85`），避免把"标签 + 值"这种贴在一起的两段并进来；
3. 取组内所有 bbox 的并集（外扩 2px），裁出来**整行重新识别**（白名单 `0-9A-Z<`，48px 高）；
4. 只有 `置信度 ≥ 0.55 且长度 ≥ 6` 才作为新的一行加入；高度超过图高 15% 的组直接跳过（说明跨了多行）。

这样 `IDIRQAC6764` + 右半段会被还原成完整 30 字符的 `IDIRQAC6764…`，`findMrz` 才能接手。
已有两行 MRZ 的卡**不触发**（`countMrzLike < 2` 才跑），正常卡零额外开销。

### 24.2 为什么这次不靠条带兜底

`scanMrzBands()`（第 23 节加的）在这张卡上没产出合格行：条带固定高度（图高 3.2%）会跨越两行 MRZ
或只切到字符上半身，识别结果结构不符合 TD1。它能救的是"整行漏检但行高合适"的情况，
这张卡是"行被切碎"，所以先走行组重识别。条带保留为最后兜底。

### 24.3 回归（真实输出）

```
auto: family=null mrzUsable=false doc=null (832ms)                      ← 300px 小卡：整幅仍漏
card: family=1002E6996574380150 mrzUsable=true doc=26F279T24 (5323ms)   ← 深度识别救回
家庭编号=1002E6996574360150  MRZ=可用 …                                 ← 正常合成卡不退化
OCR UNIT TESTS PASSED (15) / NFC TESTS PASSED (49) / QR TESTS PASSED (46)
ALL JVM TESTS PASSED
```

### 24.4 仍未验证

- 行组重识别**只在合成图上验证了不退化**，还没在真机上跑过这张卡。按日志的碎片形态（左段 12~13 字符、
  右段被切成小碎块）判断应该能拼回整行，但必须在手机上再测一次。
- 如果再测仍失败，下一步用日志里的 `框坐标` 直接定位：`OcrActivity` 的日志目前只记录文本，
  需要补 `cx/cy/w/h` 才能判断聚类是否把两段分到了一组（下一版加）。

## 25. 第二次真机日志：MRZ 只检出左边 12 个字符（图像被压缩 + 右半段漏检）

`idcard-diag-20261110-155457.zip`（2.8-lab-lite / 18，同一台 Redmi）：

```
第 1 帧 · 80 行 · 10231ms
   91%  IDIRQAC67        x=254 y=388 w=183 h=24     ← MRZ 第 1 行只有 12 字符
  100%  IDIRQAC676       x=271 y=388 w=223 h=33     ← 行组重识别也只拿到 9 字符
  100%  7602067M2        x=257 y=425 w=186 h=24     ← 第 2 行同样只有左半段
   94%  ALEQYLYSE        x=275 y=461 w=212 h=22
第 2 帧 · 24 行 · 3578ms
   97%  IDIRQAC6764      x=277 y=397 w=229 h=23
  100%  7602067          x=236 y=435 w=142 h=24
   88%  ALEQYLY<<EL      x=281 y=470 w=223 h=23
```

### 25.1 两个新事实

1. **原始帧很大**：日志里有 `x=2050 / 2190 / 2304` 的行，说明相机出的是 **2592×1944**（后置 4:3 的最小档，
   `CameraController.pickSize` 选不到 1280×960）。`recognize()` 又把最长边压到 1280，
   MRZ 每字符从 ~15px 变成 **~7px** —— DBNet 检出率骤降。
2. **右半段整块漏检**：`IDIRQAC67` 只覆盖 x=254~437，而整行 30 字符应该到 x≈700；
   y=388 这一带右半边**没有任何行**。所以行组重识别（上一节）拿到的还是左半段，`findMrz` 的 26 字符门槛照样过不去。

### 25.2 这次的修复

| 改动 | 原因 |
|---|---|
| `MAX_SIDE` 1280 → **1600** | 2592 压到 1600 时 MRZ 每字符 ~9px（比 7px 好），det 耗时约为原来的 1.6 倍 |
| `rereadSplitLines` 支持**单段 MRZ 左半段** | 以前只处理"被切成多段"的组；`IDIRQAC67` 是孤立一段，直接被跳过 |
| 命中左半段时**把框向右扩到 `20 × 行高`** | MRZ 是固定 30 字符的等宽行：24px 行高 → 整行约 450~480px，右扩 480px 正好覆盖整行，再整行重识别 |
| 条带兜底的高度改按**中位行高** | 固定 3.2% 图高在 1944px 上是 62px，跨了两行 MRZ，识别必然乱；改成中位行高×1.35 |

左半段判定（`mrzLeftFragment`）：长度 6~25，且以 `ID`/`I<`/`AC`/`A<`/`C<` 开头，或"前 6 位数字 + 第 8 位是 M/F/<"。
这样 `IDIRQAC67`、`7602067M2` 都会命中，而户号行（18 位、前 6 位含字母）和姓名行不会。

### 25.3 回归

```
auto: family=null mrzUsable=false doc=null (746ms)
card: family=1002E6996574380150 mrzUsable=true doc=26F279T24 (4666ms)
家庭编号=1002E6996574360150  MRZ=可用 …        ← 正常合成卡不退化
OCR UNIT TESTS PASSED (15) / NFC TESTS PASSED (49) / QR TESTS PASSED (46)
ALL JVM TESTS PASSED
```

### 25.4 仍未验证 / 下一步

- 右扩重识别**没在真机上跑过**。若仍失败，日志里会看到右扩后的行（`w` 明显变大）；
  如果 `w` 变大了但文本仍只有十来个字符，说明右半段的像素本身不行（反光/全息条压住了），
  那就要走"多帧融合 + 校验位反解"，而不是继续扩框。
- 2592×1944 的帧里，`MIN_CONF = 0.30` 会放进大量底纹碎块（第 1 帧 80 行、10 秒），
  下一版可以考虑按行高/字符数过滤一遍再 rec，把单帧耗时压回 5 秒内。

## 26. NFC 读卡失败：不是"没贴够 10 秒"，是"读 EF.CardAccess 把会话弄死了"

现场第二次读卡日志（`idcard-diag-20261110-113509`，HONOR BVL-N49 / Android 16）：

```
AID：A0000002471001
MRZi：A12464759896081883202274          ← MRZ 正确（doc 校验 8 / dob 8 / exp 4 都在）
卡片 ATS/历史字节：803198738401e0
结果：失败    APDU：2 条 · 349ms

步骤日志：
  选择 eMRTD 应用…        应用 A0000002471001 已选中     ← APDU 1
  读取 EF.CardAccess…     芯片不开放 EF.CardAccess（6A82/6982） ← APDU 2
  BAC 认证…               失败：TagLostException：Tag was lost.
```

### 26.1 为什么可以断定与"贴卡时长"无关

- 全程 **349ms**，2 条 APDU。人不可能 0.35 秒就把卡拿开；
- 而且"选中应用""选择文件"都成功返回了（有 R-APDU），说明**贴的位置是对的、NFC 耦合正常**；
- `TagLost` 出现在 `SELECT EF.CardAccess` 返回 **6982** 之后的下一条 APDU —— 也就是**芯片在拒绝这个文件后把会话复位了**。

上一次（Redmi）是同一个位置断的，只是当时是先读 CardAccess 再 BAC；两次都断在"6982 之后"，
说明这不是某一台手机的问题，而是**这张卡的行为**：不开放 EF.CardAccess，且被问过之后就不再应答。

### 26.2 修复：BAC 优先，PACE 只在 BAC 失败后再探测

`IcaoReader.read` 的顺序改成：

```
SELECT AID
  → BAC（GET CHALLENGE + EXTERNAL AUTHENTICATE）
      成功 → 直接读 DG
      失败（非 TagLost）→ 重选 AID → 读 EF.CardAccess → 有 PACE 参数就走 PACE
      失败（TagLost）→ 立刻上报，不去试 PACE（物理断开，试也没用）
```

以前是"为了拿 PACE 参数先读 CardAccess"，恰恰这一步把会话弄死。BAC 是 ICAO 9303-11 的老方案，
兼容面最广，先试它的成功率最高；只有 BAC 被拒（例如 `6300` MRZ 不对、`6A81` 不支持）才去碰 CardAccess。

### 26.3 顺带让现场能自己判断"贴够了没有"

- 读卡过程中顶部状态栏每秒刷新：**「读卡中… 已 N 秒 · 保持贴合不要移动（读人像通常 5~15 秒）」**；
- 读卡结束顶部显示 **「读卡失败 · 349ms · 2 条 APDU」**；
- 失败提示里直接写「本次只走了 349ms / 2 条 APDU 就断了——不是贴卡时间不够，是芯片侧会话被复位」。

### 26.4 单测（假芯片也跟着改了）

`VirtualCard(dir, usePace=true)` 以前同时支持 BAC 和 PACE，改成"**只支持 PACE 的卡对 BAC 返回 6A81**"，
这样测试 7 真正覆盖「BAC 先试 → 被拒 → 读 CardAccess → PACE 成功」这条回退链路：

```
NFC TESTS PASSED (49)
```

### 26.5 仍未验证 / 下次看什么

- 这张伊拉克卡**是否支持 BAC 还不知道**。装新包再读一次，看日志里 `BAC 认证…` 后面是：
  - `安全通道：BAC（3DES 安全报文）` → 通了，问题解决；
  - `BAC 外部认证失败：6300（MRZ 不正确…）` → MRZ 里某个字段（多半是有效期/出生）和芯片不一致，需要核对卡面；
  - `BAC 取挑战失败：6982/6A81（芯片拒绝 BAC…）` → 这张卡只支持 PACE，而且 `EF.CardAccess` 又被保护，
    那就要预置 PACE 参数（OID + 密码），属于新工作量。
- 荣耀这台机器的 NFC 天线位置与小米不同（日志里贴卡位置是对的，但读 DG2 的稳定性还要再测）。

## 没有验证的部分（不要当成已验证）

| 项 | 状态 | 原因 |
|---|---|---|
| 真机运行 | **已验证（v1.7）** | 安装/启动/建模/活体 3 动作/1:1 比对全部跑通，无异常 |
| 技术验证实验室页（v2.5 起，v2.7 加三页） | 部分已验证 | 2.6 真机跑过 7 项全通过；v2.7 新增的**动态码二维码页 / 花名册查看页**仍未上机，二维码也还没被第三方扫码器扫过 |
| 人脸登录（v2.7 系统弹窗） | **已验证不可用** | 真机实测弹的是指纹：`setAllowedAuthenticators(BIOMETRIC_WEAK)` 不能指定只要人脸（§19.1），已改成前置相机通道 |
| 前置相机人脸登录（v2.8） | **未验证** | 只做了构建与 JVM 回归；前置画面方向、低帧率动作捕捉、弱光 6 帧质量要上机确认（§19.4） |
| 系统认证通道（v2.8，指纹/人脸/密码） | **未验证** | 含密码兜底的弹窗形态、`authType` 取值、"只留人脸"是否真弹脸，只能上机确认（§19.6） |
| 相机帧方向/帧率 | **未验证** | 依赖具体机型；已提供手动旋转开关 + 所见即算法所见 |
| MediaPipe 关键点索引是否与预期一致 | **间接支持** | 用敏感度实验限制了最坏影响；有 5 点几何门控兜底 |
| 伊拉克人群精度 | **未标定** | 无数据；App 内置评测模式可现场标定 |
| 被动反欺诈（打印/屏幕翻拍） | **未实现** | 现有公开模型实测退化，已剔除 |
| 活体抗视频重放 | **未实现** | 需要经过攻击样本训练的模型 |
| 卡背 OCR（真实卡片） | **未验证** | 只有合成卡背图（Arial 近似印刷体），真实底纹/阿拉伯文/反光未测 |
| NFC 读真实证件 | **未验证** | 协议栈只在"假芯片"上闭环，真卡时序与兼容性只能上机 |
| 芯片人像 JPEG2000 | **未实现** | Android `BitmapFactory` 无 JP2 解码器，只显示字节数 |
| CSCA 主列表 / 证件真实性 | **未做** | 无各国根证书清单；只报"签名自洽、未锚定" |
| PACE-DH-GM / IM / CAM | **未实现** | 实现的是 PACE-ECDH-GM（AES/3DES）；其余组合会回退 BAC 或报错 |
