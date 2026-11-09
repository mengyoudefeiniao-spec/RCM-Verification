# Offline Android build (no Gradle): aapt2 -> javac -> d8 -> zipalign -> apksigner
# 用法:
#   pwsh -File tools\build.ps1                        # 默认：标准版 R50 + arm64
#   pwsh -File tools\build.ps1 -Model mbf             # 精简版 MobileFaceNet
# 不同模型打成「不同的应用」（包名 + 桌面名称都不同），可同时安装、互不覆盖：
#   r50 -> iq.digitalration.faceverify        "离线人脸核验 R50"
#   mbf -> iq.digitalration.faceverify.lite   "离线人脸核验 Lite"
# 注意：aapt2 / zipalign 是原生程序，遇到绝对中文路径会失败，因此全部用「相对路径 + cwd=$root」。
param(
  [ValidateSet('r50', 'mbf')] [string]$Model = 'r50',
  [ValidateSet('arm64', 'both')] [string]$Abi = 'arm64',
  [string]$Out = '',            # 留空 = 按模型自动命名（offline / lite）
  [int]$VersionCode = 11,
  [string]$VersionName = '',    # 留空 = 按模型自动生成
  [string]$PackageSuffix = '',  # 留空 = 按模型自动取：r50 无后缀（沿用原包名，可覆盖升级）；mbf = .lite
  [string]$AppLabel = ''        # 留空 = 按模型自动取桌面显示名
)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Push-Location $root
try {

$env:JAVA_HOME = Join-Path $root 'sdk\jdk\jdk-17.0.21+9'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

$jdk = 'sdk\jdk\jdk-17.0.21+9'
$bt  = 'sdk\build-tools\android-15'
$aj  = 'sdk/platforms/android-35/android.jar'
$mvn = Join-Path $root 'dl\maven'

$javac     = Join-Path $root "$jdk\bin\javac.exe"
$keytool   = Join-Path $root "$jdk\bin\keytool.exe"
$aapt2     = Join-Path $root "$bt\aapt2.exe"
$d8        = Join-Path $root "$bt\d8.bat"
$zipalign  = Join-Path $root "$bt\zipalign.exe"
$apksigner = Join-Path $root "$bt\apksigner.bat"

$deps    = Join-Path $root 'build\deps'
$classes = Join-Path $root 'build\classes'
$gen     = Join-Path $root 'build\gen'
$dex     = Join-Path $root 'build\dex'
$jni     = Join-Path $root 'build\jni'
$assets  = Join-Path $root 'app\assets'

foreach ($d in @('build','build\deps','build\classes','build\gen','build\dex','out','build\jni')) {
  New-Item -ItemType Directory -Force -Path (Join-Path $root $d) | Out-Null
}
Remove-Item (Join-Path $root 'build\classes\*') -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item (Join-Path $root 'build\gen\*') -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item (Join-Path $root 'build\dex\*') -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item (Join-Path $root 'build\jni\*') -Recurse -Force -ErrorAction SilentlyContinue

# ---------- 模型选择 ----------
$basePkg = 'iq.digitalration.faceverify'
$modelSrc = if ($Model -eq 'mbf') { 'models\w600k_mbf.onnx' } else { 'models\w600k_r50.onnx' }
$modelId  = if ($Model -eq 'mbf') { 'arcface-mbf' } else { 'arcface-r50' }
$modelDesc = if ($Model -eq 'mbf') { 'ArcFace MobileFaceNet (WebFace600K, 13MB)' } else { 'ArcFace R50 (Glint360K, 166MB)' }
$modelThr = if ($Model -eq 'mbf') { '0.58' } else { '0.45' }

# ---------- 每个模型 = 一个独立应用 ----------
# 包名不同 -> 手机上可以同时装两个，后装的不再覆盖先装的；桌面名称也不同，便于区分。
# 说明：app 的数据目录按包名隔离，两个版本各自保存自己的参照照片/阈值，互不影响。
if ([string]::IsNullOrWhiteSpace($PackageSuffix)) { $PackageSuffix = if ($Model -eq 'mbf') { '.lite' } else { '' } }
if ($PackageSuffix -and -not $PackageSuffix.StartsWith('.')) { $PackageSuffix = '.' + $PackageSuffix }
$appId = $basePkg + $PackageSuffix

if ([string]::IsNullOrWhiteSpace($AppLabel)) { $AppLabel = if ($Model -eq 'mbf') { '离线人脸核验 Lite' } else { '离线人脸核验 R50' } }
if ([string]::IsNullOrWhiteSpace($Out)) { $Out = if ($Model -eq 'mbf') { 'FaceVerify-lite.apk' } else { 'FaceVerify-offline.apk' } }
if ([string]::IsNullOrWhiteSpace($VersionName)) { $VersionName = if ($Model -eq 'mbf') { '2.1-idcard-lite' } else { '2.1-idcard-r50' } }

$abiList = if ($Abi -eq 'both') { @('arm64-v8a', 'armeabi-v7a') } else { @('arm64-v8a') }

Write-Host ("[1/7] extract AARs  (model=$Model, abi=$Abi, version=$VersionName/$VersionCode)")
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskVision = Get-ChildItem $mvn -Recurse -Filter 'tasks-vision-*.aar' | Select-Object -First 1
$taskCore   = Get-ChildItem $mvn -Recurse -Filter 'tasks-core-*.aar'   | Select-Object -First 1
$ort        = Get-ChildItem $mvn -Recurse -Filter 'onnxruntime-android-*.aar' | Select-Object -First 1
if (-not $taskVision -or -not $taskCore -or -not $ort) { throw 'missing aar in dl/maven' }

function Extract-Aar($aar, $tag, $wantJni) {
  $z = [System.IO.Compression.ZipFile]::OpenRead($aar.FullName)
  try {
    foreach ($e in $z.Entries) {
      if ($e.FullName -eq 'classes.jar') {
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, (Join-Path $deps "$tag.jar"), $true)
      } elseif ($wantJni -and $e.FullName -match "^jni/($($abiList -join '|'))/(.+\.so)$") {
        $abi = $Matches[1]
        $dir = Join-Path $jni $abi
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, (Join-Path $dir ([System.IO.Path]::GetFileName($Matches[2]))), $true)
      }
    }
  } finally { $z.Dispose() }
}
Extract-Aar $taskVision 'mediapipe-vision' $true
Extract-Aar $taskCore   'mediapipe-core'   $false
Extract-Aar $ort        'onnxruntime'      $true

