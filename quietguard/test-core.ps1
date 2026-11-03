$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    New-Item -ItemType Directory -Force -Path 'build/core-checks' | Out-Null
    & javac -encoding UTF-8 -d build/core-checks app/src/main/java/cn/quietguard/app/RuleSet.java app/src/main/java/cn/quietguard/app/DnsPacket.java app/src/main/java/cn/quietguard/app/UpdateVersion.java app/src/main/java/cn/quietguard/app/SplashPolicy.java app/src/main/java/cn/quietguard/app/SplashWindow.java app/src/main/java/cn/quietguard/app/SplashScanBudget.java app/src/main/java/cn/quietguard/app/ProtectionMode.java app/src/main/java/cn/quietguard/app/UpdateIntegrity.java app/src/main/java/cn/quietguard/app/UpdateVpnState.java tests/CoreChecks.java tests/VersionChecks.java tests/SplashChecks.java tests/RuntimeChecks.java tests/UpdateChecks.java tests/UpdateVpnChecks.java
    if ($LASTEXITCODE -ne 0) { throw 'Core checks compilation failed' }
    & java -cp build/core-checks cn.quietguard.app.CoreChecks
    if ($LASTEXITCODE -ne 0) { throw 'Core checks failed' }
    & java -cp build/core-checks cn.quietguard.app.VersionChecks
    if ($LASTEXITCODE -ne 0) { throw 'Version checks failed' }
    & java -cp build/core-checks cn.quietguard.app.SplashChecks
    if ($LASTEXITCODE -ne 0) { throw 'Splash checks failed' }
    & java -cp build/core-checks cn.quietguard.app.RuntimeChecks
    if ($LASTEXITCODE -ne 0) { throw 'Runtime checks failed' }
    & python tests/check_splash_safety.py
    if ($LASTEXITCODE -ne 0) { throw 'Splash safety release guards failed' }
    & java -cp build/core-checks cn.quietguard.app.UpdateChecks
    if ($LASTEXITCODE -ne 0) { throw 'Update checks failed' }
    & java -cp build/core-checks cn.quietguard.app.UpdateVpnChecks
    if ($LASTEXITCODE -ne 0) { throw 'VPN update checks failed' }
} finally { Pop-Location }
