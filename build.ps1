# Navdy HUD 앱 빌드. Gradle 없이 SDK 도구만 쓴다.
# javac 는 반드시 -encoding UTF-8 이어야 한다(윈도 기본 949 로 읽으면 한글 주석에서 깨진다).
#
# 네이티브 도구가 안내문을 stderr 로 내보내면 'Stop' 에서는 그것만으로 중단된다.
# 실패 판정은 종료코드로만 하고, 출력은 그대로 흘려보낸다.
$ErrorActionPreference = 'Continue'
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$bt  = "$sdk\build-tools\36.0.0"
$jar = "$sdk\platforms\android-34\android.jar"
$P   = $PSScriptRoot
Set-Location $P

Remove-Item -Recurse -Force "$P\build\classes", "$P\build\gen" -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path "$P\build\classes" | Out-Null

& "$bt\aapt2.exe" compile --dir res -o build\res.zip
& "$bt\aapt2.exe" link -o build\base.apk -I $jar --manifest AndroidManifest.xml `
    --java build\gen --min-sdk-version 22 --target-sdk-version 22 build\res.zip

$srcs = (Get-ChildItem -Recurse -Filter *.java src, build\gen -ErrorAction SilentlyContinue |
         ForEach-Object { $_.FullName })
javac -encoding UTF-8 -source 8 -target 8 -nowarn -bootclasspath $jar -classpath $jar `
      -d build\classes $srcs
if ($LASTEXITCODE -ne 0) { throw "javac 실패" }

$cls = (Get-ChildItem -Recurse -Filter *.class build\classes | ForEach-Object { $_.FullName })
& "$bt\d8.bat" --min-api 22 --lib $jar --output build $cls | Out-Null

Copy-Item build\base.apk build\unsigned.apk -Force
Copy-Item build\classes.dex .\classes.dex -Force
& "$bt\aapt.exe" add build\unsigned.apk classes.dex | Out-Null
Remove-Item .\classes.dex -Force

& "$bt\zipalign.exe" -f 4 build\unsigned.apk build\aligned.apk
# 서명키는 build 폴더 밖에 둔다. build 는 .gitignore 대상이라 저장소를 새로
# 받거나 build 를 지우면 키가 사라진다. 그렇게 만들어진 새 키로 서명하면
# 기기가 INSTALL_FAILED_UPDATE_INCOMPATIBLE 로 거부한다(실제로 겪었다).
$keyDir = Join-Path $env:USERPROFILE ".navdyhud"
$keystore = Join-Path $keyDir "release.keystore"
if (-not (Test-Path $keystore)) {
  New-Item -ItemType Directory -Force $keyDir | Out-Null
  keytool -genkeypair -keystore $keystore -storepass android -keypass android `
          -alias hud -keyalg RSA -keysize 2048 -validity 10000 `
          -dname "CN=CommaHUD, OU=dev, O=dev, L=Seoul, C=KR" 2>&1 | Out-Null
  Write-Output "새 서명키 생성: $keystore"
}
& "$bt\apksigner.bat" sign --ks $keystore --ks-pass pass:android --key-pass pass:android `
    --ks-key-alias hud --min-sdk-version 22 --out build\CommaHUD.apk build\aligned.apk

Write-Output "빌드 완료: $((Get-Item build\CommaHUD.apk).Length) bytes"
