param(
    [string]$Base = '<downloads>/造梦西游OL-8.0-尝鲜版.apk',
    [string]$OutName = 'fix80.apk',
    [switch]$NoDex,
    [switch]$NoRepack
)
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$root = Split-Path $here -Parent
$env:JAVA_HOME = '<path> Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
$aj = '<home>/AppData\Local\Android\Sdk\platforms\android-34\android.jar'
$d8jar = '<home>/AppData\Local\Android\Sdk\build-tools\36.1.0\lib\d8.jar'
$zipalign = '<home>/AppData\Local\Android\Sdk\build-tools\36.1.0\zipalign.exe'
$py = '<home>/.dsh/...\dsh-primary-runtime\dependencies\python\python.exe'
if (!(Test-Path $py)) { $py = 'python' }
$w = '<path>'
New-Item -ItemType Directory -Force "$w\classes", "$w\dex" | Out-Null

if (-not $NoDex) {
    Remove-Item "$w\classes\*" -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item "$w\dex\*" -Recurse -Force -ErrorAction SilentlyContinue
    $srcs = Get-ChildItem (Join-Path $here 'java') -Recurse -Filter *.java | ForEach-Object { $_.FullName }
    Write-Host "javac $($srcs.Count) files"
    & "$env:JAVA_HOME\bin\javac.exe" -nowarn -source 8 -target 8 -bootclasspath $aj -d "$w\classes" $srcs
    if ($LASTEXITCODE -ne 0) { throw 'javac failed' }
    $classes = Get-ChildItem "$w\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName }
    Write-Host "d8 $($classes.Count) classes"
    & "$env:JAVA_HOME\bin\java.exe" -cp $d8jar com.android.tools.r8.D8 --lib $aj --min-api 21 --output "$w\dex" $classes
    if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }
}

if (-not $NoRepack) {
    # 底包路径含中文，PowerShell -> Python 传参会乱码，先复制到纯英文临时路径
    Copy-Item $Base "$w\base.apk" -Force
    & $py (Join-Path $here 'tools\repack80.py') --src "$w\base.apk" --out "$w\fix80.unsigned" --dex "$w\dex\classes.dex"
    if ($LASTEXITCODE -ne 0) { throw 'repack failed' }
    & $zipalign -p -f 4 "$w\fix80.unsigned" "$w\fix80.aligned"
    if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }
    $final = "$w\$OutName"
    & "$env:JAVA_HOME\bin\java.exe" -jar "$root\build\tools\bt\lib\apksigner.jar" sign `
        --ks "$root\build\your-keystore.jks" --ks-pass pass:<your-pass> --key-pass pass:<your-pass> `
        --ks-key-alias yj --min-sdk-version 21 --v1-signing-enabled true `
        --v2-signing-enabled true --v3-signing-enabled true `
        --out $final "$w\fix80.aligned"
    if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }
    Write-Host "OK -> $final ($((Get-Item $final).Length) bytes)"
}
