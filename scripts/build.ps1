param(
    [switch]$Install,
    [string]$Serial
)

$ErrorActionPreference = "Stop"

$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$Sdk = $env:ANDROID_HOME
if (-not $Sdk) {
    $Sdk = $env:ANDROID_SDK_ROOT
}
if (-not $Sdk) {
    throw "ANDROID_HOME or ANDROID_SDK_ROOT must point to the Android SDK."
}

$BuildToolsRoot = Join-Path $Sdk "build-tools"
$BuildTools = Get-ChildItem -Path $BuildToolsRoot -Directory | Sort-Object Name -Descending | Select-Object -First 1
if (-not $BuildTools) {
    throw "Android build-tools are not installed."
}

$PlatformRoot = Join-Path $Sdk "platforms"
$Platform = Get-ChildItem -Path $PlatformRoot -Directory | Sort-Object Name -Descending | Select-Object -First 1
if (-not $Platform) {
    throw "Android platforms are not installed."
}

$AndroidJar = Join-Path $Platform.FullName "android.jar"
$Aapt2 = Join-Path $BuildTools.FullName "aapt2.exe"
$D8 = Join-Path $BuildTools.FullName "d8.bat"
$ZipAlign = Join-Path $BuildTools.FullName "zipalign.exe"
$ApkSigner = Join-Path $BuildTools.FullName "apksigner.bat"

