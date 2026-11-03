$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    New-Item -ItemType Directory -Force -Path 'build/core-checks' | Out-Null
    & javac -encoding UTF-8 -d build/core-checks app/src/main/java/cn/quietguard/app/RuleSet.java app/src/main/java/cn/quietguard/app/DnsPacket.java app/src/main/java/cn/quietguard/app/UpdateVersion.java tests/CoreChecks.java tests/VersionChecks.java
    if ($LASTEXITCODE -ne 0) { throw 'Core checks compilation failed' }
    & java -cp build/core-checks cn.quietguard.app.CoreChecks
    if ($LASTEXITCODE -ne 0) { throw 'Core checks failed' }
    & java -cp build/core-checks cn.quietguard.app.VersionChecks
    if ($LASTEXITCODE -ne 0) { throw 'Version checks failed' }
} finally { Pop-Location }
