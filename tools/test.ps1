# JVM 单测：直接跑 App 里编译出的真实类（Align.similarity / Liveness / Stats）
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Push-Location $root
try {
  $env:JAVA_HOME = Join-Path $root 'sdk\jdk\jdk-17.0.21+9'
  $env:Path = "$env:JAVA_HOME\bin;$env:Path"
  $aj = 'sdk/platforms/android-35/android.jar'
  $cp = 'build/classes;' + $aj

  if (-not (Test-Path 'build\classes\iq\digitalration\faceverify\Align.class')) {
    throw 'build/classes 不存在，请先运行 tools\build.ps1'
  }
  New-Item -ItemType Directory -Force -Path build\jvmtest, build\stats | Out-Null
  # 每次清空 jvmtest：残留的旧 class 会盖住 build\classes（踩过两次：Liveness 的 tasks、
  # OcrCore 的 recognizeCard —— 都表现为"NoSuchMethodError"，看着像代码没编译进去）
  Remove-Item 'build\jvmtest\*' -Recurse -Force -ErrorAction SilentlyContinue

  Write-Host '=== 编译测试 ==='
  & "$env:JAVA_HOME\bin\javac.exe" -nowarn -encoding UTF-8 -cp $cp -d build\jvmtest tools\validate\JvmTest.java
  if ($LASTEXITCODE -ne 0) { throw 'javac JvmTest failed' }
  & "$env:JAVA_HOME\bin\javac.exe" -nowarn -encoding UTF-8 -d build\stats app\java\iq\digitalration\faceverify\Stats.java
  if ($LASTEXITCODE -ne 0) { throw 'javac Stats failed' }

  Write-Host ''
  $out = & "$env:JAVA_HOME\bin\java.exe" "-Dfile.encoding=UTF-8" "-Dstdout.encoding=UTF-8" -cp "build\jvmtest;$cp" JvmTest 2>&1
  $out | ForEach-Object { $_ }

  Write-Host ''
  $out2 = & "$env:JAVA_HOME\bin\java.exe" "-Dfile.encoding=UTF-8" "-Dstdout.encoding=UTF-8" -cp build\stats iq.digitalration.faceverify.Stats 2>&1
  $out2 | ForEach-Object { $_ }

  Write-Host ''
  Write-Host '=== 证件核验：卡背 OCR（PP-OCR + 阿拉伯文标签，需 Windows 版 onnxruntime）==='
  # onnxruntime 的 Java API jar 只含接口，Windows 原生库在 dl/maven/.../onnxruntime-1.20.0.jar（88MB，仅测试用，不打进 APK）
  $ortJar = 'dl/maven/com/microsoft/onnxruntime/onnxruntime/1.20.0/onnxruntime-1.20.0.jar'
  $ocrOut = ''
  if (Test-Path $ortJar) {
    # 不依赖模型的后处理单测：标签匹配 / 位置纠错 / 校验位反解 / 投票
    & "$env:JAVA_HOME\bin\javac.exe" -nowarn -encoding UTF-8 -cp "build\classes;$ortJar" -d build\jvmtest tools\validate\OcrUnitTest.java
    if ($LASTEXITCODE -ne 0) { throw 'javac OcrUnitTest failed' }
    $unitOut = (& "$env:JAVA_HOME\bin\java.exe" "-Dfile.encoding=UTF-8" "-Dstdout.encoding=UTF-8" -cp "build\jvmtest;build\classes;$ortJar" iq.digitalration.faceverify.ocr.OcrUnitTest 2>&1) -join "`n"
    ($unitOut -split "`n" | Where-Object { $_ -match 'FAIL|PASSED|FAILED' }) | ForEach-Object { $_ }
    $ocrOut = $ocrOut + "`n" + $unitOut

    & "$env:JAVA_HOME\bin\javac.exe" -nowarn -encoding UTF-8 -cp "build\classes;$ortJar" -d build\jvmtest tools\validate\OcrTest.java
    if ($LASTEXITCODE -ne 0) { throw 'javac OcrTest failed' }
    # 三张样本：干净印刷 / 竖着拍 / 缩小+模糊+噪声（近似手机拍摄）
    foreach ($img in @('card_back.png', 'card_back_rot90.png', 'card_back_hard.png')) {
      Write-Host ("--- " + $img)
      $one = (& "$env:JAVA_HOME\bin\java.exe" "-Dfile.encoding=UTF-8" "-Dstdout.encoding=UTF-8" -cp "build\jvmtest;build\classes;$ortJar" OcrTest ("tools\fixtures\" + $img) 2>&1) -join "`n"
      $ocrOut = $ocrOut + "`n" + $img + "`n" + $one
      ($one -split "`n" | Select-String -Pattern '行数|家庭编号|标签|MRZ 校验|家庭编号=|auto:|card:') | ForEach-Object { "  " + $_ }
    }
  } else {
    Write-Host '  [skip] 未找到 Windows 版 onnxruntime jar（OCR 只能在手机上验证）'
  }

  Write-Host ''
  Write-Host '=== 证件核验：NFC / ICAO 9303（协议栈 + 假芯片）==='
  & "$env:JAVA_HOME\bin\javac.exe" -nowarn -encoding UTF-8 -cp "build\classes;$aj" -d build\jvmtest tools\validate\NfcTest.java
  if ($LASTEXITCODE -ne 0) { throw 'javac NfcTest failed' }
  $nfcOut = (& "$env:JAVA_HOME\bin\java.exe" "-Dfile.encoding=UTF-8" "-Dstdout.encoding=UTF-8" -cp "build\jvmtest;build\classes;$aj" NfcTest tools\fixtures\nfc-sample 2>&1) -join "`n"
  ($nfcOut -split "`n" | Where-Object { $_ -match 'FAIL|PASSED|FAILED' }) | ForEach-Object { $_ }

  Write-Host ''
  Write-Host '=== 离线二维码（自研编码器 + 独立解码校验）==='
  & "$env:JAVA_HOME\bin\javac.exe" -nowarn -encoding UTF-8 -cp $cp -d build\jvmtest tools\validate\QrTest.java
  if ($LASTEXITCODE -ne 0) { throw 'javac QrTest failed' }
  $qrOut = (& "$env:JAVA_HOME\bin\java.exe" "-Dfile.encoding=UTF-8" "-Dstdout.encoding=UTF-8" -cp "build\jvmtest;$cp" QrTest 2>&1) -join "`n"
  ($qrOut -split "`n" | Where-Object { $_ -match 'FAIL|PASSED|FAILED' }) | ForEach-Object { $_ }

  Write-Host ''
  Write-Host '=== 类级缺失扫描 ==='
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $scan = 'build\scan_verify'
  if (Test-Path $scan) { Remove-Item $scan -Recurse -Force }
  New-Item -ItemType Directory -Force -Path $scan | Out-Null
  $i = 0
  foreach ($j in (Get-ChildItem build\deps -Filter *.jar)) {
    $i++; $d = Join-Path $scan ("j" + $i + "_" + $j.BaseName)
    New-Item -ItemType Directory -Force -Path $d | Out-Null
    [System.IO.Compression.ZipFile]::ExtractToDirectory($j.FullName, $d)
  }
  $own = Join-Path $scan 'j0_own'
  New-Item -ItemType Directory -Force -Path $own | Out-Null
  Copy-Item build\classes\* $own -Recurse -Force
  $depOut = node tools/depscan2.mjs $scan 2>&1
  ($depOut | Select-Object -Last 3) | ForEach-Object { $_ }

  Write-Host ''
  Write-Host '=== 全类加载测试 ==='
  $jars = (Get-ChildItem build\deps -Filter *.jar | ForEach-Object { $_.FullName }) -join ';'
  $fullCp = "build\jvmtest;build\classes;$jars;$aj"
  & "$env:JAVA_HOME\bin\javac.exe" -nowarn -encoding UTF-8 -cp $fullCp -d build\jvmtest tools\validate\LoadAllTest.java
  $loadOut = & "$env:JAVA_HOME\bin\java.exe" "-Dfile.encoding=UTF-8" -cp $fullCp LoadAllTest $scan 2>&1
  ($loadOut | Select-Object -Last 2) | ForEach-Object { $_ }

  Write-Host ''
  Write-Host '=== 交付 dex 覆盖检查（dexdump 权威类列表）==='
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  # 检查"最近构建的那个包"：build.ps1 默认出 R50，-Model mbf 出 Lite；两个都是交付物。
  # 写死 offline 会在只构建 lite 时误报（classes 是新的、APK 是旧的）。
  $apk = (Get-ChildItem 'out\*.apk' -ErrorAction SilentlyContinue |
      Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
  if ($apk) { Write-Host ("checking " + $apk) }
  if ($apk -and (Test-Path $apk)) {
    $z = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path $apk))
    $e = $z.GetEntry('classes.dex')
    [System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, "$PWD\build\shipped.dex", $true)
    $z.Dispose()
    $dump = & "$PSScriptRoot\..\sdk\build-tools\android-15\dexdump.exe" build\shipped.dex 2>&1
    $dexSet = New-Object 'System.Collections.Generic.HashSet[string]'
    $dump | Select-String -Pattern "Class descriptor\s+:\s+'L(.+);'" | ForEach-Object { [void]$dexSet.Add($_.Matches[0].Groups[1].Value) }
    $expected = New-Object 'System.Collections.Generic.HashSet[string]'
    $base = (Resolve-Path 'build\scan_verify').Path
    Get-ChildItem build\scan_verify -Recurse -Filter *.class | ForEach-Object {
      $rel = ($_.FullName.Substring($base.Length + 1) -replace '\\', '/') -replace '\.class$', ''
      [void]$expected.Add($rel.Substring($rel.IndexOf('/') + 1))
    }
    $missing = @($expected | Where-Object { -not $dexSet.Contains($_) })
    Write-Host ("dex 类 $($dexSet.Count) 个 / 应包含 $($expected.Count) 个 / 缺失 $($missing.Count) 个")
    $missing | Select-Object -First 10 | ForEach-Object { Write-Host ("  MISSING " + $_) }
    $dexMissing = $missing.Count
  } else {
    Write-Host 'APK 不存在，跳过'
    $dexMissing = 0
  }

  # 断言：关键结论必须出现
  $ok = ($out -join "`n")
  $fail = @()
  if ($ok -notmatch '通过（误差 < 0.001px）') { $fail += 'Align.similarity' }
  if ($ok -notmatch 'finished=true passed=true done=3/3') { $fail += 'Liveness 正常通过' }
  if ($ok -notmatch '\(b\) 静止 60s：finished=false passed=false done=0') { $fail += 'Liveness 抗静态照片' }
  if ($ok -notmatch 'turnPeak=0\.00 maxYaw=0\.90') { $fail += 'Liveness 峰值口径' }
  if (($depOut -join "`n") -notmatch '被引用但不存在的类 ===\s*\n\s*无') { $fail += '类级缺失扫描' }
  if (($loadOut -join "`n") -notmatch 'ALL CLASSES LOADABLE') { $fail += '全类加载' }
  if ($dexMissing -ne 0) { $fail += '交付 dex 覆盖' }
  # 断言只用 ASCII 片段：PowerShell 5.1 读脚本/进程输出时的编码不可靠，中文匹配容易假失败
  if ($ocrOut -ne '') {
    # 三张样本都要认对户号（每张至少出现 2 次：行文本 + 汇总行）
    if (([regex]::Matches($ocrOut, '1002E6996574360150')).Count -lt 6) { $fail += '卡背 OCR 家庭编号' }
    if ($ocrOut -notmatch 'doc=true dob=true exp=true') { $fail += '卡背 OCR MRZ 校验位' }
  # 小卡片：卡宽 300px 时整幅识别会漏掉 MRZ，卡背专用补扫要能救回来
  if ($ocrOut -notmatch 'auto: family=null mrzUsable=false') { $fail += '小卡片漏检复现' }
  if ($ocrOut -notmatch 'card: family=\S* mrzUsable=true') { $fail += '小卡片 MRZ 补扫' }
    if ($ocrOut -notmatch 'OCR UNIT TESTS PASSED') { $fail += 'OCR 后处理单测' }
  }
  if ($nfcOut -notmatch 'NFC TESTS PASSED') { $fail += 'NFC/ICAO 协议栈' }
  if ($qrOut -notmatch 'QR TESTS PASSED') { $fail += '离线二维码' }
  Write-Host ''
  if ($fail.Count -eq 0) {
    Write-Host 'ALL JVM TESTS PASSED'
  } else {
    Write-Host ('FAILED: ' + ($fail -join ', '))
    exit 1
  }
} finally { Pop-Location }
