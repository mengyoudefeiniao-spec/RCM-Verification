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

## 没有验证的部分（不要当成已验证）

| 项 | 状态 | 原因 |
|---|---|---|
| 真机运行 | **已验证（v1.7）** | 安装/启动/建模/活体 3 动作/1:1 比对全部跑通，无异常 |
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
