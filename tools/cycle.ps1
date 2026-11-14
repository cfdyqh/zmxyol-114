param(
    [string]$OutName = 'test-x.apk',
    [string]$Tersafe = 'native\libtersafe2.so',
    [string]$Tprt = '',
    [string]$Game = '',
    [string]$Dex = '',
    [string]$LevelSdb = '',
    [string]$SublevelSdb = '',
    [string]$SdbList = '',
    [string]$BlocksJson = '',
    [switch]$NoDex,
    [switch]$NoInstall,
    [int]$WaitSec = 8
)
# One build/install/run cycle for the de-Legu 11.4.0 offline package.
# All paths go through <path> (ASCII junction to the real workspace) because
# python.exe mangles non-ASCII command-line arguments here.
$ErrorActionPreference = 'Stop'
$ws = '<path>'
$here = "$ws\v11"
$adb = '<home>/AppData\Local\Android\Sdk\platform-tools\adb.exe'
$env:JAVA_HOME = '<path> Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
$zipalign = '<home>/AppData\Local\Android\Sdk\build-tools\36.1.0\zipalign.exe'
$w = '<path>'
$pkg = 'com.zmxyol.union.uc'

if (-not $NoDex) {
    powershell -ExecutionPolicy Bypass -File "$here\build.ps1" -OutName x.apk -NoRepack | Out-Null
}

$extra = @()
$extra += @('--add-dex', "$w\dex\classes.dex")
$extra += @('--app-class', 'org.yjmobile.zmxy.offline.O', '--old-app-class', 'org.yjmobile.zmxy.zmxyApplication')
$extra += @('--rename-string', 'android.support.v4.content.FileProvider=org.yjmobile.zmxy.offline.Provider')
if ($Tersafe) { $extra += @('--asset', "lib/armeabi/libtersafe2.so=$here\$Tersafe") }
if ($Tprt) { $extra += @('--asset', "lib/armeabi/libtprt.so=$here\$Tprt") }
if ($Game) { $extra += @('--asset', "lib/armeabi/libgame.so=$here\$Game") }
if ($Dex) { $extra += @('--replace-dex', $Dex) }
if ($LevelSdb) { $extra += @('--asset', "assets/database/level.sdb=$here\$LevelSdb") }
if ($SublevelSdb) { $extra += @('--asset', "assets/database/sublevel.sdb=$here\$SublevelSdb") }
if ($SdbList) { $extra += @('--asset', "assets/database/sdb.list=$here\$SdbList") }
if ($BlocksJson) { $extra += @('--asset', "assets/offline_blocks.json=$here\$BlocksJson") }
$extra += @('--min-sdk', '21')

& python "$here\repack.py" --src "$ws\base(2).apk" --out "$w\u2.apk" @extra
if ($LASTEXITCODE -ne 0) { throw 'repack failed' }
& $zipalign -p -f 4 "$w\u2.apk" "$w\a2.apk"
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }
$final = "$here\build\$OutName"
& "$env:JAVA_HOME\bin\java.exe" -jar "$ws\build\tools\bt\lib\apksigner.jar" sign `
    --ks "$ws\build\your-keystore.jks" --ks-pass pass:<your-pass> --key-pass pass:<your-pass> `
    --ks-key-alias yj --min-sdk-version 21 --v1-signing-enabled true `
    --v2-signing-enabled true --v3-signing-enabled true --out $final "$w\a2.apk" | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }
Write-Host "built $final ($((Get-Item $final).Length) bytes)"
if ($NoInstall) { return }

& $adb shell am force-stop $pkg | Out-Null
& $adb push $final /data/local/tmp/t.apk | Out-Null
$r = & $adb shell pm install -r -d /data/local/tmp/t.apk 2>&1
if ($r -notmatch 'Success') {
    & $adb shell pm uninstall $pkg | Out-Null
    $r = & $adb shell pm install -d /data/local/tmp/t.apk 2>&1
}
Write-Host "install: $r"
& $adb logcat -c
$job = Start-Job -ScriptBlock { & $using:adb logcat -b all -v threadtime }
Start-Sleep -Milliseconds 800
& $adb shell am start -n "$pkg/org.yjmobile.zmxy.zmxy" | Out-Null
Start-Sleep -Seconds $WaitSec
& $adb logcat -d -b all -v threadtime > "$here\last.log"
Stop-Job $job -ErrorAction SilentlyContinue
Remove-Job $job -Force -ErrorAction SilentlyContinue
$pid1 = (& $adb shell pidof $pkg) -replace '\s', ''
Write-Host "pid=$pid1"
Get-Content "$here\last.log" | Where-Object { $_ -match 'YJOffline|EXIT-HOOK|ATEXIT|SIGNAL|exited|hook: |frame\?|caller|libnb' } | Select-Object -Last 100
