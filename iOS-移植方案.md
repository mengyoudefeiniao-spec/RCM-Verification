# iOS 版移植方案（现有 Android 技术验证逻辑 → iOS）

> 结论先说：**在这台 Windows 机器上无法产出可安装的 iOS 包**。这不是工程难度问题，是签名链问题：
> iOS 应用必须由 **macOS + Xcode** 编译，并由 Apple 证书签名（IPA 里的 `_CodeSignature` + provisioning profile
> 由 Apple 服务器签发）。APK 那种「aapt2→javac→d8→apksigner 手工打包」在 iOS 上没有对应物。
>
> 本文给出可落地的移植路径、组件对照、最小验证集（POC）范围与在 Mac 上的出包步骤。
> 下面的代码/SDK 名称按 2026 年可用版本写，实际以 Xcode 与依赖仓库当前版本为准。

---

## 1. 为什么不能「换个壳」

现有 App 是**纯 Java + Android 专有 API**，不是跨平台框架（Flutter/RN/Unity 都不是）：

| 现有实现 | 依赖的 Android 专有能力 |
|---|---|
| 相机帧 | `Camera2`（`CameraDevice` / `ImageReader` / `YUV_420_888`） |
| 人脸网格 / 活体 | `MediaPipe Tasks Vision` 的 **Android AAR**（`libmediapipe_tasks_vision_jni.so`） |
| 人脸特征 | `ONNX Runtime Android` AAR（`libonnxruntime.so`） |
| 密钥 / 签名 | `AndroidKeyStore`（TEE 证明链、`setUserAuthenticationRequired`） |
| 生物识别 | `android.hardware.biometrics.BiometricPrompt` |
| 读证件芯片 | Android `NfcAdapter.enableReaderMode`（ISO-DEP APDU） |
| 加密落盘 | `AES/GCM` + `AndroidKeyStore` 密钥 |
| 打包 | 手写 `build.ps1`（aapt2 / d8 / zipalign / apksigner） |

iOS 没有 JVM、没有这些 API。**必须用 Swift 重写**（模型文件 `.onnx` / `.task` 可以原样复用）。

---

## 2. 组件对照表（Android → iOS）

| 能力 | Android（现状） | iOS（对应实现） | 备注 |
|---|---|---|---|
| 前置相机取帧 | Camera2 + `ImageReader` | `AVCaptureSession` + `AVCaptureVideoDataOutput`（`kCVPixelFormatType_420YpCbCr8BiPlanarFullRange`） | 帧方向用 `videoRotationAngle`；前置默认镜像，需与 Android 对齐 |
| 人脸 478 点 + blendshape | MediaPipe Tasks Vision (AAR) | `MediaPipeTasksVision`（CocoaPods / SPM），`FaceLandmarker` | 模型 `face_landmarker.task` 可直接复用 |
| 人脸特征（ArcFace 512 维） | ONNX Runtime Android | `onnxruntime-objc`（CocoaPods）或 `onnxruntime-c` | `arcface.onnx` 可直接复用；预处理/对齐需 Swift 重写 |
| 5 点对齐 112×112 | `Align.java`（Umeyama + 仿射） | Swift + Accelerate（`vImage`/`simd`）或纯 Swift | 数学部分可逐行翻译，`tools/validate` 里的期望值可做对照测试 |
| 活体挑战 | `Liveness.java`（随机 3 动作） | 逻辑不变，Swift 重写；动作判定阈值保持不变 | 纯逻辑，最适合先移植 |
| 密钥 / 签名 | `AndroidKeyStore`（EC P-256 + attestation） | **Secure Enclave**：`SecKeyCreateRandomKey`（`kSecAttrTokenIDSecureEnclave`）+ `SecKeyCreateSignature` | iOS 无「密钥 attestation 证书链」公开等价物，改报"Secure Enclave 可用性 + 生物绑定成功" |
| 生物识别登录 | `BiometricPrompt`（strong/weak） | `LocalAuthentication`：`LAContext.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics)` | **iOS 的 Face ID 属于强档**，可用 `SecAccessControl(.biometryCurrentSet)` 绑定密钥，签名只在刚通过 Face ID 后可用 |
| 人脸登录（前置相机） | `FaceLoginActivity` | 同逻辑：AVFoundation 前置 + 活体 + ArcFace 比对 + Secure Enclave 签名 | iOS 上这条路仍需要，用于"证明是这张脸" |
| 读证件芯片 | `NfcAdapter` reader mode + ISO-DEP | `CoreNFC`：`NFCTagReaderSession`（`.iso7816`）+ `NFCISO7816APDU` | 需要付费开发者账号 + NFC entitlement（列表见下） |
| 加密落盘 | `AndroidKeyStore` + `AES/GCM` | `CryptoKit.AES.GCM` + Keychain 存密钥（`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`） | 对齐 `.bak`/`.tmp` 回退逻辑 |
| 参数存储 | `SharedPreferences` | `UserDefaults`（非敏感）/ Keychain（密钥） | |
| 文件目录 | `filesDir` / `getExternalFilesDir` | `Application Support` / `Documents` | 导出走 `UIFileSharingEnabled` |
| 分享报告 ZIP | 自建 `ContentProvider` | `UIActivityViewController` + `FileManager` 打 ZIP（`NSFileCoordinator` 或 ZIPFoundation） | 不需要 provider |
| 二维码 | 自研 `QrCode.java` | `CoreImage`：`CIFilter.qrCodeGenerator` | iOS 上不必再手写编码器 |
| 卡背 OCR | PP-OCR ONNX（det+rec+ar） | 同一套 ONNX + ORT iOS；检测也可先用 `Vision`（`VNRecognizeTextRequest`） | 阿拉伯文识别 Vision 支持有限，优先复用原模型 |
| 报告 MD/JSON | `LabReport.java` | Swift 重写（结构、字段名、判据表保持一致） | 报告 schema `rcm-lab@1` 不变，双端可对账 |

