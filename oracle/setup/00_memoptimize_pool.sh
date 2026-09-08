#!/bin/bash

# Sourced by Oracle's runUserScripts.sh. Do not exit: that would terminate the
# container entrypoint. Fast Lookup needs a fixed SGA pool and a restart after
# changing this static initialization parameter.
CURRENT_MEMOPTIMIZE_BYTES=$(sqlplus -s / as sysdba <<'SQL'
SET HEADING OFF FEEDBACK OFF PAGESIZE 0 VERIFY OFF ECHO OFF
SELECT value FROM v$parameter WHERE name = 'memoptimize_pool_size';
SQL
)
CURRENT_MEMOPTIMIZE_BYTES=$(echo "$CURRENT_MEMOPTIMIZE_BYTES" | tr -d '[:space:]')

if [ "$CURRENT_MEMOPTIMIZE_BYTES" != "268435456" ]; then
  echo "Configuring MEMOPTIMIZE_POOL_SIZE=256M; restarting Oracle once."
  sqlplus -s / as sysdba <<'SQL'
WHENEVER SQLERROR EXIT SQL.SQLCODE
ALTER SYSTEM SET MEMOPTIMIZE_POOL_SIZE = 256M SCOPE=SPFILE;
SHUTDOWN IMMEDIATE;
STARTUP;
SQL
else
  echo "MEMOPTIMIZE_POOL_SIZE already configured at 256M."
fi
