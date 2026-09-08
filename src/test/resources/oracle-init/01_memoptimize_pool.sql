-- Runs as SYS via /container-entrypoint-initdb.d/ (gvenzl image's own init
-- mechanism), during the container's first startup only -- see
-- oracle/setup/00_memoptimize_pool.sh for the equivalent against the
-- official image used by compose.yaml. MEMOPTIMIZE_POOL_SIZE is a static
-- parameter: without this, projection_admin_pkg.cutover's
-- DBMS_MEMOPTIMIZE.POPULATE call fails outright with ORA-62138
-- ("MEMOPTIMIZE memory area does not exist"), not just "never populates"
-- like the diagnosed dev-environment finding in docs/testes.md Seção 2 --
-- a different, harder failure this test setup has to avoid, not reproduce.
ALTER SYSTEM SET MEMOPTIMIZE_POOL_SIZE = 256M SCOPE=SPFILE;
SHUTDOWN IMMEDIATE;
STARTUP;
