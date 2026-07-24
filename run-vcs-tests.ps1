# run-vcs-tests.ps1
# Comprehensive end-to-end integration and functionality test suite for DraftFlow VCS.

$ErrorActionPreference = "Stop"

# Define project locations
$projectRoot = Resolve-Path $PSScriptRoot
$testDir = Join-Path $projectRoot "df-test-workspace"

# Define jar libraries
$cpList = @(
    (Join-Path $projectRoot "target/classes"),
    "C:/Users/User/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/2.11.0/527175ca6d81050b53bdd4c457a6d6e017626b0e/gson-2.11.0.jar",
    "C:/Users/User/.gradle/caches/modules-2/files-2.1/info.picocli/picocli/4.7.6/77c2cb87814b6a03d431fc856024a9f8ff605ad4/picocli-4.7.6.jar",
    "C:/Users/User/.gradle/caches/modules-2/files-2.1/com.h2database/h2/2.2.224/7bdade27d8cd197d9b5ce9dc251f41d2edc5f7ad/h2-2.2.224.jar"
)
$cp = $cpList -join ";"

Write-Host "======================================================================" -ForegroundColor Cyan
Write-Host "             DRAFTFLOW VCS ADVANCED INTEGRATION TEST RUNNER" -ForegroundColor Cyan
Write-Host "======================================================================" -ForegroundColor Cyan
Write-Host "Project Root: $projectRoot" -ForegroundColor Gray
Write-Host "Test Workspace: $testDir" -ForegroundColor Gray
Write-Host ""

# Verify compiler and runner are available
if (!(Get-Command "java" -ErrorAction SilentlyContinue)) {
    Write-Error "java is not found in your PATH. Please install Java or add it to path."
}
if (!(Get-Command "javac" -ErrorAction SilentlyContinue)) {
    Write-Error "javac is not found in your PATH. Please install JDK or add it to path."
}

# 1. Compile Java files
Write-Host "[*] Compiling backend Java files..." -ForegroundColor Yellow
$javaFiles = Get-ChildItem -Path (Join-Path $projectRoot "src/main/java") -Filter *.java -Recurse | ForEach-Object { $_.FullName }
& javac -cp $cp -d (Join-Path $projectRoot "target/classes") $javaFiles
if ($LASTEXITCODE -ne 0) {
    Write-Error "Java compilation failed. Cannot run test suite."
}
Write-Host "[OK] Compilation successful." -ForegroundColor Green
Write-Host ""

# Setup test workspace
if (Test-Path $testDir) {
    Write-Host "[*] Cleaning old test workspace..." -ForegroundColor Gray
    Remove-Item $testDir -Recurse -Force -ErrorAction SilentlyContinue
}
New-Item -ItemType Directory -Path $testDir | Out-Null
Set-Location $testDir

# Track test outcomes
$global:passedCount = 0
$global:failedCount = 0
$global:results = @()

function Invoke-DraftFlow {
    param(
        [string[]]$ArgsList,
        [string]$Stdin = $null
    )
    # Cache and restore location to prevent drift
    $originalDir = Get-Location
    Set-Location $testDir
    
    $oldPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        if ($Stdin) {
            $output = $Stdin | & java -cp $cp com.draftflow.DraftFlow $ArgsList 2>&1
        } else {
            $output = & java -cp $cp com.draftflow.DraftFlow $ArgsList 2>&1
        }
        $exitCode = $LASTEXITCODE
        
        $normalizedOutput = ""
        if ($output) {
            if ($output -is [array]) {
                $normalizedOutput = ($output | ForEach-Object { $_.ToString() }) -join "`n"
            } else {
                $normalizedOutput = $output.ToString()
            }
        }
        
        return [PSCustomObject]@{
            ExitCode = $exitCode
            Stdout   = $normalizedOutput
        }
    } finally {
        $ErrorActionPreference = $oldPreference
        Set-Location $originalDir
    }
}

