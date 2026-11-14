param(
  [Parameter(Mandatory=$true)][string]$Json,
  [int]$Wait = 24
)
# Push a login variant, restart, then decode what the client actually stored.
$adb = '<home>/AppData\Local\Android\Sdk\platform-tools\adb.exe'
$pkg = 'com.zmxyol.union.uc'
$env:ANDROID_SERIAL = 'emulator-5554'
$env:PYTHONIOENCODING = 'utf-8'
& $adb -s emulator-5554 push $Json /sdcard/yj/login.json 2>&1 | Out-Null
# remove stale savedata so we can tell whether this run rewrote it
& $adb -s emulator-5554 shell "su -c 'rm -f /data/data/$pkg/files/savedata.dat'" | Out-Null
& $adb -s emulator-5554 shell "am force-stop $pkg" | Out-Null
& $adb -s emulator-5554 logcat -c
Start-Sleep -Milliseconds 500
& $adb -s emulator-5554 shell "am start -n $pkg/org.yjmobile.zmxy.zmxy" | Out-Null
Start-Sleep -Seconds $Wait
$p = (& $adb -s emulator-5554 shell "pidof $pkg") -replace '\s',''
$crash = (& $adb -s emulator-5554 logcat -d | Select-String -Pattern 'Fatal signal' | Select-Object -Last 1)
& $adb -s emulator-5554 shell "su -c 'cp /data/data/$pkg/files/savedata.dat /data/local/tmp/sd.dat; chmod 666 /data/local/tmp/sd.dat'" | Out-Null
& $adb -s emulator-5554 pull /data/local/tmp/sd.dat '<path>' 2>&1 | Out-Null
Write-Host ("variant={0} pid=[{1}] crash={2}" -f (Split-Path $Json -Leaf), $p, ($crash -ne $null))
if (Test-Path '<path>') {
  python -W ignore -c @"
import codec,json
try:
    d=json.loads(codec.decode(open(r'<path>','rb').read()))
    print('   rid=%r service=%r cname=%r roleid=%r servicename=%r' % (d.get('rid'), d.get('service'), d.get('cname'), d.get('roleid'), d.get('servicename')))
except Exception as e:
    print('   decode failed', e)
"@
} else { Write-Host '   no savedata written' }
