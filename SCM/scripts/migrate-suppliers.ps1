param(
    [string]$ConfigPath = '',
    [string]$JavaHome = 'C:\DevTools\java\openlogic-openjdk-17.0.14+7-windows-x64'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$env:SCM_CONFIG = if ($ConfigPath) { [IO.Path]::GetFullPath($ConfigPath) } else { Join-Path $projectRoot '.local\scm.properties' }
if (!(Test-Path -LiteralPath $env:SCM_CONFIG)) { throw 'Database configuration file is missing.' }
$classPath = (Join-Path $projectRoot 'target\classes') + ';' + (Join-Path $projectRoot 'target\scm\WEB-INF\lib\*')
if (!(Test-Path -LiteralPath (Join-Path $projectRoot 'target\classes\kr\bujobank\scm\SupplierMigration.class'))) { throw 'Build the project with mvn package before migrating.' }
& "$JavaHome\bin\java.exe" -cp $classPath kr.bujobank.scm.SupplierMigration
if ($LASTEXITCODE -ne 0) { throw 'Supplier migration failed. Keep the application stopped and inspect the database before retrying.' }