### iOS 需要写进 `Info.plist` 的用途说明

- `NSCameraUsageDescription`（相机）
- `NSFaceIDUsageDescription`（Face ID）
- `NFCReaderUsageDescription`（读证件芯片，若做）
- `UIFileSharingEnabled` + `LSSupportsOpeningDocumentsInPlace`（导出报告）

### iOS 需要的能力/entitlement

| entitlement | 用途 | 获取难度 |
|---|---|---|
| `com.apple.developer.nfc.readersession.formats` | CoreNFC 读 ISO7816 证件 | **需要付费开发者账号**，且要在 App ID 上勾选 NFC，Apple 侧可能要求说明用途（1–2 周） |
| Keychain Sharing（可选） | 多 target 共享受限密钥 | 免费账号可配 |
| `com.apple.developer.associated-domains`（本方案不用） | — | — |

---

## 3. 最小验证集（POC）建议：先做 3 件事

完整对齐现有 10 项 TV 自测工作量约 **8–12 人日**（含真机调试）。建议先做只覆盖"最想证明的三件事"的 POC，**2–3 人日**：

1. **TV-05 系统生物认证凭据（iOS 强项）**
   Face ID / Touch ID 经 `LAContext` 通过后，用绑定 `.biometryCurrentSet` 的 Secure Enclave 私钥签登录令牌；
   验证"未认证时签不出名"（`SecKeyCreateSignature` 返回 `errSecAuthFailed`）。
   → 这条在 iOS 上比 Android 更干净：Face ID 本身就是系统级强模态，不存在"只能指纹"的问题。
2. **前置相机人脸登录（对应 `FaceLoginActivity`）**
   前置取帧 → MediaPipe FaceLandmarker → 随机 3 动作 → 6 帧对齐 → ArcFace 比对 → 阈值判定。
3. **报告导出**
   把上面两项结果按 `rcm-lab@1` 写成 `report.md` / `report.json` / `device.json`，用 `UIActivityViewController` 分享。

剩下的（动态码/花名册/离线队列/回执/OCR/NFC）在 POC 通过后按同一套 `LabReport` 判据逐项补。

---

## 4. 在 Mac 上出包的实际步骤

> 前置：macOS 13+、Xcode 15+、Apple ID（免费或付费 99 USD/年）。

```bash
# 1) 生成工程（示例：SwiftUI App + CocoaPods 依赖）
#    Podfile
#    platform :ios, '15.0'
#    target 'FaceVerifyLab' do
#      use_frameworks!
#      pod 'MediaPipeTasksVision'
#      pod 'onnxruntime-objc'
#    end
pod install

# 2) 命令行构建（Debug 真机）
xcodebuild -workspace FaceVerifyLab.xcworkspace -scheme FaceVerifyLab \
  -configuration Debug -destination 'generic/platform=iOS' \
  -derivedDataPath build ARCHIVE=NO build

# 3) 打 Release 归档 + 导出 IPA（需要已配置签名）
xcodebuild -workspace FaceVerifyLab.xcworkspace -scheme FaceVerifyLab \
  -configuration Release -destination 'generic/platform=iOS' \
  -archivePath build/FaceVerifyLab.xcarchive archive
xcodebuild -exportArchive -archivePath build/FaceVerifyLab.xcarchive \
  -exportPath build/ipa -exportOptionsPlist ExportOptions.plist
```

