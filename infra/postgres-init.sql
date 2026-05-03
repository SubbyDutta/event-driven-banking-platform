-- Runs once at first Postgres startup (mounted to /docker-entrypoint-initdb.d/).
-- Creates the three service databases and their owners inside the shared cluster.

CREATE ROLE findoc WITH LOGIN PASSWORD 'findoc';
CREATE DATABASE findoc OWNER findoc;

CREATE ROLE subby WITH LOGIN PASSWORD 'subby';
CREATE DATABASE subbybank OWNER subby;

CREATE ROLE subbyloan WITH LOGIN PASSWORD 'subbyloan';
CREATE DATABASE subby_loan OWNER subbyloan;

-- Let each role manage its own database end-to-end (schemas, extensions, etc.).
GRANT ALL PRIVILEGES ON DATABASE findoc TO findoc;
GRANT ALL PRIVILEGES ON DATABASE subbybank TO subby;
GRANT ALL PRIVILEGES ON DATABASE subby_loan TO subbyloan;
