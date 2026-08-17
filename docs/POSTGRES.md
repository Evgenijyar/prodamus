# PostgreSQL bootstrap

Defaults used by `application.properties`:

- host: `45.11.92.142`
- port: `5433`
- database: `prodamus`
- role: `prodamus_app`
- password: `Pdm_7Jq4wL9x2Nf8cR5v`

Run on the PostgreSQL server as a user with PostgreSQL superuser rights:

```bash
sudo -u postgres psql -p 5433 <<'SQL'
SELECT 'CREATE ROLE prodamus_app LOGIN PASSWORD ''Pdm_7Jq4wL9x2Nf8cR5v'''
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'prodamus_app') \gexec
ALTER ROLE prodamus_app WITH LOGIN PASSWORD 'Pdm_7Jq4wL9x2Nf8cR5v';

SELECT 'CREATE DATABASE prodamus OWNER prodamus_app'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'prodamus') \gexec
ALTER DATABASE prodamus OWNER TO prodamus_app;

\connect prodamus
GRANT ALL ON SCHEMA public TO prodamus_app;
SQL
```

Flyway creates/validates the application schema automatically on first startup.