function Assert-Test {
    param(
        [string]$TestName,
        [scriptblock]$AssertionBlock
    )
    Write-Host "Running: $TestName... " -NoNewline -ForegroundColor Yellow
    try {
        $ok = &$AssertionBlock
        if ($ok) {
            $global:passedCount++
            $global:results += [PSCustomObject]@{ Test = $TestName; Status = "PASS"; Message = "OK" }
            Write-Host "PASS" -ForegroundColor Green
        } else {
            $global:failedCount++
            $global:results += [PSCustomObject]@{ Test = $TestName; Status = "FAIL"; Message = "Assertion failed" }
            Write-Host "FAIL" -ForegroundColor Red
        }
    } catch {
        $global:failedCount++
        $global:results += [PSCustomObject]@{ Test = $TestName; Status = "FAIL"; Message = $_.Exception.Message }
        Write-Host "FAIL (Error: $_)" -ForegroundColor Red
    }
}

# ==========================================
# TEST CASES
# ==========================================

# Test 1: Repo setup/init
Assert-Test "VCS Repository Initialization" {
    $res = Invoke-DraftFlow -ArgsList @("setup")
    $dbPath = Join-Path $testDir ".draftflow/index/index.mv.db"
    ($res.ExitCode -eq 0) -and (Test-Path $dbPath)
}

# Test 2: Cryptographic Keys Generation
Assert-Test "ECDSA Key Pair Generation" {
    $res = Invoke-DraftFlow -ArgsList @("keys")
    $privKey = Test-Path (Join-Path $testDir ".draftflow/id_ecdsa")
    $pubKey = Test-Path (Join-Path $testDir ".draftflow/id_ecdsa.pub")
    ($res.ExitCode -eq 0) -and $privKey -and $pubKey
}

# Test 3: Clean Status
Assert-Test "Initial Status Check" {
    $res = Invoke-DraftFlow -ArgsList @("status")
    ($res.ExitCode -eq 0) -and ($res.Stdout -like "*On branch: main*") -and ($res.Stdout -like "*clean*")
}

# Test 4: Save Initial Commit (Signed automatically since key pair exists)
Assert-Test "Initial Save/Commit with Key Signing" {
    # Create test files
    Set-Content -Path (Join-Path $testDir "hello.txt") -Value "Hello, DraftFlow VCS!"
    Set-Content -Path (Join-Path $testDir "readme.md") -Value "# Test Repository"
    
    $res = Invoke-DraftFlow -ArgsList @("save", "-m", "Initial commit")
    ($res.ExitCode -eq 0) -and ($res.Stdout -like "*Saved change*")
}

# Test 5: Verify Status After Commit
Assert-Test "Status After Commit" {
    $res = Invoke-DraftFlow -ArgsList @("status")
    ($res.ExitCode -eq 0) -and ($res.Stdout -like "*clean*")
}

# Test 6: Log History
Assert-Test "History Log Verification" {
    $res = Invoke-DraftFlow -ArgsList @("history")
    ($res.ExitCode -eq 0) -and ($res.Stdout -like "*Initial commit*")
}

# Test 7: Branch Creation
Assert-Test "Branch Creation" {
    $res = Invoke-DraftFlow -ArgsList @("branch", "-c", "feature-branch")
    ($res.ExitCode -eq 0) -and ($res.Stdout -like "*Created branch: feature-branch*")
}

# Test 8: Branch Switching
Assert-Test "Switching Branches" {
    $res = Invoke-DraftFlow -ArgsList @("switch", "feature-branch")
    $status = Invoke-DraftFlow -ArgsList @("status")
    ($res.ExitCode -eq 0) -and ($status.Stdout -like "*On branch: feature-branch*")
}

# Test 9: Save Changes on Branch
Assert-Test "Modify and Save on Branch" {
    Set-Content -Path (Join-Path $testDir "hello.txt") -Value "Hello, DraftFlow VCS! Modified on branch."
    Set-Content -Path (Join-Path $testDir "feature.txt") -Value "Exclusive feature work."
    
    $res = Invoke-DraftFlow -ArgsList @("save", "-m", "Add feature details")
    ($res.ExitCode -eq 0) -and ($res.Stdout -like "*Saved change*")
}

