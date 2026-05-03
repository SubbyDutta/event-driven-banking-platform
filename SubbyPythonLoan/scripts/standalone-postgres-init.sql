-- Standalone-only bootstrap. The monorepo runs infra/postgres-init.sql instead.
CREATE ROLE subbyloan WITH LOGIN PASSWORD 'subbyloan';
CREATE DATABASE subby_loan OWNER subbyloan;
GRANT ALL PRIVILEGES ON DATABASE subby_loan TO subbyloan;
