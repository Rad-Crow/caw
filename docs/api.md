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
  "max_upload_mb": 25, "device_expiry_days": 30, "pairing_code_minutes": 120,
  "pairing_request_hours": 24, "max_group_members": 10 }
```

## Public

| method | path | request | response |
|---|---|---|---|
| GET | `/api/health` | | `ok` (text) |
| GET | `/api/config` | | `{server_name, registration_open, vapid_public_key, message_ttl_default, message_ttl_min, message_ttl_max, max_upload_mb, max_group_members, pairing_request_hours}` |
| POST | `/api/auth/salt` | `{username}` | `{salt, m, t, p}` |
| POST | `/api/auth/login` | `{username, login_key}` | `{token, user}`; sets the session cookie |
| POST | `/api/auth/register` | `{username, display_name, invite, login_key, salt}` | `{token, user}`; sets the session cookie |
| GET | `/api/invites/{token}` | | `204` if the invite is usable, else `404` |
| POST | `/api/pairings/redeem` | `{ticket, wait, request}` | `{link_id, token, expires_at}` |

- `salt`: unknown usernames receive a stable salt indistinguishable from a real one.
- `login`: `403` for incorrect credentials or a disabled account.
- `register`: `invite` is optional when open registration is enabled; `403` otherwise or for an invalid invite. `409` if the username is taken.
- `redeem`: creates a pending link to the ticket's account (a pairing request), whose connections receive `requests` and whose push endpoints a `request` push. `wait` is the request's lifetime in seconds, limited to 60 and the `pairing_request_hours` setting; `request` is optional base64url of at most 1024 characters, returned to the owner in `GET /api/links`. `410` if the ticket is unknown, used or expired.

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
| GET | `/api/links` | | `[{id, kind, peer_server, peer_account, created_at, last_used, state, expires_at, request}]` |
| POST | `/api/links` | `{peer_server, peer_account}` or `{kind: "group", gen, token}` | `{link_id, token, account}` |
| DELETE | `/api/links/{id}` | | `204` |
| PUT | `/api/links/{id}/token` | `{gen, token}` | `204` |
| POST | `/api/links/{id}/accept` | | `204` |
| POST | `/api/links/{id}/close` | | `204` |
| POST | `/api/links/{id}/mail` | as `POST /api/link/mail` | mail |
| POST | `/api/links/{id}/blobs` | encrypted file | `{id}` |
| GET | `/api/mail` | | `{now, mail: [mail]}` |
| POST | `/api/sent` | `{contact}` | `204` |
| GET | `/api/blobs/{id}` | | encrypted file (`application/octet-stream`) |

- `POST /api/links`: `kind` is `contact` (default) or `group`. A group link's `token` is chosen by the client (32 bytes, base64url) and its peer fields are empty. `409` when the account has 1000 links.
- `PUT /api/links/{id}/token`: group links only. A greater `gen` replaces the token; the previous token remains valid for 24 hours. The same `gen` and token again returns `204`; otherwise `409` if `gen` is not greater.
- `POST /api/links/{id}/mail`, `POST /api/links/{id}/blobs`: the owner's copy and attachments for a group link, as the link endpoints below. The account's connections receive `mail`; no push is sent.
- `GET /api/links`: `state` is `pending`, `accepted` or `open` (closed links are not listed); `expires_at` and `request` are present only on pairing requests. A redeemed link's peer fields are empty.
- `POST /api/links/{id}/close`: closes a group link when its owner leaves the group. Its mail and attachments are deleted; the link refuses mail (`403`) but answers `GET /api/link` and `GET /api/link/devices` until the maximum message lifetime has passed, then is deleted. It no longer appears in `GET /api/links`, and the account's connections receive `link_revoked`. `404` if no group link of the account has that id.
- `POST /api/links/{id}/accept`: accepts a pending link; the account's connections receive `requests`. `410` if the link is gone or expired.
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
- `POST /api/push` registers the session's endpoint, replacing any earlier one for that session. `p256dh` is a P-256 public key and `auth` a 16-byte secret (RFC 8291). Endpoints on private networks, or on hosts outside the configured allow-list, are rejected with `400`. The 10 most recent endpoints per account are kept.

## Link endpoints

Link authentication required. Served by the server of the account the link belongs to.

| method | path | request | response |
|---|---|---|---|
| GET | `/api/link` | | `{state, expires_at}` |
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
- `DELETE /api/link`: deletes the link, or withdraws a pending request; the owning account's connections receive `link_removed`.
- A pending link accepts only `GET /api/link` and `DELETE /api/link`; other link endpoints return `403`. An accepted link becomes `open` when mail is first posted through it. A pending or accepted link returns `410` after its `expires_at` and is deleted; the owning account's connections receive `link_revoked`.
- Group links accept only `POST /api/link/mail` without `blobs`, `GET /api/link/devices` and `GET /api/link/blobs/{id}`; other link endpoints return `403`. A group link's previous token is accepted for 24 hours after it is replaced; other tokens return `401`.

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

- Settings bounds: `server_name` 1 to 64 characters; `ring_timeout_secs` 10 to 300; `60 <= message_ttl_min <= message_ttl_default <= message_ttl_max <= 2592000`; `max_upload_mb` 1 to 500; `device_expiry_days` 7 to 365; `pairing_code_minutes` 1 to 1440; `pairing_request_hours` 1 to 720; `max_group_members` 2 to 50.
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
| `requests` | | a pairing request arrived or was accepted |
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
| `GET /api/link` | 60 per link |
| `POST /api/link/mail` | 60 per link, 120 per group link |
| `POST /api/links/{id}/mail` | 120 per link |
| `POST /api/link/blobs`, `POST /api/links/{id}/blobs` | 20 per link |
| `call` | 10 per link |
| `PUT /api/vault` | 60 per account |
| `POST /api/sent` | 120 per account |