签名方式（`ExportOptions.plist` 的 `method`）：

| 方式 | 适用 | 限制 |
|---|---|---|
| `development`（真机调试） | 少量测试机 | **免费账号 7 天过期**、最多 3 台设备、需要每 7 天重签 |
| `ad-hoc` | 指定 UDID 的测试机 | 需收集设备 UDID，100 台/年 |
| `app-store`（TestFlight） | 远程分发 | **需付费账号**，构建上传 App Store Connect，审核（TestFlight 内部测试通常较快） |
| `enterprise` | 企业内部分发 | 需企业账号（299 USD/年），Apple 审核严格 |

> 现实建议：如果只是拿几台 iPhone 做技术验证，用**免费 Apple ID + Xcode 直接 Run 到设备**最省事（7 天有效，到期重装）。
> 要给甲方远程装，必须付费账号 + TestFlight。

---

## 5. 与 Android 版的对应关系（报告字段不变）

同一份判据表继续沿用，双端结果可并排对账：

| TV | Android | iOS | 字段是否一致 |
|---|---|---|---|
| TV-21/30 设备环境 | `DeviceInfo.collect` | `UIDevice` + `sysctl`（机型/ABI 无意义，改报芯片型号 `hw.machine`）+ `AVCaptureDevice` | 字段名一致，值语义按平台调整 |
| TV-04 端侧可信性 | Root/模拟器 + Keystore 等级 | 越狱迹象（`/Applications/Cydia.app` 等）+ Secure Enclave 可用性 + 签名 teamID | `deviceKey.hardwareAttested` 在 iOS 上改报 `secureEnclave=true` |
| TV-11 回执防篡改 | ECDSA + 单调计数器 | 同逻辑，`SecKeyCreateSignature` + `UserDefaults` 计数器 | 完全一致 |
| TV-05 生物识别 | 强/弱两条 + 前置人脸 | Face ID 强档 + 前置人脸 | iOS 没有"弱模态"这档，`weakAuth` 置空并在报告说明 |
| TV-08 动态码 | HMAC + 时间窗 | CryptoKit `HMAC<SHA256>` + 同一窗口算法 | 完全一致 |
| TV-09 花名册 | AES-GCM 落盘 + `.bak` | CryptoKit + 同一回退策略 | 完全一致 |
| TV-10 离线队列 | 幂等键 + 冲突差异单 | 同逻辑 | 完全一致 |
| TV-03 人脸比对 | MediaPipe + ArcFace | 同模型、同阈值（mbf 0.58） | **相似度可直接对比** |
| TV-01 卡背 OCR | PP-OCR ONNX | 同模型 + ORT iOS | 需重写预处理 |
| TV-02/29 NFC | ISO-DEP + PACE/BAC | CoreNFC + 同一套 APDU/密码学（Swift 重写或 C 移植） | 工作量最大的一块 |

---

## 6. 尚未解决 / 需要你确认的事

1. **没有 Mac**：Windows 上只能写源码，不能编译、不能签名、不能出 IPA。需要一台 Mac（或云 Mac）。
2. **没有 Apple 开发者账号**：免费账号只能本机 7 天；要远程分发必须付费账号。
3. **NFC  entitlement 审批**：读护照/证件芯片属于受限能力，需在 App ID 上申请，时间不可控。
4. **iOS 版人脸模型精度未标定**：同一 `.onnx` + 不同预处理（像素格式、镜像、色彩矩阵）会影响相似度分布，
   必须在 iPhone 上用 App 内的评测模式重新跑一遍 FAR/FRR，**不能直接沿用 Android 的 0.58**。
5. **CoreNFC 的 APDU 时序 / 分块**需要真机 + 真卡重测，Android 的假芯片单测只能证明密码学逻辑。

---

## 7. 如果现在就要推进，按这个顺序

1. 你在 Mac 上装 Xcode，创建一个空 SwiftUI 工程，确认能 Run 到自己 iPhone（这一步能验证签名链通不通）。
2. 我把 POC 的 Swift 源码（相机 + MediaPipe + ORT + 活体 + 比对 + Face ID 令牌 + 报告导出）按模块写好，
   你拷到 Mac 上 `pod install` → Run。
3. POC 通过后再逐项补 TV-08/09/10（纯逻辑，最快），最后做 OCR/NFC（最慢）。
