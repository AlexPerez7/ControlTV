# Control TV - servidor local que reenvia comandos al Roku (ECP, puerto 8060)
# Uso: powershell -ExecutionPolicy Bypass -File servidor.ps1 [-Puerto 8090] [-RokuIP 192.168.31.110]
param(
    [int]$Puerto = 8090,
    [string]$RokuIP = ""
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Web
$base = Split-Path -Parent $MyInvocation.MyCommand.Path
$cfgFile = Join-Path $base 'roku_ip.txt'

function Test-Roku([string]$ip) {
    try {
        $r = Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 "http://$($ip):8060/query/device-info"
        return ($r.Content -match '<vendor-name>Roku')
    } catch { return $false }
}

function Find-Roku {
    Write-Host "Buscando el Roku en la red..." -ForegroundColor Yellow
    $local = Get-NetIPAddress -AddressFamily IPv4 |
        Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254*' -and $_.PrefixLength -eq 24 } |
        Select-Object -First 1
    if (-not $local) { return $null }
    $prefix = ($local.IPAddress -split '\.')[0..2] -join '.'
    $tasks = foreach ($i in 1..254) {
        $c = New-Object System.Net.Sockets.TcpClient
        [pscustomobject]@{ ip = "$prefix.$i"; c = $c; t = $c.ConnectAsync("$prefix.$i", 8060) }
    }
    Start-Sleep -Milliseconds 2000
    $cands = $tasks | Where-Object { $_.t.Status -eq 'RanToCompletion' -and $_.c.Connected } | ForEach-Object { $_.ip }
    $tasks | ForEach-Object { $_.c.Close() }
    foreach ($ip in $cands) { if (Test-Roku $ip) { return $ip } }
    return $null
}

# --- Determinar IP del Roku ---
if (-not $RokuIP -and (Test-Path $cfgFile)) { $RokuIP = (Get-Content $cfgFile -Raw).Trim() }
if (-not $RokuIP -or -not (Test-Roku $RokuIP)) {
    $RokuIP = Find-Roku
    if (-not $RokuIP) { Write-Host "No encontre ningun Roku en la red. Revisa que este encendido y en el mismo WiFi." -ForegroundColor Red; Read-Host "Enter para salir"; exit 1 }
}
Set-Content -Path $cfgFile -Value $RokuIP -Encoding ascii
$roku = "http://$($RokuIP):8060"

$html = [IO.File]::ReadAllBytes((Join-Path $base 'index.html'))

function Send-Response($stream, [int]$code, [string]$ctype, [byte[]]$body) {
    $status = @{200='OK';204='No Content';400='Bad Request';404='Not Found';502='Bad Gateway'}[$code]
    $hdr = "HTTP/1.1 $code $status`r`nContent-Type: $ctype`r`nContent-Length: $($body.Length)`r`nCache-Control: no-store`r`nConnection: close`r`n`r`n"
    $h = [Text.Encoding]::ASCII.GetBytes($hdr)
    $stream.Write($h, 0, $h.Length)
    if ($body.Length) { $stream.Write($body, 0, $body.Length) }
}

function Roku-Post([string]$path) {
    Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 -Method Post "$roku$path" | Out-Null
}

$listener = New-Object System.Net.Sockets.TcpListener([Net.IPAddress]::Any, $Puerto)
try { $listener.Start() } catch {
    Write-Host "No pude abrir el puerto $Puerto (quiza ya esta en uso). Prueba con -Puerto 8091." -ForegroundColor Red
    Read-Host "Enter para salir"; exit 1
}

$myIPs = Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254*' } | ForEach-Object { $_.IPAddress }
Write-Host ""
Write-Host "=========================================" -ForegroundColor Cyan
Write-Host "  CONTROL TV listo  (Roku en $RokuIP)" -ForegroundColor Cyan
Write-Host "=========================================" -ForegroundColor Cyan
Write-Host "Abre en el navegador de tu celular:" -ForegroundColor White
foreach ($ip in $myIPs) { Write-Host "    http://$($ip):$Puerto" -ForegroundColor Green }
Write-Host ""
Write-Host "Deja esta ventana abierta. Ctrl+C para cerrar." -ForegroundColor DarkGray
Write-Host ""

$utf8 = [Text.Encoding]::UTF8
while ($true) {
    $client = $listener.AcceptTcpClient()
    try {
        $client.ReceiveTimeout = 3000
        $stream = $client.GetStream()
        $reader = New-Object IO.StreamReader($stream, [Text.Encoding]::ASCII, $false, 4096, $true)
        $line = $reader.ReadLine()
        if (-not $line) { continue }
        while (($l = $reader.ReadLine()) -ne $null -and $l -ne '') { }  # descartar cabeceras
        $parts = $line -split ' '
        $method = $parts[0]; $url = [Uri]("http://x" + $parts[1])
        $path = $url.AbsolutePath

        if ($path -eq '/' -or $path -eq '/index.html') {
            Send-Response $stream 200 'text/html; charset=utf-8' $html
        }
        elseif ($path -match '^/key/([A-Za-z0-9_]+)$') {
            $k = $Matches[1]
            try { Roku-Post "/keypress/$k"; Send-Response $stream 204 'text/plain' @() ; Write-Host "  tecla: $k" -ForegroundColor DarkGray }
            catch { Send-Response $stream 502 'text/plain' $utf8.GetBytes("Roku no responde") }
        }
        elseif ($path -eq '/text') {
            $q = [Web.HttpUtility]::ParseQueryString($url.Query)['t']
            if ($null -eq $q) { $q = '' }
            try {
                foreach ($ch in $q.ToCharArray()) { Roku-Post ("/keypress/Lit_" + [Uri]::EscapeDataString([string]$ch)) }
                Send-Response $stream 204 'text/plain' @()
                Write-Host "  texto: $q" -ForegroundColor DarkGray
            } catch { Send-Response $stream 502 'text/plain' $utf8.GetBytes("Roku no responde") }
        }
        elseif ($path -eq '/apps') {
            try { $r = Invoke-WebRequest -UseBasicParsing -TimeoutSec 4 "$roku/query/apps"; Send-Response $stream 200 'text/xml; charset=utf-8' $utf8.GetBytes($r.Content) }
            catch { Send-Response $stream 502 'text/plain' $utf8.GetBytes("Roku no responde") }
        }
        elseif ($path -match '^/launch/([A-Za-z0-9_]+)$') {
            try { Roku-Post "/launch/$($Matches[1])"; Send-Response $stream 204 'text/plain' @() }
            catch { Send-Response $stream 502 'text/plain' $utf8.GetBytes("Roku no responde") }
        }
        elseif ($path -match '^/icon/([A-Za-z0-9_]+)$') {
            try {
                $wc = New-Object Net.WebClient
                $bytes = $wc.DownloadData("$roku/query/icon/$($Matches[1])")
                Send-Response $stream 200 'image/png' $bytes
            } catch { Send-Response $stream 404 'text/plain' @() }
        }
        else { Send-Response $stream 404 'text/plain' $utf8.GetBytes("No encontrado") }
    } catch {
        Write-Host "  error: $($_.Exception.Message)" -ForegroundColor DarkRed
    } finally {
        $client.Close()
    }
}