$guava = Get-ChildItem $mvn -Recurse -Filter 'guava-*-android.jar' | Select-Object -First 1
$annot = Get-ChildItem $mvn -Recurse -Filter 'annotation-1.1.0.jar' | Select-Object -First 1
Copy-Item $guava.FullName (Join-Path $deps 'guava.jar') -Force
Copy-Item $annot.FullName (Join-Path $deps 'android-annotation.jar') -Force

# tasks-core 的运行时依赖（漏了就会在结果解析时报 NoClassDefFoundError）
$extra = @(
  @('protobuf-javalite-*.jar', 'protobuf-javalite.jar'),
  @('flogger-0.6.jar',         'flogger.jar'),
  @('flogger-system-backend-*.jar', 'flogger-system-backend.jar'),
  @('failureaccess-*.jar',     'failureaccess.jar'),
  @('j2objc-annotations-*.jar','j2objc-annotations.jar'),
  @('jsr305-*.jar',            'jsr305.jar'),
  @('checker-qual-*.jar',      'checker-qual.jar'),
  @('error_prone_annotations-*.jar', 'error-prone-annotations.jar'),
  @('auto-value-annotations-*.jar', 'auto-value-annotations.jar')
)
foreach ($e in $extra) {
  $src = Get-ChildItem $mvn -Recurse -Filter $e[0] -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($src) { Copy-Item $src.FullName (Join-Path $deps $e[1]) -Force }
  else { Write-Host ("    [warn] 缺少 " + $e[0]) }
}

$depJarsAbs = Get-ChildItem $deps -Filter *.jar | ForEach-Object { $_.FullName }
$depJarsRel = $depJarsAbs | ForEach-Object { $_.Substring($root.Length + 1) }
Write-Host ("    jars: " + (($depJarsRel | ForEach-Object { Split-Path $_ -Leaf }) -join ', '))
Write-Host ("    so: " + (((Get-ChildItem $jni -Recurse -Filter *.so | ForEach-Object { $_.Directory.Name + '/' + $_.Name })) -join ', '))

