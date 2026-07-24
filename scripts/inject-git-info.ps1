# inject-git-info.ps1
# Regenerates Configuration.java and injects build metadata for Windows builds.
# Configuration.java is gitignored; always recreate it from the tracked template
# so an existing checkout cannot retain stale version or configuration values.

$ConfigFile   = "src/main/java/org/perlonjava/core/Configuration.java"
$TemplateFile = "src/main/java/org/perlonjava/core/Configuration.java.in"

if (-not (Test-Path $TemplateFile)) {
    Write-Error "Configuration template not found: $TemplateFile"
    exit 1
}

Copy-Item $TemplateFile $ConfigFile -Force -ErrorAction Stop

try {
    $GitCommitId = git rev-parse --short HEAD 2>$null
    $GitCommitDate = git log -1 --format=%cs HEAD 2>$null

    if ($GitCommitId -and $GitCommitId -ne "dev") {
        $BuildTimestamp = (Get-Date).ToString(
            "MMM dd yyyy HH:mm:ss",
            [System.Globalization.CultureInfo]::InvariantCulture
        ) -replace '^(\w{3}) 0', '$1  '
        $content = Get-Content $ConfigFile -Raw

        $content = $content -replace '(gitCommitId\s*=\s*)"[^"]*"', ('$1"' + $GitCommitId + '"')
        $content = $content -replace '(gitCommitDate\s*=\s*)"[^"]*"', ('$1"' + $GitCommitDate + '"')
        $content = $content -replace '(buildTimestamp\s*=\s*)"[^"]*"', ('$1"' + $BuildTimestamp + '"')

        Set-Content $ConfigFile -Value $content -NoNewline
    }
} catch {
    $GitCommitId = "dev"
    $GitCommitDate = "unknown"
    Write-Host "Git info injection skipped: $_"
}

Write-Host "Regenerated Configuration.java and injected git info: $GitCommitId ($GitCommitDate)"
