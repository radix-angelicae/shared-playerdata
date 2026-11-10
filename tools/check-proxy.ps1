# =============================================================================
#  Check whether the local proxy port accepts connections.
#
#  build.bat calls this BEFORE starting Gradle. gradle.properties routes ALL
#  downloads through 127.0.0.1:10808; when the proxy client is not running,
#  ForgeGradle / Loom retry dozens of artifacts and print a wall of
#  "Connection refused" stacks, which looks exactly like a hang.
#  Probing up front lets us fail fast with an actionable message instead.
#
#  Exit code 0 = reachable; 1 = connection failed or timed out.
#
#  NOTE: do not name a parameter $Host - that is a read-only PowerShell
#  automatic variable. $TargetHost is used instead.
# =============================================================================

param(
    [string]$TargetHost = '127.0.0.1',
    [int]$Port = 10808,
    [int]$TimeoutMs = 1200
)

$client = New-Object System.Net.Sockets.TcpClient
try {
    $pending = $client.BeginConnect($TargetHost, $Port, $null, $null)
    if (-not $pending.AsyncWaitHandle.WaitOne($TimeoutMs)) {
        $client.Close()
        exit 1
    }
    $client.EndConnect($pending)
    $client.Close()
    exit 0
} catch {
    try { $client.Close() } catch { }
    exit 1
}
