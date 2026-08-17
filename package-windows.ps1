$ErrorActionPreference = "Stop"

if (-not $env:JAVA_HOME) {
    throw "JAVA_HOME is not set. Point it to a full JDK 21 installation."
}

$mvnw = Join-Path $PSScriptRoot "mvnw.cmd"
$jpackage = Join-Path $env:JAVA_HOME "bin\jpackage.exe"
if (-not (Test-Path $jpackage)) {
    throw "jpackage.exe was not found in JAVA_HOME. A full JDK 21 is required."
}

Push-Location $PSScriptRoot
try {
    & $mvnw clean package -DskipTests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    $jar = Join-Path $PSScriptRoot "target\prodamus-predictive-client.jar"
    if (-not (Test-Path $jar)) {
        throw "Maven build did not produce $jar."
    }

    $inputDir = Join-Path $PSScriptRoot "target\jpackage-input"
    $dist = Join-Path $PSScriptRoot "dist"
    if (Test-Path $inputDir) { Remove-Item $inputDir -Recurse -Force }
    if (Test-Path $dist) { Remove-Item $dist -Recurse -Force }
    New-Item -ItemType Directory -Path $inputDir | Out-Null
    New-Item -ItemType Directory -Path $dist | Out-Null
    Copy-Item $jar (Join-Path $inputDir "prodamus-predictive-client.jar")

    $hasWix = (Get-Command candle.exe -ErrorAction SilentlyContinue) -and
              (Get-Command light.exe -ErrorAction SilentlyContinue)
    $packageType = if ($hasWix) { "exe" } else { "app-image" }
    if (-not $hasWix) {
        Write-Warning "WiX was not found. Building a portable native app-image instead of an installer."
    }

    $jpackageArgs = @(
        "--type", $packageType,
        "--name", "Prodamus Predictive",
        "--app-version", "1.4.0",
        "--vendor", "Prodamus",
        "--description", "Predictive dual-session AI sales assistant",
        "--input", $inputDir,
        "--main-jar", "prodamus-predictive-client.jar",
        "--dest", $dist,
        "--java-options", "-Dfile.encoding=UTF-8"
    )
    if ($hasWix) {
        $jpackageArgs += @("--win-menu", "--win-shortcut")
    }
    & $jpackage @jpackageArgs

    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    Write-Host "Done. Prodamus Predictive package is available in $dist" -ForegroundColor Green
}
finally {
    Pop-Location
}