# ---------- 准备 assets ----------
Write-Host "[1b] stage assets"
# 只删 arcface.onnx（按 -Model 重新复制）；OCR 模型是常驻资源，但换模型时旧文件要清掉，
# 否则上一版残留的 ppocr_rec.onnx 会白白多占 10MB
Remove-Item (Join-Path $assets 'arcface.onnx') -Force -ErrorAction SilentlyContinue
Get-ChildItem $assets -Filter 'ppocr_*' -ErrorAction SilentlyContinue | Remove-Item -Force
Copy-Item (Join-Path $root $modelSrc) (Join-Path $assets 'arcface.onnx') -Force
# 卡背 OCR：DBNet 检测 + 英文/数字识别 + 阿拉伯文识别（约 21.4MB），来源见 README「证件核验」一节
foreach ($f in @(@('models\ppocr_det.onnx', 'ppocr_det.onnx'),
                 @('models\ppocr_rec_en.onnx', 'ppocr_rec_en.onnx'),
                 @('models\ppocr_keys_en.txt', 'ppocr_keys_en.txt'),
                 @('models\ppocr_rec_ar.onnx', 'ppocr_rec_ar.onnx'),
                 @('models\ppocr_keys_ar.txt', 'ppocr_keys_ar.txt'))) {
  $src = Join-Path $root $f[0]
  if (-not (Test-Path $src)) { throw ("缺少 OCR 资源: " + $f[0]) }
  Copy-Item $src (Join-Path $assets $f[1]) -Force
}
$confLines = @(
  "# generated by tools/build.ps1",
  "id=$modelId",
  "desc=$modelDesc",
  "default_threshold=$modelThr"
)
[System.IO.File]::WriteAllLines((Join-Path $assets 'model.conf'), $confLines, (New-Object System.Text.UTF8Encoding($false)))
Write-Host ("    arcface.onnx = " + [math]::Round((Get-Item (Join-Path $assets 'arcface.onnx')).Length/1MB,2) + "MB  ($modelId)")

Write-Host "[2/7] aapt2 compile"
& $aapt2 compile --dir 'app/res' -o 'build/res.zip'
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed ($LASTEXITCODE)" }

Write-Host "[3/7] aapt2 link"
if (Test-Path (Join-Path $root 'build\base.apk')) { Remove-Item (Join-Path $root 'build\base.apk') -Force }
# 用构建参数覆盖版本号/应用名（aapt2 的 --version-code 会被 manifest 里的属性覆盖，所以直接改一份临时 manifest）
$mf = [System.IO.File]::ReadAllText((Join-Path $root 'app\AndroidManifest.xml'), [System.Text.Encoding]::UTF8)
$mf = [regex]::Replace($mf, 'android:versionCode="\d+"', ('android:versionCode="' + $VersionCode + '"'))
$mf = [regex]::Replace($mf, 'android:versionName="[^"]*"', ('android:versionName="' + $VersionName + '"'))
# 桌面显示名：按模型写死进临时 manifest（源 manifest 仍用 @string/app_name，不动源码）
$labelXml = $AppLabel.Replace('&', '&amp;').Replace('<', '&lt;').Replace('>', '&gt;').Replace('"', '&quot;')
if ($mf.Contains('android:label="@string/app_name"')) {
  $mf = $mf.Replace('android:label="@string/app_name"', ('android:label="' + $labelXml + '"'))
} else {
  Write-Host "    [warn] manifest 里没找到 android:label=""@string/app_name""，桌面名称未按模型区分"
}
[System.IO.File]::WriteAllText((Join-Path $root 'build\AndroidManifest.xml'), $mf, (New-Object System.Text.UTF8Encoding($false)))
Write-Host ("    appId=" + $appId + "  label=" + $AppLabel)

# --rename-manifest-package：只改最终 APK 的包名，R 类仍在 iq.digitalration.faceverify 下（Java 源码无需改动），
# 相对类名 .MainActivity 会被 aapt2 展开成完全限定名，所以组件仍然指向原来的类。
& $aapt2 link -o 'build/base.apk' -I $aj --manifest 'build/AndroidManifest.xml' `
  -R 'build/res.zip' --auto-add-overlay --java 'build/gen' --min-sdk-version 24 --target-sdk-version 33 `
  --version-code $VersionCode --version-name $VersionName `
  --rename-manifest-package $appId `
  -A 'app/assets' -0 .onnx -0 .task -0 .conf
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed ($LASTEXITCODE)" }

