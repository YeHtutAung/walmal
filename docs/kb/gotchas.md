# gotchas.md — walmal Known Pitfalls

## Stale JAR Pattern

**Symptom:** config changes (rate limits, CORS, profile marker) appear to have no effect after restart.
**Cause:** the running JAR packages `application-test.yml` at build time; working-tree edits are invisible to it.
**Fix:** rebuild the JAR — command and details in `docs/kb/testing.md` (Stale JAR Rule).

## Maven `-pl` Without `-am`

Running `./mvnw -pl <module> test` without `-am` compiles against stale `walmal-common` in `~/.m2`, producing phantom "does not override" or "symbol not found" errors. If the module compiles but references a NEW walmal-common class at runtime, the symptom is instead a phantom `NoClassDefFoundError` in otherwise-green tests (bit the global-search feature: `LikePatterns` "missing" in walmal-order until `-am` was added). Always add `-am`.

## Docker Desktop / WSL (environment note — this machine only)

- Docker WSL data lives on C: (`C:\wsl` for Ubuntu, `C:\docker\DockerDesktopWSL` for docker-desktop). Old D: copy at `D:\docker\DockerDesktopWSL` is a backup.
- If engine dies: `wsl --shutdown` → start Docker Desktop → `docker compose up -d --wait`.
- Compose health flags can be unreliable after a crash; use `pg_isready` directly to confirm Postgres is up.
- `.wslconfig`: `memory=8GB`, no `autoMemoryReclaim` (incompatible with Docker Desktop — causes loop-device I/O errors).

## Testcontainers + Docker 29.x

Integration tests fail against Docker Engine 29.x — workaround flag and details in `docs/kb/testing.md` (Testcontainers Workaround).

## k6 Performance Tests (environment note)

- k6 v2.0.0 installed via winget; not in Cygwin PATH — run via PowerShell with a refreshed PATH.
- Use `--summary-export` flag (not `--report-file`, removed in k6 v0.48+).
- For load testing, start the backend with elevated rate limits:
  `-Dwalmal.rate-limit.unauthenticated-limit=3000 -Dwalmal.rate-limit.authenticated-limit=2000`
- After stock-depletion tests, reset inventory:
  `docker exec walmal-postgres psql -U walmal -d walmal -c "UPDATE inventory_stock SET available_quantity=500 WHERE variant_id='20000000-0000-0000-0000-000000000001' AND location_id='a0000000-0000-0000-0000-000000000001'"`
- Spring returns 404 for non-existent username (not 401) — k6 auth test must expect this.
- Spring refresh tokens are single-use; do not share a refresh token across k6 VUs.
- `http_req_failed` metric: `passes` = failed requests, `fails` = successful — inverted from intuition.
- For requests where 4xx is expected (e.g. transfer 409), use `responseCallback: http.expectedStatuses(204, 409)`.

## Cygwin Shell Caveats (environment note — this machine only)

- `!` in curl `-d '...'` bodies is rewritten to `\!` by bash history expansion → "Malformed request body". Write JSON to a temp file and use `--data-binary @"C:/path/file.json"`.
- `/E`-style Windows flags become `E:/` paths in Cygwin; `cmd /c` also breaks. Put the command in a `.cmd` file and invoke via `powershell -NoProfile -Command "& 'C:/path/file.cmd'"`.
- `/tmp` differs between Windows Python and Cygwin tools; use `C:/Users/.../AppData/Local/Temp` for cross-tool temp files.
