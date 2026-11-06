#!/usr/bin/env bash
# US-1.4 load-balancer simulation: start one apiorch node against the simulation's SQL Server.
#   start-node.sh <instance-id> <api-port> <management-port> <seed true|false>
# Needs MSSQL_SA_PASSWORD (the password the simulation's SQL Server container was started with) and the built jar.
# The local profile keeps dev security and the demo seed; only the database and ports change.
set -euo pipefail
id="$1"; port="$2"; mgmt="$3"; seed="$4"
cd "$(dirname "$0")/../.."
# a copy per node, so a running simulation never locks the jar the build repackages
mkdir -p target/lb-sim
cp target/apiorch-service-0.1.0-SNAPSHOT.jar "target/lb-sim/$id.jar"
exec java -jar "target/lb-sim/$id.jar" \
  --spring.profiles.active=local \
  --apiorch.instance-id="$id" \
  --server.port="$port" \
  --management.server.port="$mgmt" \
  --apiorch.seed.enabled="$seed" \
  --spring.datasource.url="jdbc:sqlserver://localhost:14330;encrypt=false;trustServerCertificate=true" \
  --spring.datasource.username=sa \
  --spring.datasource.password="${MSSQL_SA_PASSWORD:?set MSSQL_SA_PASSWORD}" \
  --server.tomcat.remoteip.internal-proxies="${APIORCH_TRUSTED_PROXIES:-127\\.0\\.0\\.1|0:0:0:0:0:0:0:1}" \
  --management.endpoint.shutdown.enabled=true \
  --spring.jmx.enabled=true \
  --management.endpoints.jmx.exposure.include=shutdown
