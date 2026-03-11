# inject-git-info.ps1
# Injects git commit ID and date into Configuration.java for Windows builds

$ConfigFile = "src/main/java/org/perlonjava/core/Configuration.java"

if (Test-Path $ConfigFile) {
    try {
        $GitCommitId = git rev-parse --short HEAD 2>$null
        $GitCommitDate = git log -1 --format=%cs HEAD 2>$null
        
        if ($GitCommitId -and $GitCommitId -ne "dev") {
            $content = Get-Content $ConfigFile -Raw
            
            # Replace gitCommitId value
            $content = $content -replace '(gitCommitId\s*=\s*)"[^"]*"', ('$1"' + $GitCommitId + '"')
            
            # Replace gitCommitDate value
            $content = $content -replace '(gitCommitDate\s*=\s*)"[^"]*"', ('$1"' + $GitCommitDate + '"')
            
            Set-Content $ConfigFile -Value $content -NoNewline
            Write-Host "Injected git info: $GitCommitId ($GitCommitDate)"
        }
    } catch {
        Write-Host "Git info injection skipped: $_"
    }
}
