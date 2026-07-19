# build-fat-jar.ps1
# Automates the creation of a self-contained fat JAR for DraftFlow VCS.

$ErrorActionPreference = "Stop"
$projectRoot = $PSScriptRoot

Write-Host "[*] Setting up build directories..."
$targetDir = Join-Path $projectRoot "target"
$classesDir = Join-Path $targetDir "classes"
$tempDir = Join-Path $targetDir "fat-jar-temp"

# Clean and recreate temp directories
Remove-Item -Recurse -Force $tempDir -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $tempDir | Out-Null
New-Item -ItemType Directory -Path $classesDir -Force | Out-Null

# Find Java source files
$javaFiles = Get-ChildItem -Path (Join-Path $projectRoot "src/main/java") -Filter "*.java" -Recurse | ForEach-Object { $_.FullName }

# Define classpath
$dependencies = @(
    "C:/Users/User/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/2.11.0/527175ca6d81050b53bdd4c457a6d6e017626b0e/gson-2.11.0.jar",
    "C:/Users/User/.gradle/caches/modules-2/files-2.1/info.picocli/picocli/4.7.6/77c2cb87814b6a03d431fc856024a9f8ff605ad4/picocli-4.7.6.jar",
    "C:/Users/User/.gradle/caches/modules-2/files-2.1/com.h2database/h2/2.2.224/7bdade27d8cd197d9b5ce9dc251f41d2edc5f7ad/h2-2.2.224.jar"
)
$cp = $dependencies -join [IO.Path]::PathSeparator

Write-Host "[*] Compiling backend Java files..."
& javac -cp $cp -d $classesDir $javaFiles

Write-Host "[*] Extracting dependency libraries..."
Push-Location $tempDir
try {
    foreach ($dep in $dependencies) {
        Write-Host "  Extracting $dep..."
        & jar -xf $dep
    }
} finally {
    Pop-Location
}

Write-Host "[*] Copying DraftFlow classes..."
Copy-Item -Path (Join-Path $classesDir "*") -Destination $tempDir -Recurse -Force

Write-Host "[*] Cleaning up dependency signatures..."
# Remove security signature files to avoid SecurityException when running
Remove-Item -Path (Join-Path $tempDir "META-INF/*.SF") -ErrorAction SilentlyContinue
Remove-Item -Path (Join-Path $tempDir "META-INF/*.DSA") -ErrorAction SilentlyContinue
Remove-Item -Path (Join-Path $tempDir "META-INF/*.RSA") -ErrorAction SilentlyContinue

Write-Host "[*] Writing MANIFEST.MF..."
$manifestPath = Join-Path $tempDir "META-INF/MANIFEST.MF"
New-Item -ItemType Directory -Path (Split-Path $manifestPath) -Force | Out-Null
$manifestContent = @"
Manifest-Version: 1.0
Main-Class: com.draftflow.DraftFlow
Multi-Release: true

"@
[System.IO.File]::WriteAllText($manifestPath, $manifestContent)

Write-Host "[*] Packaging fat JAR..."
$jarOut = Join-Path $targetDir "draftflow.jar"
Remove-Item -Path $jarOut -ErrorAction SilentlyContinue
Push-Location $tempDir
try {
    & jar -cfm $jarOut META-INF/MANIFEST.MF *
} finally {
    Pop-Location
}

Write-Host "[*] Cleaning up temporary directories..."
Remove-Item -Recurse -Force $tempDir -ErrorAction SilentlyContinue

Write-Host "[OK] Executable fat JAR built successfully: $jarOut"
