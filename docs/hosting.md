# Hosting

Caw runs with Docker Compose: the Caw server, coturn for call relaying, and optionally Caddy for HTTPS.

## Setup

Copy the matching example to `.env` and fill in the values:

| file | for |
|---|---|
| `examples/public-server.env` | server with its own public IP; the bundled Caddy provides HTTPS |
| `examples/behind-router.env` | home or office server behind a router with port forwards; Caddy provides HTTPS |
| `examples/existing-proxy.env` | behind a reverse proxy you already run |
| `examples/local.env` | local testing and development |

```sh
cp examples/public-server.env .env
docker compose up -d --build
docker compose logs caw | grep invite   # one-time link to create the first administrator
```

Browsers allow microphone access only over HTTPS, except on localhost.

To update: `git pull && docker compose up -d --build`.

## Ports

| port | service |
|---|---|
| 80, 443/tcp, 443/udp | Caddy |
| 3478/tcp+udp | coturn (`CAW_TURN_PORT`) |
| 49160-49200/udp | coturn relay range (`CAW_TURN_MIN_PORT`-`CAW_TURN_MAX_PORT`) |
| 127.0.0.1:8080 | Caw |

coturn uses host networking, so its ports are subject to the host firewall. Behind NAT, forward the TURN port and relay range to the host and set `CAW_TURN_EXTERNAL_IP`.

## Configuration

Environment variables in `.env`:

| variable | default | |
|---|---|---|
| `CAW_PUBLIC_URL` | `http://localhost:8080` | public address; used for invite links and the WebSocket origin check |
| `COMPOSE_PROFILES` | - | `caddy` runs the bundled Caddy for HTTPS |
| `CAW_DOMAIN` | - | domain Caddy obtains the certificate for |
| `CAW_LISTEN`, `CAW_PORT` | `127.0.0.1`, `8080` | where Caw's port is published for a proxy |
| `CAW_TURN_SECRET` | - | shared with coturn `static-auth-secret` |
| `CAW_TURN_URLS` | - | comma separated |
| `CAW_STUN_URLS` | - | comma separated |
| `CAW_TURN_TTL` | `43200` | TURN credential lifetime, seconds |
| `CAW_TURN_REALM` | - | coturn realm, usually the domain |
| `CAW_TURN_EXTERNAL_IP` | - | behind NAT: `PUBLIC_IP` or `PUBLIC_IP/PRIVATE_IP` |
| `CAW_TURN_PORT` | `3478` | coturn listening port; keep `CAW_TURN_URLS` and `CAW_STUN_URLS` in step |
| `CAW_TURN_MIN_PORT`, `CAW_TURN_MAX_PORT` | `49160`, `49200` | coturn relay range |
| `CAW_PUSH_ALLOWED_HOSTS` | - | accept push endpoints only on these hosts, comma separated |
| `CAW_PUSH_ALLOW_PRIVATE` | `false` | allow push endpoints on private IP addresses |
| `CAW_SECRET` | - | encrypts stored vaults; overrides `data/caw/secret`. Changing it discards stored vaults |
| `CAW_DB` | `/data/caw.db` | |
| `CAW_BIND` | `0.0.0.0:8080` | |

The admin settings page sets the server name, open registration, ring timeout, message expiry bounds, device expiry, pairing code expiry and the attachment limit.

## Data and backups

State is stored in `./data/`: the SQLite database and attachments in `data/caw/`, Caddy certificates in `data/caddy/`.

`data/caw/secret` is generated on first start and encrypts the stored contact vaults. Back it up separately from `caw.db`, or set `CAW_SECRET` instead.