# Test 10: Switching Back to Main
Assert-Test "Switching Back to main" {
    $res = Invoke-DraftFlow -ArgsList @("switch", "main")
    # Verify file contents restored to main state
    $contentHello = Get-Content -Path (Join-Path $testDir "hello.txt") -Raw
    $hasFeatureFile = Test-Path (Join-Path $testDir "feature.txt")
    
    ($res.ExitCode -eq 0) -and ($contentHello.Trim() -eq "Hello, DraftFlow VCS!") -and ($hasFeatureFile -eq $false)
}

# Test 11: Stash Working Directory Modifications
Assert-Test "Stashing Modifications" {
    # Make local uncommitted modification
    Set-Content -Path (Join-Path $testDir "hello.txt") -Value "Hello, DraftFlow VCS! Temporary stash test."
    
    # Push stash
    $stashPush = Invoke-DraftFlow -ArgsList @("stash", "push")
    
    # Check that modification is reverted
    $contentHello = Get-Content -Path (Join-Path $testDir "hello.txt") -Raw
    
    # Pop stash
    $stashPop = Invoke-DraftFlow -ArgsList @("stash", "pop")
    $contentHelloAfterPop = Get-Content -Path (Join-Path $testDir "hello.txt") -Raw
    
    ($stashPush.ExitCode -eq 0) -and 
    ($contentHello.Trim() -eq "Hello, DraftFlow VCS!") -and 
    ($stashPop.ExitCode -eq 0) -and 
    ($contentHelloAfterPop.Trim() -eq "Hello, DraftFlow VCS! Temporary stash test.")
}

# Test 12: Ignore Pattern Check
Assert-Test "Ignore Patterns (.dfignore)" {
    # Add a pattern to ignore
    $resAdd = Invoke-DraftFlow -ArgsList @("ignore", "*.log")
    
    # Create ignored file
    Set-Content -Path (Join-Path $testDir "test.log") -Value "Log entry..."
    
    # Check ignore status
    $resCheck = Invoke-DraftFlow -ArgsList @("ignore", "--check", "test.log")
    
    ($resAdd.ExitCode -eq 0) -and ($resCheck.Stdout -like "*Ignored: Yes*")
}

# Test 13: Clean Untracked Files
Assert-Test "Clean Untracked Files" {
    Set-Content -Path (Join-Path $testDir "untracked.tmp") -Value "To be cleaned"
    $cleanSim = Invoke-DraftFlow -ArgsList @("clean")
    $cleanExec = Invoke-DraftFlow -ArgsList @("clean", "-f")
    
    ($cleanSim.Stdout -like "*untracked.tmp*") -and 
    ((Test-Path (Join-Path $testDir "untracked.tmp")) -eq $false)
}

# Test 14: Trace Line Blame
Assert-Test "Trace Line Evolution Blame" {
    $res = Invoke-DraftFlow -ArgsList @("trace", "hello.txt")
    ($res.ExitCode -eq 0) -and ($res.Stdout -like "*Hello, DraftFlow*")
}

# Test 15: Verification of Database Ledger
Assert-Test "Ledger Transaction Logging" {
    $res = Invoke-DraftFlow -ArgsList @("ledger")
    ($res.ExitCode -eq 0) -and ($res.Stdout -like "*HEAD@*")
}

# Test 16: Large File CDC Chunk Tree Storage
Assert-Test "FastCDC Large File Chunking (>1MB)" {
    New-Item -ItemType Directory -Path (Join-Path $testDir "assets") | Out-Null
    [byte[]]$largeData = New-Object byte[] 1200000 # 1.2MB file
    (New-Object Random).NextBytes($largeData)
    [System.IO.File]::WriteAllBytes((Join-Path $testDir "assets/large-file.bin"), $largeData)
    
    # Save the chunked file
    $res = Invoke-DraftFlow -ArgsList @("save", "-m", "Add large chunked file")
    ($res.ExitCode -eq 0) -and ($res.Stdout -like "*Saved change*")
}

