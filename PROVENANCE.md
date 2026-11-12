# 来源与独立性说明（PROVENANCE）

> 本文件回答一个问题：**本仓库里 ICAO 9303 读卡相关的新增代码从哪来、有没有复制第三方实现。**
>
> 结论先行：**全部新增代码均为独立编写**，依据是公开发布的国际标准，以及本仓库自身已有的抽象
> （`CardSession` / `Tlv` / `Apdu`）。**未复制任何第三方的源码、二进制、资源或字符串表**，
> 也没有引入任何新的运行时依赖。

## 1. 覆盖范围

| 提交 | 内容 |
|---|---|
| `fb41f3a` | 主动认证（AA）、CSCA 信任锚、界面/JSON 展示、NfcTest 断言、README |
| 其后一次提交 | 注释中性化 + 本文件 |

涉及的文件（`git show fb41f3a --stat` 可复核）：

| 文件 | 类型 | 行数 | 说明 |
|---|---|---|---|
| `app/java/.../card/ActiveAuth.java` | 新增 | 180 | DG15 公钥解析 + INTERNAL AUTHENTICATE + 验签 |
| `app/java/.../card/Csca.java` | 新增 | 150 | 受信 CSCA 库 + 比对（指纹/Subject+序列号） |
| `app/java/.../card/IcaoReader.java` | 修改 | +16 | EF.COM 声明 DG15 时读取并调用 AA |
| `app/java/.../card/PassiveAuth.java` | 修改 | +28 | 输出 `cscaTrusted` / 国别 / 库容量 |
| `app/java/.../IdCardActivity.java` | 修改 | +32 | 界面两行结论 + 导出 JSON 字段 |
| `app/java/.../App.java` | 修改 | +23 | 启动时从 `assets/csca/*.pem` 装入证书 |
| `tools/validate/NfcTest.java` | 修改 | +77 | 15 项新断言（总数 91 → 106） |
| `README.md` | 修改 | +17 | 能力说明与限制 |

## 2. 依据的公开标准（条款级）

| 实现点 | 依据 |
|---|---|
| `INTERNAL AUTHENTICATE` = `00 88 00 00 08 <challenge> 00` | ISO/IEC 7816-4（命令与数据对象） |
| 状态字 `6982`（安全状态不满足）、`6A81`/`6A88`（不支持/未找到） | ISO/IEC 7816-4 |
| DG15 结构 `6F { 30 SubjectPublicKeyInfo }` | ICAO Doc 9303 Part 10（LDS） |
| 主动认证：8 字节随机挑战 + 芯片私钥签名，在安全报文建立之后执行 | ICAO Doc 9303 Part 11（安全机制 · 主动认证） |
| 签名算法族：RSA（SHA-1/256/384/512）、ECDSA | PKCS#1 / ANSI X9.62，JCA 标准名称 |
| 被动认证：SOD 的 CMS 签名 + 数据组哈希 | ICAO Doc 9303 Part 11（被动认证）（仓库原有实现，本次未改其算法） |
| CSCA/DS 信任模型、MasterList 语义 | ICAO Doc 9303 Part 12（MRTD 公钥基础设施） |
| BAC 的 3DES 安全报文与 MAC | ICAO Doc 9303 Part 11 §9.8 + ISO/IEC 9797-1（仓库原有实现） |

> 说明：上述均为公开标准文本描述的**流程与数据格式**，不涉及任何具体实现代码。

## 3. 实现过程（独立性证据）

1. **能力层面对照，而非代码取用**：对照对象是一份第三方商用读卡应用（下称"参考实现"），
   仅用于确认"这类证件核验还应具备哪些能力"（主动认证、CSCA 信任锚），据此回到公开标准去实现。
2. **参考实现没有可复制的 Java 实现**：其主动认证与 CSCA 逻辑位于原生库中，公开 API 层
   （反编译可见的 Java 类）对 `INTERNAL AUTHENTICATE` / `SubjectPublicKeyInfo` / `0x88`
   的命中次数均为 **0**；`CSCA` 的命中仅是"把证书库路径传给原生层"的调用。
   因此本仓库的 Java 实现不存在可逐行对照的第三方源码。
3. **编写顺序**：先按标准写协议状态机，再套用本仓库既有抽象
   （`CardSession.send()` 负责 APDU/安全报文、`Tlv` 负责 DER 解析、`Apdu.Resp` 负责状态字）。
   新增文件没有自造传输层，也没有引入新的密码学库。
4. **验证方式为自证，不依赖参考实现的任何样本**：
   - AA 的验签用**本机临时生成**的 RSA 密钥自测（签名、篡改签名、替换挑战三种情形）；
   - CSCA 锚定用**本仓库自带**的测试样本（`tools/fixtures/nfc-sample/sod.bin`，由
     `tools/nfc-proto/gen_nfc_sample.py` 生成的自签 CSCA/DS 链）验证"空库→未收录、
     装入→已锚定、清空→回到未收录"；
   - 全量协议测试：`NfcTest` **106 项全部通过**（改动前 91 项）。

## 4. 未引入的组件（负向清单）

- 未引入参考实现的任何 **class / .so / assets / res / 字符串表 / 常量表**；
- 未引入 **JMRTD、SCUBA、BouncyCastle、OpenSSL** 等第三方 ICAO/密码学实现；
- 未改变 APK 的依赖集合：仍为 `com.microsoft.onnxruntime` + `com.google.mediapipe`
  （`tools/build.ps1` 构建日志可复核：原生库 `libonnxruntime.so`、
  `libonnxruntime4j_jni.so`、`libmediapipe_tasks_vision_jni.so` 共 3 个，与改动前一致）；
- 新增两个文件的 `import` 仅来自 JDK 标准库（`java.security.*`、`javax.security.auth.x500.*`），
  可用下面第 6 节的命令复核。

## 5. 第三方资源（模型，均在本次改动之前已存在）

| 资源 | 来源 | 说明 |
|---|---|---|
| `models/w600k_*.onnx` | insightface（ArcFace） | 人脸特征，非本次引入 |
| `models/ppocr_*.onnx`、`ppocr_keys_*.txt` | HuggingFace `monkt/paddleocr-onnx`、`SWHL/RapidOCR`（det） | 卡背 OCR，非本次引入 |
| `models/face_landmarker.task` | Google MediaPipe | 活体检测，非本次引入 |

## 6. 可复现的核查方式

```powershell
# 1) 看这次到底改了哪些文件、多少行
git show fb41f3a --stat

# 2) 新增文件的依赖只在 JDK 内
Select-String -Path app\java\iq\digitalration\faceverify\card\ActiveAuth.java,app\java\iq\digitalration\faceverify\card\Csca.java -Pattern '^import'

# 3) 全仓库不再包含任何参考实现的包名/类名引用
Select-String -Path app\java\**\*.java,README.md -Pattern 'com\.nja|com\.bjnja|NjaChip|ChipClient'

# 4) 协议层的回归测试（不需要手机，也不需要模型）
#    需要 JDK 17 + 本仓库的 card 包（纯 Java）
```

> 分析阶段使用的反编译产物位于**仓库之外**，未提交、未随 APK 分发。

## 7. 声明

- 本文件是工程侧的来源说明，**不构成法律意见**；如需对外交付或应对权属审查，
  请交由知识产权顾问复核，本文件与 git 历史可作为核对材料。
- 若日后引入任何第三方代码或数据（含 CSCA 证书包、字体、图标、模型），
  应在此文件追加条目并注明许可证。