# Caw API Reference

Version 1, November 2026

HTTP and WebSocket interface of a Caw server. Formats and client behavior are specified in [protocol.md](protocol.md).

## Contents

1. [Conventions](#conventions)
2. [Objects](#objects)
3. [Public](#public)
4. [Account](#account)
5. [Vault](#vault)
6. [Devices](#devices)
7. [Contacts and messages](#contacts-and-messages)
8. [Calls and push](#calls-and-push)
9. [Link endpoints](#link-endpoints)
10. [Administration](#administration)
11. [WebSocket](#websocket)
12. [Limits](#limits)

## Conventions

- Request and response bodies are JSON unless stated.
- Errors return `{"error": "<message>"}` with the status code.
- Binary values are base64url without padding unless stated. Times are seconds since the Unix epoch.

Authentication:

| kind | how |
|---|---|
| session | cookie `caw_session`, or header `Authorization: Bearer <token>`. Sessions expire after 90 days without use |
| link | header `Authorization: Link <link id>.<token>`, or query parameter `link=<link id>.<token>` on WebSocket requests |
| admin | session of an administrator |

Link endpoints and `POST /api/pairings/redeem` accept requests from any origin (methods `GET`, `POST`, `DELETE`; headers `Authorization`, `Content-Type`) and do not use cookies.

Status codes:

| code | meaning |
|---|---|
| 200, 201, 204 | success |
| 400 | invalid request |
| 401 | missing or invalid session or token |
| 403 | not permitted, or incorrect credentials |
| 404 | not found |
| 409 | conflict (duplicate, limit reached, stale version) |
| 410 | link or pairing ticket unknown, revoked, used or expired |
| 412 | message does not cover all of the recipient's devices |
| 429 | rate limited; `Retry-After: 60` |

## Objects

User:

```
{ "id": 1, "username": "...", "display_name": "...", "is_admin": false, "must_change": false }
```

Mail:

```
{ "link_id": "...", "id": "...", "from_device": "...", "ttl": 86400, "created_at": 0, "expires_at": 0,
  "body": "...", "keys": [{ "d": "...", "k": "...", "w": "...", "o": "to" }], "sig": "..." }
```

Public device:

```
{ "id": "...", "sign_pub": "...", "enc_pub": "...", "enc_created_at": 0, "enc_sig": "..." }
```

Own device: a public device plus `label`, `created_at`, `last_seen` and `keys` (recent encryption public keys). `enc_pub`, `enc_created_at` and `enc_sig` are `null` before the first key upload.

Settings:

```
{ "server_name": "Caw", "registration_open": false, "ring_timeout_secs": 45,
  "message_ttl_default": 86400, "message_ttl_min": 300, "message_ttl_max": 604800,
  "max_upload_mb": 25, "device_expiry_days": 30, "pairing_code_minutes": 120 }
```

## Public

| method | path | request | response |
|---|---|---|---|
| GET | `/api/health` | | `ok` (text) |
| GET | `/api/config` | | `{server_name, registration_open, vapid_public_key, message_ttl_default, message_ttl_min, message_ttl_max, max_upload_mb}` |
| POST | `/api/auth/salt` | `{username}` | `{salt, m, t, p}` |
| POST | `/api/auth/login` | `{username, login_key}` | `{token, user}`; sets the session cookie |
| POST | `/api/auth/register` | `{username, display_name, invite, login_key, salt}` | `{token, user}`; sets the session cookie |
| GET | `/api/invites/{token}` | | `204` if the invite is usable, else `404` |
| POST | `/api/pairings/redeem` | `{ticket, peer_server, peer_account}` | `{link_id, token}` |

- `salt`: unknown usernames receive a stable salt indistinguishable from a real one.
- `login`: `403` for incorrect credentials or a disabled account.
- `register`: `invite` is optional when open registration is enabled; `403` otherwise or for an invalid invite. `409` if the username is taken.
- `redeem`: creates a link to the ticket's account; `410` if the ticket is unknown, used or expired.

## Account

Session required.

| method | path | request | response |
|---|---|---|---|
| POST | `/api/auth/logout` | | `204`; deletes the session and clears the cookie |
| GET | `/api/me` | | user |
| PATCH | `/api/me` | `{display_name}` | user |
| PUT | `/api/me/login` | `{login_key, salt, current_login_key, wrapped_key}` | user |

`PUT /api/me/login` replaces the login key and salt and clears `must_change`. `current_login_key` is required unless the account is flagged `must_change`. `wrapped_key` is optional and is stored as with `PUT /api/vault/key`. Other sessions are not affected. `403` if the current credential is incorrect.

## Vault

Session required.

| method | path | request | response |
|---|---|---|---|
| GET | `/api/vault` | | `{version, blob, wrapped_key}`; `blob` and `wrapped_key` may be `null`; `version` is `0` when no vault exists |
| PUT | `/api/vault` | `{version, blob}` | `{version}` |
| PUT | `/api/vault/key` | `{wrapped_key}` | `204` |

`PUT /api/vault` succeeds only if `version` equals the stored version, and returns the new version; otherwise `409`. The account's connections receive `vault`.

## Devices

Session required.

| method | path | request | response |
|---|---|---|---|
| GET | `/api/devices` | | array of own devices |
| POST | `/api/devices` | `{label, sign_pub}` | `{id}` |
| PUT | `/api/devices/{id}/key` | `{enc_pub, created_at, sig}` | `204` |
| DELETE | `/api/devices/{id}` | | `204` |

- `POST` enrolls a device and binds the session to it; `409` when the account has 10 devices.
- `PUT .../key` stores the encryption key and certificate and binds the session to the device; `404` for an unknown device.
- `DELETE` removes the device, deletes its sessions and closes their connections.
- Each change sends `devices` to the account's connections.

## Contacts and messages

Session required.

| method | path | request | response |
|---|---|---|---|
| POST | `/api/pairings` | | `{ticket, expires_at, account}` |
| GET | `/api/links` | | `[{id, peer_server, peer_account, created_at, last_used}]` |
| POST | `/api/links` | `{peer_server, peer_account}` | `{link_id, token, account}` |
| DELETE | `/api/links/{id}` | | `204` |
| GET | `/api/mail` | | `{now, mail: [mail]}` |
| POST | `/api/sent` | `{contact}` | `204` |
| GET | `/api/blobs/{id}` | | encrypted file (`application/octet-stream`) |

- `POST /api/links`: `409` when the account has 1000 links.
- `DELETE /api/links/{id}`: closes guest connections using the link; the account's connections receive `link_revoked`.
- `GET /api/mail`: unexpired messages delivered to all of the account's links; `now` is the server time.
- `POST /api/sent`: sends `sent {contact}` to the account's connections. `contact` is an opaque client value of at most 64 characters.
- `GET /api/blobs/{id}`: attachments of unexpired messages delivered to the account; `404` otherwise.

## Calls and push

Session required.

| method | path | request | response |
|---|---|---|---|
| GET | `/api/turn` | | `{ice_servers, ttl}` |
| POST | `/api/push` | `{endpoint, p256dh, auth}` | `204` |
| DELETE | `/api/push` | `{endpoint}` | `204` |

- `ice_servers` is an array of `{urls}` (STUN) and `{urls, username, credential}` (TURN, time-limited, valid for `ttl` seconds).
- `POST /api/push` registers an endpoint for the session. `p256dh` is a P-256 public key and `auth` a 16-byte secret (RFC 8291). Endpoints on private networks, or on hosts outside the configured allow-list, are rejected with `400`. The 10 most recent endpoints per account are kept.

## Link endpoints

Link authentication required. Served by the server of the account the link belongs to.

| method | path | request | response |
|---|---|---|---|
| GET | `/api/link/devices` | | `{account, devices: [public device]}` |
| POST | `/api/link/mail` | `{id, from_device, ttl, body, keys, sig, blobs}` | mail |
| GET | `/api/link/mail` | | `{now, mail: [mail]}` |
| POST | `/api/link/blobs` | encrypted file | `{id}` |
| GET | `/api/link/blobs/{id}` | | encrypted file |
| DELETE | `/api/link` | | `204` |
| GET | `/api/link/ws` | | WebSocket ([WebSocket](#websocket)) |

- `GET /api/link/devices`: devices with an encryption key.
- `POST /api/link/mail`: `id` is a UUID; `keys` holds 1 to 40 stanzas with `o` of `to` or `from`; `blobs` is optional and lists up to 10 blob ids uploaded through the same link. The stored lifetime is `ttl` limited to the server's bounds. `409` if the id exists in the link's mailbox; `412` if the `to` stanzas do not cover every device of the account that has an encryption key. The account's connections receive `mail`, and push endpoints a push.
- `GET /api/link/mail`: unexpired messages delivered through this link.
- `POST /api/link/blobs`: request body is the encrypted file; at most the attachment limit plus 64 bytes. `409` when the link has 20 uploads not yet attached to a message; unattached uploads are deleted after one hour.
- `GET /api/link/blobs/{id}`: files uploaded through this link.
- `DELETE /api/link`: deletes the link; the owning account's connections receive `link_removed`.

## Administration

Admin required.

| method | path | request | response |
|---|---|---|---|
| GET | `/api/admin/settings` | | settings |
| PATCH | `/api/admin/settings` | any subset of settings | settings |
| GET | `/api/admin/users` | | `[{id, username, display_name, is_admin, disabled, created_at, last_seen, online}]` |
| POST | `/api/admin/users` | `{username, display_name, login_key, salt, is_admin}` | `201` |
| PATCH | `/api/admin/users/{id}` | `{display_name, is_admin, disabled, login_key, salt}` | `204` |
| DELETE | `/api/admin/users/{id}` | | `204` |
| GET | `/api/admin/invites` | | `[{id, token, note, is_admin, max_uses, uses, expires_at, created_at, url}]` |
| POST | `/api/admin/invites` | `{note, is_admin, max_uses, expires_in_hours}` | `201` |
| DELETE | `/api/admin/invites/{id}` | | `204` |

- Settings bounds: `server_name` 1 to 64 characters; `ring_timeout_secs` 10 to 300; `60 <= message_ttl_min <= message_ttl_default <= message_ttl_max <= 2592000`; `max_upload_mb` 1 to 500; `device_expiry_days` 7 to 365; `pairing_code_minutes` 1 to 1440.
- `POST /api/admin/users`: the login key and salt derive from a temporary password; the account is flagged `must_change`. `409` if the username is taken.
- `PATCH /api/admin/users/{id}`: all fields optional. `login_key` with `salt` resets the credential and sets `must_change`. Disabling or resetting another user ends that user's sessions. Administrators cannot demote or disable themselves.
- `DELETE /api/admin/users/{id}`: administrators cannot delete themselves.
- `POST /api/admin/invites`: `max_uses` defaults to 1 and `expires_in_hours` to 168; `0` means unlimited for either.

## WebSocket

| endpoint | authentication | |
|---|---|---|
| `/ws?client=<uuid>` | session | the account's devices; cookie-authenticated requests must come from the server's own origin |
| `/api/link/ws?link=<id>.<token>&client=<uuid>` | link | a contact; can only start calls |

- Frames are JSON text, at most 64 KiB, with a `type` field.
- The server pings every 25 seconds and closes a connection that receives nothing for 70 seconds. The session or link is checked every 60 seconds.
- `client` identifies the connection across reconnects. A connection that closes during a call keeps its place for 30 seconds; reconnecting with the same `client` resumes it.

Client messages:

| type | fields | |
|---|---|---|
| `call` | `call_id` | start a call (link connections only); `call_id` is a UUID |
| `accept` | `call_id` | answer an incoming call |
| `reject` | `call_id` | decline an incoming call before it is answered |
| `hangup` | `call_id` | leave a call, or decline if not yet answered |
| `signal` | `call_id`, `to`, `data` | relay `data` to a peer in the same call; contents in [protocol.md](protocol.md#82-signaling) |
| `ping` | | |

Server events:

| type | fields | |
|---|---|---|
| `hello` | `peer_id`, `incoming: [{call_id, link_id}]`, `calls: [call_id]` | sent on connect |
| `incoming` | `call_id`, `link_id` | a contact is calling |
| `ringing` | `call_id` | the callee's devices are ringing |
| `joined` | `call_id`, `peers` | this connection joined the call |
| `peer_joined`, `peer_left` | `call_id`, `peer_id` | |
| `declined` | `call_id` | |
| `ended` | `call_id`, `reason` | `answered_elsewhere`, `declined`, `no_answer`, `cancelled`, `completed` or `left` |
| `signal` | `call_id`, `from`, `data` | |
| `mail` | mail fields | new message for the account |
| `sent` | `contact` | another device of the account sent a message |
| `vault` | `version` | the vault changed |
| `devices` | | the account's device list changed |
| `link_removed` | `link_id` | a contact removed the link |
| `link_revoked` | `link_id` | a device of the account revoked the link |
| `error` | `message`, `call_id` (optional) | |
| `pong` | | |

## Limits

| item | limit |
|---|---|
| username | 3 to 32 characters: `a-z 0-9 _ . -` |
| display name, device label | 64 characters |
| devices per account | 10 |
| links per account | 1000 |
| push endpoints per account | 10 |
| message body | 64 KiB |
| stanzas per message | 40 |
| attachments per message | 10 |
| attachment size | administrator setting (default 25 MB) |
| vault blob | 1,500,000 characters |
| wrapped key | 512 characters |
| WebSocket frame | 64 KiB |

Rate limits, per minute:

| action | limit |
|---|---|
| sign-in | 10 per username, 30 per IP address |
| `POST /api/link/mail` | 60 per link |
| `POST /api/link/blobs` | 20 per link |
| `call` | 10 per link |
| `PUT /api/vault` | 60 per account |
| `POST /api/sent` | 120 per account |
