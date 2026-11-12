$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    New-Item -ItemType Directory -Force -Path 'build/core-checks' | Out-Null
    & javac -encoding UTF-8 -d build/core-checks app/src/main/java/cn/quietguard/app/RuleSet.java app/src/main/java/cn/quietguard/app/DnsPacket.java app/src/main/java/cn/quietguard/app/SniClientHello.java app/src/main/java/cn/quietguard/app/UpdateVersion.java app/src/main/java/cn/quietguard/app/SplashPolicy.java app/src/main/java/cn/quietguard/app/SplashWindow.java app/src/main/java/cn/quietguard/app/SplashScanBudget.java app/src/main/java/cn/quietguard/app/ProtectionMode.java app/src/main/java/cn/quietguard/app/UpdateIntegrity.java app/src/main/java/cn/quietguard/app/UpdateVpnState.java app/src/main/java/cn/quietguard/app/SplashButton.java app/src/main/java/cn/quietguard/app/SplashDiagnostic.java app/src/main/java/cn/quietguard/app/SplashTextTap.java app/src/main/java/cn/quietguard/app/SplashOcrPolicy.java tests/ButtonChecks.java tests/CoreChecks.java tests/TextTapChecks.java tests/OcrChecks.java tests/SniChecks.java app/src/main/java/cn/quietguard/app/AccessibilitySelection.java tests/AccessibilityChecks.java tests/VersionChecks.java tests/SplashChecks.java tests/RuntimeChecks.java tests/UpdateChecks.java tests/UpdateVpnChecks.java app/src/main/java/cn/quietguard/app/ProtectionControl.java app/src/main/java/cn/quietguard/app/ProtectionStatus.java app/src/main/java/cn/quietguard/app/SelectionPlan.java app/src/main/java/cn/quietguard/app/RuleStats.java tests/ControlChecks.java tests/HttpChecks.java app/src/main/java/cn/quietguard/app/HttpHost.java app/src/main/java/cn/quietguard/app/DnsEndpoint.java
    if ($LASTEXITCODE -ne 0) { throw 'Core checks compilation failed' }
    & java -cp build/core-checks cn.quietguard.app.CoreChecks
    if ($LASTEXITCODE -ne 0) { throw 'Core checks failed' }
    & java -cp build/core-checks cn.quietguard.app.SniChecks
    if ($LASTEXITCODE -ne 0) { throw 'SNI parser checks failed' }
    & java -cp build/core-checks cn.quietguard.app.AccessibilityChecks
    if ($LASTEXITCODE -ne 0) { throw 'Accessibility selection checks failed' }
    & java -cp build/core-checks cn.quietguard.app.VersionChecks
    if ($LASTEXITCODE -ne 0) { throw 'Version checks failed' }
    & java -cp build/core-checks cn.quietguard.app.SplashChecks
    if ($LASTEXITCODE -ne 0) { throw 'Splash checks failed' }
    & java -cp build/core-checks cn.quietguard.app.RuntimeChecks
    if ($LASTEXITCODE -ne 0) { throw 'Runtime checks failed' }
    & java -cp build/core-checks cn.quietguard.app.ButtonChecks
    if ($LASTEXITCODE -ne 0) { throw 'Button tree checks failed' }
    & python tests/check_splash_safety.py
    if ($LASTEXITCODE -ne 0) { throw 'Splash safety release guards failed' }
    & java -cp build/core-checks cn.quietguard.app.TextTapChecks
    if ($LASTEXITCODE -ne 0) { throw 'Text tap checks failed' }
    & java -cp build/core-checks cn.quietguard.app.OcrChecks
    if ($LASTEXITCODE -ne 0) { throw 'OCR checks failed' }
    & java -cp build/core-checks cn.quietguard.app.UpdateChecks
    if ($LASTEXITCODE -ne 0) { throw 'Update checks failed' }
    & java -cp build/core-checks cn.quietguard.app.ControlChecks
    if ($LASTEXITCODE -ne 0) { throw 'Protection control checks failed' }
    & java -cp build/core-checks cn.quietguard.app.UpdateVpnChecks
    if ($LASTEXITCODE -ne 0) { throw 'VPN update checks failed' }
    & java -cp build/core-checks cn.quietguard.app.HttpChecks
    if ($LASTEXITCODE -ne 0) { throw 'Plain HTTP host checks failed' }
} finally { Pop-Location }