Write-Host "[4/7] javac"
$srcs = Get-ChildItem (Join-Path $root 'app\java') -Recurse -Filter *.java | ForEach-Object { $_.FullName.Substring($root.Length + 1) }
$rjava = Get-ChildItem (Join-Path $root 'build\gen') -Recurse -Filter R.java | ForEach-Object { $_.FullName.Substring($root.Length + 1) }
$cp = (($depJarsRel | ForEach-Object { $_.Replace('\','/') }) + $aj) -join ';'
$lines = @('-source', '8', '-target', '8', '-nowarn', '-encoding', 'UTF-8')
$lines += '-bootclasspath'; $lines += '"' + $aj + ';sdk/build-tools/android-15/core-lambda-stubs.jar"'
$lines += '-classpath';     $lines += '"' + $cp + '"'
$lines += '-d';             $lines += '"build/classes"'
foreach ($s in $srcs)  { $lines += '"' + $s.Replace('\','/') + '"' }
foreach ($s in $rjava) { $lines += '"' + $s.Replace('\','/') + '"' }
[System.IO.File]::WriteAllLines((Join-Path $root 'build\javac.args'), $lines, (New-Object System.Text.UTF8Encoding($false)))
& $javac "@build\javac.args"
if ($LASTEXITCODE -ne 0) { throw "javac failed ($LASTEXITCODE)" }

Write-Host "[5/7] d8"
$classFiles = Get-ChildItem (Join-Path $root 'build\classes') -Recurse -Filter *.class | ForEach-Object { $_.FullName.Substring($root.Length + 1) }
$d8args = @('--min-api', '24', '--lib', $aj, '--output', 'build/dex')
foreach ($j in $depJarsRel) { $d8args += $j }
foreach ($c in $classFiles) { $d8args += $c }
& $d8 @d8args
if ($LASTEXITCODE -ne 0) { throw "d8 failed ($LASTEXITCODE)" }
Write-Host ("    dex: " + ((Get-ChildItem (Join-Path $root 'build\dex') | ForEach-Object { $_.Name + ' ' + [math]::Round($_.Length/1MB,2) + 'MB' }) -join ', '))

Write-Host "[6/7] package"
$unsigned = Join-Path $root 'build\unsigned.apk'
Copy-Item (Join-Path $root 'build\base.apk') $unsigned -Force
$zip = [System.IO.Compression.ZipFile]::Open($unsigned, 'Update')
try {
  foreach ($dx in Get-ChildItem (Join-Path $root 'build\dex') -Filter *.dex) {
    $entry = $zip.CreateEntry($dx.Name, [System.IO.Compression.CompressionLevel]::NoCompression)
    $es = $entry.Open(); $fs = [System.IO.File]::OpenRead($dx.FullName); $fs.CopyTo($es); $fs.Close(); $es.Close()
  }
  foreach ($so in Get-ChildItem (Join-Path $root 'build\jni') -Recurse -Filter *.so) {
    $soAbi = Split-Path $so.DirectoryName -Leaf
    $entry = $zip.CreateEntry(("lib/$soAbi/" + $so.Name), [System.IO.Compression.CompressionLevel]::NoCompression)
    $es = $entry.Open(); $fs = [System.IO.File]::OpenRead($so.FullName); $fs.CopyTo($es); $fs.Close(); $es.Close()
  }
} finally { $zip.Dispose() }

Write-Host "[7/7] align + sign"
if (Test-Path (Join-Path $root 'build\aligned.apk')) { Remove-Item (Join-Path $root 'build\aligned.apk') -Force }
& $zipalign -f -p 4 'build/unsigned.apk' 'build/aligned.apk'
if ($LASTEXITCODE -ne 0) { throw "zipalign failed ($LASTEXITCODE)" }

$ks = Join-Path $root 'out\debug.keystore'
if (-not (Test-Path $ks)) {
  & $keytool -genkeypair -keystore $ks -storepass android -keypass android `
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 `
    -dname 'CN=Android Debug,O=Android,C=US' 2>&1 | Out-Null
}
$final = Join-Path $root ("out\" + $Out)
if (Test-Path $final) { Remove-Item $final -Force }
& $apksigner sign --ks $ks --ks-pass pass:android --key-pass pass:android --out $final (Join-Path $root 'build\aligned.apk')
if ($LASTEXITCODE -ne 0) { throw "apksigner failed ($LASTEXITCODE)" }

$size = [math]::Round((Get-Item $final).Length / 1MB, 1)
Write-Host ""
Write-Host ("OK -> out\" + $Out + "  ($size MB)  model=$Model abi=$Abi")
Write-Host ("     appId=" + $appId + "  label=" + $AppLabel + "  version=" + $VersionName + "/" + $VersionCode)
Write-Host ("     各模型包名互不相同，可同时安装：r50=" + $basePkg + "  mbf=" + $basePkg + ".lite")
} finally { Pop-Location }
