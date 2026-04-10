param(
    [ValidateSet("connected")]
    [string]$Mode = "connected",
    [string]$Variant = "E2e",
    [string]$LogDirectory = ".\test-logs\windows"
)

$ErrorActionPreference = "Stop"
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$rootDir = Split-Path -Parent $PSScriptRoot
Set-Location $rootDir

$taskName = "connected${Variant}AndroidTest"
$resolvedLogDirectory = [System.IO.Path]::GetFullPath((Join-Path $rootDir $LogDirectory))
[System.IO.Directory]::CreateDirectory($resolvedLogDirectory) | Out-Null

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$logPath = Join-Path $resolvedLogDirectory "$taskName-$timestamp.log"

Write-Host "Writing test log to $logPath"
$writer = New-Object System.IO.StreamWriter($logPath, $false, [System.Text.UTF8Encoding]::new($false))
$writer.AutoFlush = $true
$gradleBat = Join-Path $rootDir "gradlew.bat"
$cmdLine = "chcp 65001>nul && set JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8 && set GRADLE_OPTS=-Dfile.encoding=UTF-8 && `"$gradleBat`" $taskName --no-daemon --console=plain 2>&1"

try {
    & cmd.exe /d /c $cmdLine | ForEach-Object {
        Write-Host $_
        $writer.WriteLine($_)
    }
}
finally {
    $writer.Flush()
    $writer.Dispose()
}

$gradleExitCode = $LASTEXITCODE

if ($gradleExitCode -ne 0) {
    throw "Gradle test task failed with exit code $gradleExitCode. See $logPath"
}

Write-Host "Test log saved to $logPath"