# Test 17: Repository Hooks Execution & Block
Assert-Test "Repository pre-commit Hook Blocking" {
    # Configure a custom batch pre-commit hook that fails when TODO_CHECK_FAILED is found
    $hooksDir = Join-Path $testDir ".draftflow/hooks"
    Set-Content -Path (Join-Path $hooksDir "pre-commit.bat") -Value "@echo off`r`nfindstr /s /m `"TODO_CHECK_FAILED`" * 2>nul | findstr /v /i `"\.draftflow`" >nul`r`nif %errorlevel%==0 (`r`n    echo [Hook Alert] Blocked by pre-commit hook!`r`n    exit /b 1`r`n)`r`nexit /b 0"
    
    # Introduce blocking text in hello.txt
    Set-Content -Path (Join-Path $testDir "hello.txt") -Value "This commit has TODO_CHECK_FAILED in it!"
    
    # Run save, which should fail due to pre-commit hook
    $resSave = Invoke-DraftFlow -ArgsList @("save", "-m", "Should fail hook")
    
    # Remove block
    Set-Content -Path (Join-Path $testDir "hello.txt") -Value "Hello, DraftFlow VCS! Temporary stash test."
    
    ($resSave.ExitCode -ne 0) -and ($resSave.Stdout -like "*pre-commit hook failed*")
}

# Test 18: Rebase Operations
Assert-Test "VCS Rebase Integration" {
    # Switch to feature-branch, make edits and save
    Invoke-DraftFlow -ArgsList @("switch", "feature-branch") | Out-Null
    Set-Content -Path (Join-Path $testDir "feature.txt") -Value "Exclusive feature work. Rebase modification."
    Invoke-DraftFlow -ArgsList @("save", "-m", "Feature rebase commit") | Out-Null
    
    # Rebase feature-branch onto main
    $rebaseRes = Invoke-DraftFlow -ArgsList @("rebase", "main")
    
    ($rebaseRes.ExitCode -eq 0)
}

# Test 19: Cherry-Picking Commit
Assert-Test "Cherry-Pick Commit Apply" {
    # Switch to feature-branch
    Invoke-DraftFlow -ArgsList @("switch", "feature-branch") | Out-Null
    # Create specific single commit we want to cherry-pick
    Set-Content -Path (Join-Path $testDir "cherry.txt") -Value "Cherry pick this change!"
    Invoke-DraftFlow -ArgsList @("save", "-m", "Cherry target") | Out-Null
    
    # Switch back to main
    Invoke-DraftFlow -ArgsList @("switch", "main") | Out-Null
    
    # Cherry pick feature-branch (the latest commit)
    $cherryRes = Invoke-DraftFlow -ArgsList @("cherry-pick", "feature-branch")
    
    # Check that cherry.txt exists on main now
    $hasCherryFile = Test-Path (Join-Path $testDir "cherry.txt")
    
    ($cherryRes.ExitCode -eq 0) -and $hasCherryFile
}

# Test 20: Git Export Integration
Assert-Test "Git Export Conversion" {
    $exportTarget = Join-Path $testDir "exported-git-repo"
    New-Item -ItemType Directory -Path $exportTarget | Out-Null
    
    $res = Invoke-DraftFlow -ArgsList @("git-export", $exportTarget)
    $gitDb = Test-Path (Join-Path $exportTarget ".git")
    
    ($res.ExitCode -eq 0) -and $gitDb
}

# Test 21: Garbage Collection & Pruning
Assert-Test "CAS Object Garbage Collection" {
    $res = Invoke-DraftFlow -ArgsList @("prune")
    ($res.ExitCode -eq 0) -and ($res.Stdout -like "*Pruned*")
}

# Test 22: Repository Integrity Verification
Assert-Test "VCS Object Verification" {
    $res = Invoke-DraftFlow -ArgsList @("verify")
    ($res.ExitCode -eq 0) -and ($res.Stdout -like "*verify*")
}

