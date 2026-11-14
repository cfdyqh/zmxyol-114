param(
    [string]$OutName = 'fix157.apk',
    [string]$Base = '<path>',
    [string]$Libgame = '<tmp>/libgame_crit.so',
    [string]$Libtprt = '',
    [switch]$NoBuild,
    [switch]$NoMagic,
    [switch]$Install,
    [switch]$NoLaunch
)
# Build one offline APK from an already-good base:
#   <base> + fresh classes2.dex (+ optional 5-slot magic.sdb, + optional libgame.so)
# The base keeps its own classes.dex, libtersafe2.so and libtprt.so.
# NOTE: app class must stay org.yjmobile.zmxy.offline.O — a test base with
#       ...offline.P exits(15) right after nativeInit (no TP2 init).
$ErrorActionPreference = 'Stop'
# python.exe mangles non-ASCII argv -> every python argument goes through the
# ASCII junction <path> -> <desktop>/ol 重构 .
$here = '<path>'
$w = '<path>'
$env:JAVA_HOME = '<path> Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
$zipalign = '<home>/AppData\Local\Android\Sdk\build-tools\36.1.0\zipalign.exe'
$adb = '<path>'
$pkg = 'com.zmxyol.union.uc'
$py = '<home>/.dsh/...\dsh-primary-runtime\dependencies\python\python.exe'
if (!(Test-Path $py)) { $py = 'python' }

if (-not $NoBuild) {
    powershell -ExecutionPolicy Bypass -File "$here\build.ps1" -NoRepack
    if ($LASTEXITCODE -ne 0) { throw 'dex build failed' }
}

# keep the base's own classes.dex untouched
$baseDex = "$w\base_classes.dex"
& $py -c "import zipfile,sys; open(sys.argv[2],'wb').write(zipfile.ZipFile(sys.argv[1]).read('classes.dex'))" $Base $baseDex
if ($LASTEXITCODE -ne 0) { throw 'base dex extract failed' }

$sets = @()
$sets += ('classes.dex=' + $baseDex)
$sets += ('classes2.dex=' + "$w\dex\classes.dex")
if (-not $NoMagic -and (Test-Path "$w\magic_patched.sdb")) {
    $sets += ('assets/database/magic.sdb=' + "$w\magic_patched.sdb")
}
if ($Libgame -and (Test-Path $Libgame)) {
    $sets += ('lib/armeabi/libgame.so=' + $Libgame)
}
if ($Libtprt -and (Test-Path $Libtprt)) {
    $sets += ('lib/armeabi/libtprt.so=' + $Libtprt)
}

$uns = "$w\$OutName.unsigned"
& $py "$here\_zipswap3.py" $Base $uns @sets
if ($LASTEXITCODE -ne 0) { throw 'zipswap failed' }

& $zipalign -p -f 4 $uns "$w\$OutName.aligned"
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

$out = "$w\$OutName"
& "$env:JAVA_HOME\bin\java.exe" -jar "<path>" sign `
    --ks "<path>" --ks-pass pass:<your-pass> --key-pass pass:<your-pass> `
    --ks-key-alias yj --min-sdk-version 21 --v1-signing-enabled true `
    --v2-signing-enabled true --v3-signing-enabled true --out $out "$w\$OutName.aligned"
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }
Write-Host "built $out ($((Get-Item $out).Length) bytes)"

if ($Install) {
    & $adb -s 127.0.0.1:5555 shell am force-stop $pkg | Out-Null
    & $adb -s 127.0.0.1:5555 push $out /data/local/tmp/t.apk | Out-Null
    $r = & $adb -s 127.0.0.1:5555 shell pm install -r -d /data/local/tmp/t.apk 2>&1
    Write-Host "install: $r"
    if (-not $NoLaunch) {
        & $adb -s 127.0.0.1:5555 logcat -c
        & $adb -s 127.0.0.1:5555 shell am start -n "$pkg/org.yjmobile.zmxy.zmxy"
    }
}
