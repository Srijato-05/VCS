# run-coverage.ps1
# Full Code Coverage Analysis & Metric Report Generator for DraftFlow VCS

$ErrorActionPreference = "Stop"

$projectRoot = "E:/backup 7.1.26/user/Downloads/DraftFlow"

# Classpath dependencies
$libs = @(
    "C:/Users/User/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/2.11.0/527175ca6d81050b53bdd4c457a6d6e017626b0e/gson-2.11.0.jar",
    "C:/Users/User/.gradle/caches/modules-2/files-2.1/info.picocli/picocli/4.7.6/77c2cb87814b6a03d431fc856024a9f8ff605ad4/picocli-4.7.6.jar",
    "C:/Users/User/.gradle/caches/modules-2/files-2.1/com.h2database/h2/2.2.224/7bdade27d8cd197d9b5ce9dc251f41d2edc5f7ad/h2-2.2.224.jar",
    "C:/Users/User/.gradle/caches/modules-2/files-2.1/org.junit.jupiter/junit-jupiter-api/5.10.2/fb55d6e2bce173f35fd28422e7975539621055ef/junit-jupiter-api-5.10.2.jar",
    "C:/Users/User/.gradle/caches/modules-2/files-2.1/org.junit.jupiter/junit-jupiter-params/5.10.2/359132c82a9d3fa87a325db6edd33b5fdc67a3d8/junit-jupiter-params-5.10.2.jar",
    "C:/Users/User/.gradle/caches/modules-2/files-2.1/org.junit.jupiter/junit-jupiter-engine/5.10.2/f1f8fe97bd58e85569205f071274d459c2c4f8cd/junit-jupiter-engine-5.10.2.jar",
    "C:/Users/User/.gradle/caches/modules-2/files-2.1/org.junit.platform/junit-platform-commons/1.10.2/3197154a1f0c88da46c47a9ca27611ac7ec5d797/junit-platform-commons-1.10.2.jar",
    "C:/Users/User/.gradle/caches/modules-2/files-2.1/org.junit.platform/junit-platform-engine/1.10.2/d53bb4e0ce7f211a498705783440614bfaf0df2e/junit-platform-engine-1.10.2.jar",
    "C:/Users/User/.gradle/caches/modules-2/files-2.1/org.apiguardian/apiguardian-api/1.1.2/a231e0d844d2721b0fa1b238006d15c6ded6842a/apiguardian-api-1.1.2.jar",
    "C:/Users/User/.gradle/caches/modules-2/files-2.1/org.opentest4j/opentest4j/1.3.0/152ea56b3a72f655d4fd677fc0ef2596c3dd5e6e/opentest4j-1.3.0.jar",
    "C:/Users/User/.gradle/caches/modules-2/files-2.1/org.junit.platform/junit-platform-console/1.10.2/8f206c9d7d715435f868f1bb9747e0d6e5b74619/junit-platform-console-1.10.2.jar",
    "C:/Users/User/.gradle/caches/modules-2/files-2.1/org.junit.platform/junit-platform-launcher/1.10.2/8125dd29e847ca274dd1a7a9ca54859acc284cb3/junit-platform-launcher-1.10.2.jar",
    "C:/Users/User/.gradle/caches/modules-2/files-2.1/org.jacoco/org.jacoco.core/0.8.14/5d317827447ab203bb90ecc7597850baae9c8565/org.jacoco.core-0.8.14.jar",
    "C:/Users/User/.gradle/caches/modules-2/files-2.1/org.jacoco/org.jacoco.report/0.8.14/d25b1c200c0c6e82baac3c0ddb8b9e38f13a5f6c/org.jacoco.report-0.8.14.jar",
    "C:/Users/User/.gradle/caches/modules-2/files-2.1/org.ow2.asm/asm/9.7.1/f0ed132a49244b042cd0e15702ab9f2ce3cc8436/asm-9.7.1.jar",
    "C:/Users/User/.gradle/caches/modules-2/files-2.1/org.ow2.asm/asm-tree/9.7.1/3a53139787663b139de76b627fca0084ab60d32c/asm-tree-9.7.1.jar",
    "C:/Users/User/.gradle/caches/modules-2/files-2.1/org.ow2.asm/asm-commons/9.7.1/406c6a2225cfe1819f102a161e54cc16a5c24f75/asm-commons-9.7.1.jar"
)

# Directories
$classesDir = "$projectRoot/target/classes"
$testClassesDir = "$projectRoot/target/test-classes"
$execFile = "$projectRoot/jacoco.exec"

if (!(Test-Path $classesDir)) { New-Item -ItemType Directory -Path $classesDir | Out-Null }
if (!(Test-Path $testClassesDir)) { New-Item -ItemType Directory -Path $testClassesDir | Out-Null }

# 1. Compile Main Sources
Write-Host "Compiling main Java sources..." -ForegroundColor Yellow
$mainSources = Get-ChildItem -Path "$projectRoot/src/main/java" -Filter *.java -Recurse | ForEach-Object { $_.FullName }
$mainCp = ($libs + $classesDir) -join ";"
& javac -cp $mainCp -d $classesDir $mainSources

# 2. Compile Test Sources
Write-Host "Compiling test Java sources..." -ForegroundColor Yellow
$testSources = Get-ChildItem -Path "$projectRoot/src/test/java" -Filter *.java -Recurse | ForEach-Object { $_.FullName }
$testCp = ($libs + $classesDir + $testClassesDir) -join ";"
& javac -cp $testCp -d $testClassesDir $testSources

# 3. Run JUnit tests with JaCoCo JavaAgent
Write-Host "Running JUnit test suite with JaCoCo agent instrumentation..." -ForegroundColor Green
$runCp = ($libs + $classesDir + $testClassesDir) -join ";"
$agentJar = "$projectRoot/jacocoagent.jar"

& java -javaagent:"${agentJar}=destfile=${execFile}" -cp $runCp org.junit.platform.console.ConsoleLauncher --scan-classpath

# 4. Generate & Display Detailed Coverage Metrics Report
Write-Host "`nGenerating Code Coverage Metrics Report..." -ForegroundColor Cyan
& java -cp $runCp com.draftflow.coverage.CoverageReporter $execFile $classesDir