# Test 23: Merge and Conflict Handling
Assert-Test "Merge Conflict Detection and Resolution" {
    # Create conflict.txt on main
    Set-Content -Path (Join-Path $testDir "conflict.txt") -Value "Line 1`nLine 2`nLine 3"
    Invoke-DraftFlow -ArgsList @("save", "-m", "Add base conflict file") | Out-Null
    
    # Switch to feature-branch, make edits and save
    Invoke-DraftFlow -ArgsList @("switch", "feature-branch") | Out-Null
    Set-Content -Path (Join-Path $testDir "conflict.txt") -Value "Line 1`nLine 2 branch edit`nLine 3"
    Invoke-DraftFlow -ArgsList @("save", "-m", "Branch edit") | Out-Null
    
    # Switch back to main, make conflicting edits and save
    Invoke-DraftFlow -ArgsList @("switch", "main") | Out-Null
    Set-Content -Path (Join-Path $testDir "conflict.txt") -Value "Line 1`nLine 2 main edit`nLine 3"
    Invoke-DraftFlow -ArgsList @("save", "-m", "Main edit") | Out-Null
    
    # Merge feature-branch into main
    $mergeRes = Invoke-DraftFlow -ArgsList @("merge", "feature-branch")
    
    # Verify conflict reported
    $statusRes = Invoke-DraftFlow -ArgsList @("status")
    
    # Resolve using resolve command (choosing ours, theirs, or both)
    # Stdin "1" represents selection 1 (Keep OURS version)
    $resolveRes = Invoke-DraftFlow -ArgsList @("resolve") -Stdin "1"
    
    # Save the merge resolution
    $saveMerge = Invoke-DraftFlow -ArgsList @("save", "-m", "Resolved merge conflict")
    
    ($mergeRes.ExitCode -eq 0) -and 
    ($statusRes.Stdout -like "*conflict*") -and 
    ($saveMerge.ExitCode -eq 0)
}

# ==========================================
# REPORT SUMMARY
# ==========================================
Set-Location $projectRoot

Write-Host ""
Write-Host "======================================================================" -ForegroundColor Cyan
Write-Host "                           TEST SUMMARY" -ForegroundColor Cyan
Write-Host "======================================================================" -ForegroundColor Cyan
Write-Host "Total Tests Passed: $global:passedCount" -ForegroundColor Green
Write-Host "Total Tests Failed: $global:failedCount" -ForegroundColor ($(if ($global:failedCount -eq 0) { "Green" } else { "Red" }))
Write-Host "======================================================================" -ForegroundColor Cyan
Write-Host ""

if ($global:failedCount -gt 0) {
    Write-Host "[FAIL] Some tests failed. Please review output logs above." -ForegroundColor Red
    exit 1
} else {
    Write-Host "[SUCCESS] All automated checks verified successfully!" -ForegroundColor Green
    Write-Host ""
    Write-Host "----------------------------------------------------------------------" -ForegroundColor Cyan
    Write-Host "                 MANUAL TESTING SANDBOX INITIALIZED" -ForegroundColor Cyan
    Write-Host "----------------------------------------------------------------------" -ForegroundColor Cyan
    Write-Host "A dummy test folder has been preserved for you to test VCS commands manually." -ForegroundColor Gray
    Write-Host "Location: $testDir" -ForegroundColor White
    Write-Host ""
    Write-Host "How to run DraftFlow commands in this directory:" -ForegroundColor Gray
    Write-Host "  1. Open PowerShell and change directory:" -ForegroundColor Gray
    Write-Host "     cd '$testDir'" -ForegroundColor Green
    Write-Host ""
    Write-Host "  2. Example commands you can run:" -ForegroundColor Gray
    Write-Host "     # Check repo status:" -ForegroundColor Gray
    Write-Host "     java -cp `"$cp`" com.draftflow.DraftFlow status" -ForegroundColor Yellow
    Write-Host "     # View history:" -ForegroundColor Gray
    Write-Host "     java -cp `"$cp`" com.draftflow.DraftFlow history" -ForegroundColor Yellow
    Write-Host "     # Make changes and save a new revision:" -ForegroundColor Gray
    Write-Host "     Echo 'my update' >> hello.txt" -ForegroundColor Yellow
    Write-Host "     java -cp `"$cp`" com.draftflow.DraftFlow save -m 'My manual save'" -ForegroundColor Yellow
    Write-Host "----------------------------------------------------------------------" -ForegroundColor Cyan
    exit 0
}
