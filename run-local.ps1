# =====================================================================
# Chạy Morphia 3D Backend local với DB trên Railway
#
# Bước 1: Tạo file .env.local từ .env.example và điền thông tin Railway
# Bước 2: Chạy script này trên PowerShell:
#           .\run-local.ps1
#
# Script sẽ:
#   1. Nạp biến môi trường từ .env.local
#   2. Chạy Flyway migration lên DB Railway (tạo bảng cần thiết)
#   3. Khởi động Spring Boot local (kết nối Railway DB)
# =====================================================================
$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$envFile = Join-Path $scriptDir ".env.local"

if (-not (Test-Path $envFile)) {
    Write-Error "Không tìm thấy file $envFile. Hãy tạo từ .env.example và điền thông tin Railway."
    exit 1
}

# Nạp biến môi trường từ .env.local
Get-Content $envFile | Where-Object { $_.Trim() -match '^([A-Z][A-Z0-9_]*)=(.*)$' } | ForEach-Object {
    if ($_ -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
        $name  = $matches[1]
        $value = $matches[2]
        # Loại bỏ ngoặc kép nếu có
        if ($value -match '^"(.*)"$') { $value = $matches[1] }
        if ($value -match "^\'(.*)\'$") { $value = $matches[1] }
        # Set biến môi trường process (cú pháp PowerShell an toàn)
        Set-Item -Path "Env:$name" -Value $value
    }
}

# Kiểm tra biến bắt buộc
$required = @("DB_URL", "DB_PASSWORD", "JWT_SECRET")
$missing = @()
foreach ($r in $required) {
    if ([string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable($r))) {
        $missing += $r
    }
}
if ($missing.Count -gt 0) {
    $missingList = $missing -join ', '
    Write-Error ("Thiếu biến môi trường trong " + $envFile + ": " + $missingList)
    exit 1
}

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  DB_URL     : $env:DB_URL" -ForegroundColor Cyan
Write-Host "  DB_PASSWORD: ***" -ForegroundColor Cyan
Write-Host "  JWT_SECRET : ***" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# Bước 1: Chạy Flyway migration lên DB Railway
Write-Host "`n>> Đang chạy Flyway migration lên DB Railway..." -ForegroundColor Cyan
& ./mvnw.cmd flyway:migrate `
    "-Dflyway.url=$env:DB_URL" `
    "-Dflyway.user=postgres" `
    "-Dflyway.password=$env:DB_PASSWORD" `
    "-Dflyway.locations=filesystem:src/main/resources/db/migration" `
    "-Dflyway.baselineOnMigrate=false" `
    "-Dflyway.cleanDisabled=true"

if ($LASTEXITCODE -ne 0) {
    Write-Error "Flyway migration thất bại. Vui lòng kiểm tra DB_URL, DB_PASSWORD và kết nối mạng đến Railway."
    exit 1
}

Write-Host ">> Flyway migration hoàn tất." -ForegroundColor Green

# Bước 2: Khởi động Spring Boot local
Write-Host "`n>> Đang khởi động Spring Boot local (port 8080)..." -ForegroundColor Cyan
& ./mvnw.cmd spring-boot:run `
    "-Dspring-boot.run.arguments=--server.port=8080 --spring.profiles.active=local --spring.flyway.enabled=false --spring.jpa.hibernate.ddl-auto=validate"