$ArCoreVersion = "1.54.0"
$ArCoreDir = Join-Path $Root "deps\arcore"
$ArCoreAar = Join-Path $ArCoreDir "core-$ArCoreVersion.aar"
$ArCoreExpanded = Join-Path $ArCoreDir "expanded"
$ArCoreClasses = Join-Path $ArCoreExpanded "classes.jar"
if (-not (Test-Path $ArCoreAar)) {
    New-Item -ItemType Directory -Force -Path $ArCoreDir | Out-Null
    $ArCoreUrl = "https://dl.google.com/dl/android/maven2/com/google/ar/core/$ArCoreVersion/core-$ArCoreVersion.aar"
    Invoke-WebRequest -UseBasicParsing -Uri $ArCoreUrl -OutFile $ArCoreAar
}
if (-not (Test-Path $ArCoreClasses)) {
    if (Test-Path $ArCoreExpanded) {
        Remove-Item -LiteralPath $ArCoreExpanded -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $ArCoreExpanded | Out-Null
    Push-Location $ArCoreExpanded
    try {
        & jar xf $ArCoreAar
    } finally {
        Pop-Location
    }
}

foreach ($Tool in @($AndroidJar, $Aapt2, $D8, $ZipAlign, $ApkSigner)) {
    if (-not (Test-Path $Tool)) {
        throw "Missing required Android build tool: $Tool"
    }
}

$BuildDir = Join-Path $Root "build"
$ResolvedBuildDir = [System.IO.Path]::GetFullPath($BuildDir)
$ResolvedRoot = [System.IO.Path]::GetFullPath($Root)
if (-not $ResolvedBuildDir.StartsWith($ResolvedRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to clean a build directory outside the project."
}
if (Test-Path $ResolvedBuildDir) {
    Remove-Item -LiteralPath $ResolvedBuildDir -Recurse -Force
}

$CompiledDir = Join-Path $BuildDir "compiled"
$GeneratedDir = Join-Path $BuildDir "generated"
$ClassesDir = Join-Path $BuildDir "classes"
$DexDir = Join-Path $BuildDir "dex"
$IntermediateDir = Join-Path $BuildDir "intermediates"
$OutputDir = Join-Path $BuildDir "outputs"
$CheckedInApkDir = Join-Path $Root "apk"
$SigningDir = Join-Path $Root "signing"
New-Item -ItemType Directory -Force -Path $CompiledDir, $GeneratedDir, $ClassesDir, $DexDir, $IntermediateDir, $OutputDir | Out-Null
New-Item -ItemType Directory -Force -Path $SigningDir, $CheckedInApkDir | Out-Null

$Manifest = Join-Path $Root "app\src\main\AndroidManifest.xml"
$Resources = Join-Path $Root "app\src\main\res"
$CompiledResources = Join-Path $CompiledDir "resources.zip"
$UnsignedApk = Join-Path $IntermediateDir "lawnmapper-unsigned.apk"
$DexedApk = Join-Path $IntermediateDir "lawnmapper-dexed-unsigned.apk"
$AlignedApk = Join-Path $IntermediateDir "lawnmapper-aligned.apk"
$FinalApk = Join-Path $OutputDir "lawnmapper-debug.apk"
$ClassesJar = Join-Path $IntermediateDir "classes.jar"

& $Aapt2 compile --dir $Resources -o $CompiledResources
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }
& $Aapt2 link `
    -o $UnsignedApk `
    -I $AndroidJar `
    --manifest $Manifest `
    --java $GeneratedDir `
    --min-sdk-version 26 `
    --target-sdk-version 36 `
    --version-code 1 `
    --version-name "0.1.0" `
    $CompiledResources
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

$SourceList = Join-Path $BuildDir "java-sources.txt"
$JavaSources = @()
$JavaSources += Get-ChildItem -Path (Join-Path $Root "app\src\main\java") -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$JavaSources += Get-ChildItem -Path $GeneratedDir -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$JavaSources | Set-Content -Path $SourceList -Encoding ASCII

$JavacClasspath = "$AndroidJar;$ArCoreClasses"
& javac -encoding UTF-8 -source 17 -target 17 -classpath $JavacClasspath -d $ClassesDir "@$SourceList"
if ($LASTEXITCODE -ne 0) { throw "javac failed" }
& jar cf $ClassesJar -C $ClassesDir .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }
& $D8 --lib $AndroidJar --min-api 26 --output $DexDir $ClassesJar $ArCoreClasses
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Copy-Item -LiteralPath $UnsignedApk -Destination $DexedApk -Force
& jar uf $DexedApk -C $DexDir classes.dex
if ($LASTEXITCODE -ne 0) { throw "adding classes.dex failed" }
$NativeLibRoot = Join-Path $IntermediateDir "native-libs"
New-Item -ItemType Directory -Force -Path (Join-Path $NativeLibRoot "lib") | Out-Null
Get-ChildItem -Path (Join-Path $ArCoreExpanded "jni") -Directory | ForEach-Object {
    $AbiTarget = Join-Path (Join-Path $NativeLibRoot "lib") $_.Name
    New-Item -ItemType Directory -Force -Path $AbiTarget | Out-Null
    Copy-Item -LiteralPath (Join-Path $_.FullName "libarcore_sdk_c.so") -Destination $AbiTarget -Force
    Copy-Item -LiteralPath (Join-Path $_.FullName "libarcore_sdk_jni.so") -Destination $AbiTarget -Force
}
& jar uf $DexedApk -C $NativeLibRoot lib
if ($LASTEXITCODE -ne 0) { throw "adding native libraries failed" }
& $ZipAlign -p -f 4 $DexedApk $AlignedApk
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

$KeyStore = Join-Path $SigningDir "debug.keystore"
if (-not (Test-Path $KeyStore)) {
    & keytool -genkeypair `
        -keystore $KeyStore `
        -storepass android `
        -keypass android `
        -alias androiddebugkey `
        -keyalg RSA `
        -keysize 2048 `
        -validity 10000 `
        -dname "CN=Android Debug,O=Android,C=US" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}

& $ApkSigner sign `
    --ks $KeyStore `
    --ks-pass pass:android `
    --key-pass pass:android `
    --out $FinalApk `
    $AlignedApk
if ($LASTEXITCODE -ne 0) { throw "apksigner sign failed" }
& $ApkSigner verify --verbose $FinalApk
if ($LASTEXITCODE -ne 0) { throw "apksigner verify failed" }

$CheckedInApk = Join-Path $CheckedInApkDir "lawnmapper-debug.apk"
Copy-Item -LiteralPath $FinalApk -Destination $CheckedInApk -Force

Write-Host "Built $FinalApk"
Write-Host "Updated $CheckedInApk"

if ($Install) {
    $Adb = Join-Path $Sdk "platform-tools\adb.exe"
    if (-not (Test-Path $Adb)) {
        throw "adb was not found at $Adb"
    }
    $DeviceSerial = $Serial
    if (-not $DeviceSerial) {
        $DeviceLine = & $Adb devices | Select-String -Pattern "^\S+\s+device$" | Select-Object -First 1
        if ($DeviceLine) {
            $DeviceSerial = ($DeviceLine.Line -split "\s+")[0]
        }
    }
    if (-not $DeviceSerial) {
        Write-Warning "No connected ADB device found. Enable USB debugging, connect the phone, then run .\scripts\build.ps1 -Install"
    } else {
        & $Adb -s $DeviceSerial install -r $FinalApk
        if ($LASTEXITCODE -ne 0) { throw "adb install failed" }
        Write-Host "Installed on $DeviceSerial"
    }
}
