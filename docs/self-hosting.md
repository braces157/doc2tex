# Self-hosting doc2TeX as a public portfolio demo

This guide runs `doc2TeX` inside an unprivileged Linux Docker container alongside a `cloudflared` sidecar so visitors can reach your demo over HTTPS (`https://doc2tex.tech`) without opening router ports.

Default local execution (`scripts/start.ps1` / `scripts/start.sh` / `./mvnw spring-boot:run`) remains unchanged: bound to `127.0.0.1`, localhost-only, with demo mode disabled.

## What demo mode enables

When `DOC2TEX_DEMO_MODE=true` and `DOC2TEX_ALLOWED_HOSTS` are configured in `docker-compose.yml`:

- **Host & origin allowlisting:** Only requests targeting `127.0.0.1`/`localhost` or `DOC2TEX_ALLOWED_HOSTS` are accepted. Top-level cross-site `GET` navigations (clicking your link from a portfolio or GitHub) are allowed, while cross-site API requests and mismatched `Origin` headers are rejected with `403`.
- **Per-browser session isolation:** Each browser receives an `HttpOnly`, `SameSite=Lax` cookie (`doc2tex_demo_session`). Visitors only see their own conversions in recents and `All documents`, and requests targeting another session's job ID return `404`.
- **Automatic cleanup & capacity cap:** Idle jobs expire automatically after `DOC2TEX_DEMO_RETENTION_MINUTES` (default `60`), and total stored jobs are capped at `DOC2TEX_DEMO_MAX_JOBS` (default `50`, evicting the oldest idle jobs first).
- **Per-IP and per-session rate limits:** Uploads/layout-copies and PDF preview builds are rate-limited per visitor IP (`CF-Connecting-IP` when `DOC2TEX_TRUST_PROXY_IP=true`).
- **Container isolation:** Runs as a non-root user with `cap_drop: ALL`, `no-new-privileges`, `2 GB` RAM limit, `pids_limit: 128`, and `DOC2TEX_AI_LAYOUT_ENABLED=false` (LibreOffice disabled for public untrusted uploads).

## Prerequisites

1. **Docker Desktop** running with the WSL2 backend.
2. **Domain on Cloudflare DNS** (`doc2tex.tech` nameservers pointed to Cloudflare and status **Active**).
3. **Cloudflare Tunnel** created in **Zero Trust -> Networks -> Tunnels**:
   - Public hostname: `doc2tex.tech` (and optionally `www.doc2tex.tech`) -> Service: `http://doc2tex:8080`.
   - Copy the tunnel token (`--token eyJ...`).

## Quick start (Windows PowerShell)

1. Copy `.env.example` to `.env` and paste your token into `CLOUDFLARE_TUNNEL_TOKEN`:

   ```powershell
   Copy-Item .env.example .env
   ```

2. Build and start both containers:

   ```powershell
   docker compose up -d --build
   ```

3. Check container health:

   ```powershell
   docker compose ps
   docker compose logs -f doc2tex cloudflared
   ```

4. To test the container locally without starting `cloudflared`:

   ```powershell
   docker compose up -d --build doc2tex
   ```

   Then open `http://127.0.0.1:8080`.

5. To stop everything:

   ```powershell
   docker compose down
   ```

## Windows host tips

- **(Optional) Cap WSL2 memory** on a 16 GB PC so container workloads never starve Windows. Create or edit `%USERPROFILE%\.wslconfig`:

  ```ini
  [wsl2]
  memory=6GB
  ```

  Then run `wsl --shutdown` and restart Docker Desktop.
- **Keep the PC awake:** Set Windows Power & Sleep to **Never** while plugged in when hosting the demo.
