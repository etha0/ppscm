param(
    [string]$TomcatHome = 'C:\DevTools\apache-tomcat-8.5.82_64bit',
    [string]$JavaHome = 'C:\DevTools\java\openlogic-openjdk-17.0.14+7-windows-x64',
    [int]$Port = 8087,
    [string]$ConfigPath = '',
    [string]$RuntimeName = '.preview'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$previewRoot = Join-Path $projectRoot $RuntimeName
$configFile = if ($ConfigPath) { [IO.Path]::GetFullPath($ConfigPath) } else { Join-Path $projectRoot '.local\scm.properties' }
if (!(Test-Path -LiteralPath $configFile)) { throw 'Create .local/scm.properties with the database settings first.' }
$env:SCM_CONFIG = $configFile
$war = Join-Path $projectRoot 'target\scm.war'
if (!(Test-Path -LiteralPath $war)) { throw 'Build target/scm.war with Maven first.' }
if (!(Test-Path -LiteralPath "$TomcatHome\bin\bootstrap.jar")) { throw 'Specify a Tomcat 8.5 or 9 installation using -TomcatHome.' }
$portProbe = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Loopback, $Port)
try { $portProbe.Start() } catch { throw "Port $Port is already in use. Stop the existing preview or select another port." } finally { $portProbe.Stop() }
foreach ($dir in @('conf','logs','temp','work','webapps')) { New-Item -ItemType Directory -Force -Path (Join-Path $previewRoot $dir) | Out-Null }
Copy-Item -Path "$TomcatHome\conf\*" -Destination "$previewRoot\conf" -Force
# Use a context descriptor pointing at the freshly built exploded WAR.
New-Item -ItemType Directory -Force -Path "$previewRoot\conf\Catalina\localhost" | Out-Null
$webRoot = (Join-Path $projectRoot 'target\scm').Replace('\','/')
$context = '<Context docBase="' + [System.Security.SecurityElement]::Escape($webRoot) + '"><CookieProcessor sameSiteCookies="lax" /></Context>'
[IO.File]::WriteAllText("$previewRoot\conf\Catalina\localhost\scm.xml", $context)
$serverXml = '<Server port="-1"><Service name="Catalina"><Connector address="127.0.0.1" port="' + $Port + '" protocol="HTTP/1.1" URIEncoding="UTF-8"/><Engine name="Catalina" defaultHost="localhost"><Host name="localhost" appBase="webapps" autoDeploy="false"/></Engine></Service></Server>'
[IO.File]::WriteAllText("$previewRoot\conf\server.xml", $serverXml)
$javaArgs = @("`"-Dcatalina.home=$TomcatHome`"", "`"-Dcatalina.base=$previewRoot`"", "`"-Djava.io.tmpdir=$previewRoot\temp`"", '-cp', "`"$TomcatHome\bin\bootstrap.jar;$TomcatHome\bin\tomcat-juli.jar`"", 'org.apache.catalina.startup.Bootstrap', 'start')
$process = Start-Process -FilePath "$JavaHome\bin\java.exe" -ArgumentList $javaArgs -WindowStyle Hidden -PassThru -RedirectStandardOutput "$previewRoot\stdout.log" -RedirectStandardError "$previewRoot\stderr.log"
$process.Id | Set-Content "$previewRoot\server.pid"
Write-Output "Preview starting: http://localhost:$Port/scm/app/login (PID $($process.Id))"
Write-Output "Logs: $previewRoot\stderr.log"
