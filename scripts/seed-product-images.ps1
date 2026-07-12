<#
.SYNOPSIS
    Seeds product images for the 5 demo products created by V9__seed_dev_data.sql.

.DESCRIPTION
    V9 seeds 5 products with zero product_images rows, so primaryImageUrl is
    null and the storefront/admin show "No image" placeholders. This script
    uploads a matching PNG (from scripts/seed-images/) for each product via
    POST /api/v1/product/{productId}/images (multipart, admin/staff JWT
    required) and marks it primary in the same call.

    IDEMPOTENT: for each product it first calls GET .../images and skips the
    upload if the product already has a PRIMARY image (not just any image —
    stray non-primary images can be left behind by other test suites, e.g.
    the admin E2E product-CRUD spec, and must not block seeding a real
    primary). Safe to re-run.

    Re-run this after a postgres/minio volume wipe (docker compose down -v)
    or any time the 5 demo products come back imageless.

.PARAMETER ApiBase
    Base URL of the backend API. Defaults to http://localhost:8080/api/v1.

.EXAMPLE
    pwsh -File scripts/seed-product-images.ps1
#>

param(
    [string]$ApiBase = "http://localhost:8080/api/v1"
)

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ImagesDir = Join-Path $ScriptDir "seed-images"

# productId -> image filename (relative to seed-images/), from V9 seed data.
$Products = [ordered]@{
    "10000000-0000-0000-0000-000000000001" = "galaxy-s24-ultra.png"    # Galaxy S24 Ultra
    "10000000-0000-0000-0000-000000000002" = "iphone-16-pro.png"       # iPhone 16 Pro
    "10000000-0000-0000-0000-000000000003" = "macbook-pro-14.png"      # MacBook Pro 14"
    "10000000-0000-0000-0000-000000000004" = "classic-crew-tee.png"    # Classic Crew Tee
    "10000000-0000-0000-0000-000000000005" = "slim-fit-jeans.png"      # Slim Fit Jeans
}

Write-Host "Logging in as admin_test..."
$loginBody = @{ username = "admin_test"; password = "AdminPass123!" } | ConvertTo-Json
$tokenResponse = Invoke-RestMethod -Method Post -Uri "$ApiBase/auth/login" `
    -ContentType "application/json" -Body $loginBody
$accessToken = $tokenResponse.accessToken
if (-not $accessToken) {
    throw "Login did not return an accessToken. Response: $($tokenResponse | ConvertTo-Json -Compress)"
}
$authHeader = @{ Authorization = "Bearer $accessToken" }

foreach ($productId in $Products.Keys) {
    $imageFile = Join-Path $ImagesDir $Products[$productId]
    if (-not (Test-Path $imageFile)) {
        Write-Host "[$productId] SKIP - image file not found: $imageFile"
        continue
    }

    # Idempotency check: skip only if a PRIMARY image already exists. Stray
    # non-primary images (e.g. leftover from admin E2E CRUD tests) must not
    # block seeding a real primary image for the storefront.
    $existing = Invoke-RestMethod -Method Get -Uri "$ApiBase/product/$productId/images"
    $existingImages = $existing.data
    $hasPrimary = $existingImages | Where-Object { $_.primary -eq $true }
    if ($hasPrimary) {
        Write-Host "[$productId] SKIPPED (already has a primary image)"
        continue
    }

    # Multipart upload via curl.exe (Windows ships curl.exe; more reliable than
    # Invoke-RestMethod -Form across PowerShell 5.1/7 for multipart + auth headers).
    $result = & curl.exe -s -X POST "$ApiBase/product/$productId/images" `
        -H "Authorization: Bearer $accessToken" `
        -F "file=@$imageFile;type=image/png" `
        -F "isPrimary=true"

    if ($result -match '"storageKey"') {
        Write-Host "[$productId] UPLOADED - $($Products[$productId]) (set as primary)"
    } else {
        Write-Host "[$productId] FAILED - response: $result"
    }
}

Write-Host "Done."
