<#
.SYNOPSIS
    Rotates the Keycloak client secret of a CBRMS platform or the monitoring client (AT17).

.DESCRIPTION
    1. Signs in to the Keycloak admin API (master realm) as an administrator.
    2. Generates a new secret for the client. THE OLD SECRET STOPS WORKING IMMEDIATELY.
    3. Proves the new secret works: requests a client-credentials token and checks it is issued for CBRMS.
    4. Shows the new secret once, or writes it to -OutFile, for hand-over to the platform team.

    Keycloak records the change as an admin event (who, when, which client) without the secret itself.
    Agree a time with the platform team first: their calls fail from step 2 until they load the new secret.

.EXAMPLE
    .\sso\Rotate-ClientSecret.ps1 -Client lims

.EXAMPLE
    .\sso\Rotate-ClientSecret.ps1 -Client monitoring -KeycloakUrl https://sso.tirdo.go.tz -OutFile D:\handover\monitoring.secret

.EXAMPLE
    .\sso\Rotate-ClientSecret.ps1 -Client ciap -WhatIf      # checks access and the client, changes nothing
#>
[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('lims', 'ciap', 'teltp', 'isicms', 'monitoring')]
    [string] $Client,

    [string] $KeycloakUrl = 'http://localhost:8190',
    [string] $Realm = 'tirdo',
    [string] $AdminUser = 'admin',
    [System.Security.SecureString] $AdminPassword,
    [string] $Audience = 'cbrms',
    [string] $OutFile
)

$ErrorActionPreference = 'Stop'
$KeycloakUrl = $KeycloakUrl.TrimEnd('/')

function ConvertTo-PlainText([System.Security.SecureString] $s) {
    $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($s)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
}

function Get-JwtClaims([string] $jwt) {
    $part = $jwt.Split('.')[1].Replace('-', '+').Replace('_', '/')
    switch ($part.Length % 4) { 2 { $part += '==' } 3 { $part += '=' } }
    return [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($part)) | ConvertFrom-Json
}

if (-not $AdminPassword) {
    $AdminPassword = Read-Host -AsSecureString "Keycloak administrator password for '$AdminUser'"
}

# 1. Administrator token
try {
    $admin = Invoke-RestMethod -Method Post -Uri "$KeycloakUrl/realms/master/protocol/openid-connect/token" `
        -ContentType 'application/x-www-form-urlencoded' `
        -Body @{ grant_type = 'password'; client_id = 'admin-cli'; username = $AdminUser; password = (ConvertTo-PlainText $AdminPassword) }
} catch {
    throw "Could not sign in to Keycloak at $KeycloakUrl as '$AdminUser'. Check the address, user name and password. ($($_.Exception.Message))"
}
$headers = @{ Authorization = "Bearer $($admin.access_token)" }
$api = "$KeycloakUrl/admin/realms/$Realm"

# The client must exist and be a confidential client with a service account
$found = @(Invoke-RestMethod -Uri "$api/clients?clientId=$Client" -Headers $headers)
if ($found.Count -ne 1) { throw "Client '$Client' was not found in realm '$Realm'." }
$c = $found[0]
if ($c.publicClient -or -not $c.serviceAccountsEnabled -or $c.clientAuthenticatorType -ne 'client-secret') {
    throw "Client '$Client' is not a confidential client with a service account and a client secret; nothing to rotate."
}
Write-Host "Client '$Client' found in realm '$Realm' at $KeycloakUrl."

if (-not $PSCmdlet.ShouldProcess("client '$Client' in realm '$Realm'", 'Generate a new secret (the current secret stops working at once)')) {
    return
}

# 2. New secret
$new = (Invoke-RestMethod -Method Post -Uri "$api/clients/$($c.id)/client-secret" -Headers $headers).value
if ([string]::IsNullOrEmpty($new)) { throw 'Keycloak did not return a new secret.' }

# 3. Prove it works and the token is meant for CBRMS
$token = Invoke-RestMethod -Method Post -Uri "$KeycloakUrl/realms/$Realm/protocol/openid-connect/token" `
    -ContentType 'application/x-www-form-urlencoded' `
    -Body @{ grant_type = 'client_credentials'; client_id = $Client; client_secret = $new }
$claims = Get-JwtClaims $token.access_token
if (@($claims.aud) -notcontains $Audience) {
    Write-Warning "The new secret works, but the token audience is '$($claims.aud -join ', ')', not '$Audience'. CBRMS will refuse it; check the client's audience mapper."
} else {
    Write-Host "New secret verified: Keycloak issues '$Client' tokens for '$Audience'." -ForegroundColor Green
}

# 4. Hand-over
if ($OutFile) {
    Set-Content -Path $OutFile -Value $new -NoNewline -Encoding ascii
    Write-Host "New secret written to $OutFile. Hand it over securely, then delete the file."
} else {
    Write-Host ''
    Write-Host "New secret for '$Client' (shown once; it is not stored by this script):" -ForegroundColor Yellow
    Write-Host "    $new"
    Write-Host ''
}
if ($Client -eq 'monitoring') {
    Write-Host 'Next: put the new secret in Prometheus (client_secret_file in production) and reload Prometheus.'
} else {
    Write-Host "Next: the $($Client.ToUpper()) team loads the new secret; their calls to CBRMS fail until they do."
}
