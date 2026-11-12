# Caw Protocol Specification

Version 1, November 2026

This document specifies the formats, algorithms and behavior required for interoperable Caw clients and servers. Design rationale and security analysis are in [whitepaper.md](whitepaper.md). HTTP endpoints, request and response bodies and WebSocket messages are in [api.md](api.md).

## Contents

1. [Conventions](#1-conventions)
2. [Primitives](#2-primitives)
3. [Accounts and credentials](#3-accounts-and-credentials)
4. [Vault](#4-vault)
5. [Devices](#5-devices)
6. [Contacts](#6-contacts)
7. [Messages](#7-messages)
8. [Calls](#8-calls)
9. [Groups](#9-groups)
10. [Push notifications](#10-push-notifications)
11. [Server requirements](#11-server-requirements)
12. [Client storage](#12-client-storage)
13. [Test vectors](#13-test-vectors)

## 1. Conventions

The key words MUST, MUST NOT, SHOULD, SHOULD NOT and MAY are to be interpreted as described in RFC 2119 and RFC 8174.

- `b64(x)` is base64url without padding (RFC 4648, section 5). Standard base64 is used only where stated.
- Strings are UTF-8. JSON is UTF-8 encoded.
- `||` is byte concatenation. `u64be(n)` is `n` as an 8-byte big-endian unsigned integer.
- Times are integer seconds since the Unix epoch, except `updated_at` and vault entry times, which are milliseconds.
- `LF` is the byte 0x0A.

Identifiers:

| identifier | format |
|---|---|
| account id | decimal string of the server's numeric user id |
| device id, link id, message id, call id, blob id, contact id | UUID string |
| ticket, link token, session token | `b64` of 32 random bytes |
| client id | UUID string chosen by a client per connection lifetime |

A server is identified by its address, the origin of its base URL as browsers serialize it: `http` or `https`, `://`, the host, and `:port` only when the port is not the scheme's default. Scheme and host are lowercase, a non-ASCII host is in its ASCII (IDNA) form, and there is no path, query, fragment or trailing slash; at most 255 characters. Clients bring every address they are given or receive to this form before using or comparing it, taking `https` when the scheme is missing. Cases are in `testdata/groups.json`.

## 2. Primitives

| name | definition |
|---|---|
| Ed25519 | RFC 8032 (pure). Public keys 32 bytes, signatures 64 bytes |
| X25519 | RFC 7748. Keys 32 bytes |
| HPKE | RFC 9180, mode_base, KEM 0x0020 DHKEM(X25519, HKDF-SHA256), KDF 0x0001 HKDF-SHA256, AEAD 0x0001 AES-128-GCM, single-shot Seal and Open |
| AES-GCM | 12-byte nonce, 16-byte tag; key size as stated |
| Argon2id | RFC 9106, version 0x13 |
| PBKDF2 | RFC 8018, HMAC-SHA256 |
| HMAC-SHA256 | RFC 2104 with SHA-256 |

Labels:

| label | use |
|---|---|
| `caw-enc-v1` | encryption key certificate ([5.2](#52-encryption-keys)) |
| `caw-message-key-v1` | HPKE info ([7.2](#72-encryption)) |
| `caw-message-v1` | message signature ([7.4](#74-signature)) |
| `caw-call-v1` | session description signature ([8.2](#82-signaling)) |
| `caw-blob-v1` | attachment AAD ([7.10](#710-attachments)) |
| `caw-words-v1` | verification words ([6.4](#64-verification-words)) |
| `caw-key-v1` | data key wrapping AAD ([4.1](#41-data-key)) |
| `caw-vault-v1` | vault AAD ([4.2](#42-document)) |
| `caw-group-token-v1` | group link token ([9.1](#91-group-links)) |
| `caw-group-id-v1` | derived group message id ([9.5](#95-sending)) |
| `caw-request-key-v1` | request key derivation ([6.2](#62-pairing-code)) |
| `caw-request-v1` | HPKE info of a pairing request ([6.3](#63-pairing)) |
| `caw-hello-v1` | hello message id ([6.3](#63-pairing)) |
| `caw-contact-v1` | contact id from a request ([6.3](#63-pairing)) |
| `caw:2:` | pairing code prefix ([6.2](#62-pairing-code)) |

## 3. Accounts and credentials

### 3.1 Derivation

```
k            = Argon2id(password = UTF-8(NFC(password)), salt, m, t, p, length = 64)
login key    = b64(k[0..32])
password key = k[32..64]
```

- `salt` is 16 bytes, transmitted as `b64`. `m` (KiB), `t` and `p` are returned with the salt; version 1 uses `m = 65536, t = 3, p = 1`.
- Clients MUST obtain the salt and parameters from `POST /api/auth/salt` and MUST NOT send the password or the password key to a server.
- Usernames are compared after trimming and lowercasing.

### 3.2 Sign-in and credential changes

- Sign-in sends `{username, login_key}`. On success the client establishes the data key ([4.5](#45-key-establishment)) using the password and the derivation.
- A credential change generates a new 16-byte salt, derives a new login key and password key, and sends `PUT /api/me/login` with the new `login_key` and `salt`, the current login key (not required when the account is flagged `must_change`), and the data key wrapped under the new password key.
- Clients MUST reject a new password shorter than 12 code points, with fewer than 5 distinct characters, containing the username (3 or more characters), whose letters alone form a common word or its repetition, or whose letters and digits, 6 or more, form a run of a numeric, alphabetic or keyboard sequence.

## 4. Vault

### 4.1 Data key

The data key is 32 random bytes per account. Its wrapped form:

```
wrapped = salt "." m "." t "." p "." b64(nonce || AES-256-GCM(password key, nonce, aad = "caw-key-v1", data key))
```

`salt`, `m`, `t` and `p` are those of the derivation that produced the password key, as `b64` and decimal strings. To unwrap, a client derives the password key with the recorded parameters.

### 4.2 Document

```
blob = b64(nonce || AES-256-GCM(data key, nonce, aad = "caw-vault-v1", UTF-8(JSON(document))))
document = { "v": 1, "contacts": { <contact id>: <entry> }, "groups": { <group id>: <group entry> } }
entry    = { "u": <ms>, "c": <contact> } | { "u": <ms>, "gone": true }
```

Contact record:

| field | type | |
|---|---|---|
| `id` | string | UUID, equal to the entry key |
| `name` | string | name received during pairing |
| `alias` | string, optional | user's rename |
| `favorite` | `true`, optional | |
| `server` | string | contact's server address |
| `account` | string | contact's account id |
| `out` | `{link_id, token}` | contact's link, used to reach the contact |
| `in` | string | id of the account's link for the contact; empty while a request is pending |
| `status` | `"pending"` or `"active"` | |
| `created_at` | integer | seconds |
| `ttl` | integer, optional | conversation message lifetime, seconds |
| `words` | array of 6 strings, optional | verification words |
| `expires_at` | integer, optional | seconds; when a pending request ends |
| `updated_at` | integer | milliseconds; equal to the entry's `u` |

Clients MUST ignore unknown fields. Groups are described in [9.10](#910-vault).

### 4.3 Merge

For each entry `e` in a fetched document, let `L` be the local `updated_at` of the contact with that id, else the local removal time for that id, else -1:

- If `e.u <= L`, the local state is kept.
- Else if `e.gone` is true or `e.c` is absent, the local contact is removed and its removal time set to `e.u`.
- Else the local contact's synced fields are replaced with `e.c`, or it is added.

A local change sets `updated_at = max(now_ms, updated_at + 1)`. A change caused by a `timer` message, and a contact stored from a `hello` (6.3), set `updated_at = max(created_at * 1000, updated_at + 1)`, using the message's `created_at`. Removal markers MAY be omitted from uploads 180 days after their time.

### 4.4 Upload

1. `GET /api/vault`. If `blob` is present, decrypt it and merge it.
2. If the local state has no entry newer than the fetched copy, stop.
3. `PUT /api/vault {version, blob}` with the fetched `version` and the local state encrypted under the data key.
4. On `409`, repeat from step 1.

On a `vault` event with a version greater than the last one seen, clients fetch and merge.

### 4.5 Key establishment

At sign-in, with the password and its derivation:

1. If `wrapped_key` is present, unwrap it ([4.1](#41-data-key)).
2. Otherwise, or if unwrapping fails: use a data key stored on the device if it decrypts `blob` or `blob` is absent; else, if `blob` is absent, generate a data key. Store the wrapped key with `PUT /api/vault/key`.
3. If no data key results, the vault is locked under a previous password. The client MAY ask for that password, unwrap the data key with it, verify that it decrypts `blob`, and store it wrapped under the current password key. The client MAY instead generate a new data key, store it wrapped, and upload its local contacts as the new vault.

A client with a session but no stored data key MUST end the session and sign in again. A client whose stored data key no longer decrypts the vault MUST delete the key, end the session and sign in again.

### 4.6 Key replacement

To replace the data key, a client fetches and merges the vault, generates a new data key, changes the credential ([3.2](#32-sign-in-and-credential-changes)) with the new key wrapped, and uploads the vault encrypted with the new key. While replacing, it merges fetched copies that decrypt with either key.

## 5. Devices

### 5.1 Enrollment

A device generates an Ed25519 identity key pair and enrolls with `POST /api/devices {label, sign_pub}`. A session is bound to a device when it enrolls it or uploads an encryption key for it; clients MUST upload their current encryption key after each sign-in.

### 5.2 Encryption keys

A device generates an X25519 key pair when it has none or its newest key is older than 86400 seconds, and uploads it with a certificate:

```
enc_sig = Ed25519(identity private key, "caw-enc-v1" || UTF-8(device id) || 0x00 || enc_pub || u64be(created_at))
```

`created_at` is the current time. A device MUST delete an encryption private key when `created_at + 86400 + ttl_max` has passed, where `ttl_max` is the server's maximum message lifetime, except its newest key.

### 5.3 Device lists

A device list entry is valid only if `enc_pub` is present and `enc_sig` verifies with `sign_pub` over the input in [5.2](#52-encryption-keys). Senders MUST NOT encrypt for invalid entries.

### 5.4 Known devices

Each device keeps, for its own account, a record per device id: encryption public keys observed (from `keys` and `enc_pub` in `GET /api/devices`), identity key, label, and the time last listed. Records not listed for `ttl_max + 86400` seconds are dropped. A device listed with an id not in the records, other than the device itself, created at or after the device's own enrollment, is a new device; the client SHOULD notify the user. On first use, records are populated without notification.

### 5.5 Device check

For each message the device reads, where `own` is `to` for a message received through one of the account's links and `from` for a sent copy:

1. If the message was created before the device's own enrollment, stop.
2. Every stanza with `o = own` MUST name a device in the known records with `k` among its observed keys.
3. If a stanza fails, refetch the device list and repeat step 2. If it still fails, the client MUST warn the user.

## 6. Contacts

### 6.1 Links

A link is used with the header `Authorization: Link <link id>.<token>`, or the query parameter `link=<link id>.<token>` on WebSocket requests. The owner creates inbound links with `POST /api/links {peer_server, peer_account}`; pairing creates one by redemption. Group links are described in [9.1](#91-group-links). Servers return `410` for unknown and revoked link ids. Requests for different links share URLs, so clients MUST NOT answer a link request from an HTTP cache.

### 6.2 Pairing code

```
code = "caw:2:" b64(UTF-8(JSON({ "v": 2, "s": <server>, "t": <ticket>, "a": <account id>, "n": <display name>, "k": b64(request key) })))
```

The ticket is obtained with `POST /api/pairings`. It is single use and expires at `expires_at`. `k` is the X25519 public key of the account's request key, whose private key is `HKDF-SHA256(ikm = data key, salt = "", info = "caw-request-key-v1", length = 32)`; every device of the account derives it.

### 6.3 Pairing

Account A shows a code; account B enters it. Until A accepts, B gives A only a name, and A's devices do not contact B's server.

1. B MUST reject a code whose `s` and `a` are its own, or that identifies an existing contact.
2. B redeems the ticket on A's server: `POST {s}/api/pairings/redeem {ticket, wait, request}`, receiving L_A and its `expires_at`. `wait` is the request's lifetime in seconds, at most B's server's `pairing_request_hours`; A's server limits it to its own. `request = b64(enc || ct)`, where `(enc, ct) = HPKE.SealBase(pkR = k, info = "caw-request-v1", aad = "", pt = UTF-8(JSON({"name": <B's display name>})))`.
3. B stores the contact with `status: "pending"`, `in` empty and `expires_at`.
4. A's server sends `requests` to A's connections and a `request` push. A's devices list requests with `GET /api/links` and present each pending one with the name opened from `request`, which is not verified, and the verification words for L_A. A request is dropped when its link is revoked or removed.
5. Accept: A sends `POST /api/links/{L_A}/accept`. Decline: A revokes L_A.
6. B's devices read the request's state with `GET {s}/api/link` through L_A when the client starts or comes to the foreground, and periodically while it is in the foreground. On `410`, or once `expires_at` has passed, B removes the contact.
7. When the state is `accepted`, a device of B creates L_B for A (`POST /api/links {peer_server: s, peer_account: a}`), reads A's device list through L_A, MUST verify that `account` equals `a`, keeps the `sign_pub` of the listed devices, and sends `{"t": "hello", "card": {server, account, name, link_id, token}}` through L_A with id `uuid8(SHA-256("caw-hello-v1" || UTF-8(L_A))[0..16])` (9.5), where the card describes B and L_B. It then sets the contact `active` with `in` L_B. On `409`, another device of B sent the hello first; the device revokes its L_B.
8. When the state is `open` and the contact is still pending, a device of B reads the hello back with `GET {s}/api/link/mail` through L_A and sets the contact `active` with `in` the card's `link_id`.
9. A device of A that receives a message through a contact link with no contact treats it as a hello. It decrypts it, reads the device list from `card.server` with the card's link, MUST verify that `account` equals `card.account` and that the message signature verifies with a listed device, and stores the contact with id `uuid8(SHA-256("caw-contact-v1" || UTF-8(L_A))[0..16])`, `status: "active"`, `in` L_A, `out` the card's link and the card's server, account and name, so that every device of A stores the same record.

### 6.4 Verification words

```
bytes = PBKDF2-HMAC-SHA256(password = "caw-words-v1" || UTF-8(link id), salt = "caw-words-v1", iterations = 65536, length = 6)
words = [ wordlist[b] for b in bytes ]
```

`link id` is L_A. `wordlist` is `protocol/words.txt`, 256 lines.

### 6.5 Removal

To remove a contact, a client revokes `in` (`DELETE /api/links/{in}`) if it is set, requests `DELETE /api/link` with `out` on the contact's server, which also withdraws a pending request, and records a removal in the vault. On `link_removed` for a contact's `in`, the client removes the contact. On `410` from the contact's server, the client removes the contact. On `link_revoked`, clients remove the contact with that `in`.

## 7. Messages

### 7.1 Plaintext

A JSON object with type `t`:

| `t` | fields |
|---|---|
| `text` | `text`: string |
| `media` | `kind`: `"image"` or `"video"`; `mime`: string; `blob`: blob id; `key`: `b64` of 32 bytes; `size`: integer; `w`, `h`: integers; `dur`: integer seconds (video); `thumb`: standard base64 JPEG; `text`: string, optional |
| `call` | `outcome`: `"answered"`, `"missed"` or `"declined"`; `secs`: integer, when answered |
| `hello` | `card`: `{server, account, name, link_id, token}` |
| `timer` | `ttl`: integer seconds |

`hello` and `timer` are control messages and are not displayed. Group messages are described in [9.4](#94-messages).

### 7.2 Encryption

1. `id` is a new UUID. Retries of the same message MUST reuse it.
2. `ck` is 16 random bytes and `nonce` 12 random bytes.
3. `body = b64(nonce || AES-128-GCM(ck, nonce, aad = UTF-8(id), UTF-8(JSON(plaintext))))`.
4. For each recipient device: `w = b64(enc || ct)` where `(enc, ct) = HPKE.SealBase(pkR = enc_pub, info = "caw-message-key-v1", aad = UTF-8(id), pt = ck)`. `enc` and `ct` are 32 bytes each.
5. Each stanza is `{d: device id, k: b64(enc_pub), w, o}`.

### 7.3 Recipients

- `o = "to"`: every valid device in the recipient's list, read through the link. The list MAY be cached for up to 60 seconds.
- `o = "from"`: every valid device in the sender's own list, with the sending device included using its newest key.

### 7.4 Signature

```
sig = b64(Ed25519(identity private key of from_device, input))
input = "caw-message-v1" LF id LF from_device LF ttl LF body LF stanzas
stanzas = for each stanza sorted by d: d ":" k ":" w ":" o, joined with ","
```

`ttl` is in decimal. Sorting compares `d` by code units.

### 7.5 Delivery

The sender posts `{id, from_device, ttl, body, keys, sig, blobs}` to `POST /api/link/mail` on the recipient's server.

| response | sender action |
|---|---|
| 200 | delivered; then `POST /api/sent {contact: <contact id>}` on its own server |
| 409 | delivered by an earlier attempt |
| 412 | reread the recipient's device list without cache, encrypt again with the same `id`, post once more |
| 410 | the contact is gone ([6.5](#65-removal)) |
| network error, 5xx, 429 | keep the message queued and retry later; honor `Retry-After` |

Queued messages are discarded once `at + ttl` has passed, where `at` is the time the message was queued.

### 7.6 Reading

1. Find the stanza with `d` equal to the device's id and the private key whose public key equals `k`. If absent, the message is unreadable.
2. Verify `sig` over the input in [7.4](#74-signature) with the identity key of `from_device`: for received messages, from the sender's server's device list read through `out`; for sent copies, from the device's own known records. Clients SHOULD retain identity keys of devices they have seen. If verification fails, the message is unreadable.
3. `ck = HPKE.OpenBase(enc, skR, info = "caw-message-key-v1", aad = UTF-8(id), ct)`, then decrypt `body`.
4. Run the device check ([5.5](#55-device-check)).

Messages with an `id` already held are ignored. Received messages are read from `GET /api/mail` and the `mail` event; sent copies from `GET /api/link/mail` on each contact's server, and again for a contact named in a `sent` event.

### 7.7 Expiry

The recipient's server stores a message until `created_at` plus `ttl` limited to its configured minimum and maximum, and returns that time as `expires_at`. Clients MUST discard messages at `expires_at` and SHOULD keep messages in memory only.

### 7.8 Message lifetime per conversation

New messages use the contact's `ttl`, or the server default. Changing it sets the contact's `ttl` and sends `{"t": "timer", "ttl"}`. A device receiving `timer` sets the contact's `ttl`.

### 7.9 Control messages

| `t` | receiver action |
|---|---|
| `hello` | [6.3](#63-pairing) step 9, through a contact link with no contact |
| `timer` | set the contact's `ttl` |

All devices of the receiving account process control messages; vault timestamps follow [4.3](#43-merge).

### 7.10 Attachments

1. Generate a 32-byte `key`. The file is `nonce || AES-256-GCM(key, nonce, aad = "caw-blob-v1", bytes)`.
2. Upload it with `POST /api/link/blobs` to the recipient's server; the response gives the blob id.
3. Send a `media` message carrying the blob id and `key`, with `blobs: [<blob id>]` in the POST body. `blobs` is not signed.
4. The recipient account downloads with `GET /api/blobs/{id}`; the sender's devices with `GET /api/link/blobs/{id}`.

Clients SHOULD reduce images to 1600 pixels on the longest side and SHOULD transcode video to 720p or lower. Clients MUST NOT send the source file's metadata, such as location, device details, capture time and tags: images are re-encoded, and a video that cannot be transcoded is remuxed without it or not sent. `thumb` SHOULD be at most 64 pixels on the longest side.

## 8. Calls

### 8.1 Flow

The caller connects to the callee's server at `/api/link/ws?link=<link id>.<token>&client=<client id>` and the callee's devices are connected to their own server at `/ws`. Each side obtains ICE servers from its own server with `GET /api/turn`.

1. Caller sends `{"type": "call", "call_id": <uuid>}` and receives `ringing`.
2. Each callee connection receives `incoming {call_id, link_id}`; a push of type `call` is sent. The device maps `link_id` to the contact whose `in` equals it. A device that is already in a call, or has no contact for the link, MUST NOT respond, so that the account's other devices can answer.
3. A callee device answers with `accept`. It receives `joined {call_id, peers}`; the caller receives `peer_joined`. Other callee connections receive `ended` with reason `answered_elsewhere`.
4. A callee device MAY send `reject` at the user's request before any device answers; the caller receives `declined` and the call ends for all devices.
5. Either side ends the call with `hangup`. An unanswered call ends after the server's ring timeout with reason `no_answer`.

### 8.2 Signaling

`{"type": "signal", "call_id", "to": <peer id>, "data"}` is delivered to the peer as `{"type": "signal", "call_id", "from": <peer id>, "data"}`. `data` is one of:

| data | |
|---|---|
| `{"sdp": {"type", "sdp"}, "device", "sig"}` | session description, signed |
| `{"candidate": {...}}` | ICE candidate (`RTCIceCandidateInit`) |
| `{"restart": true}` | request for an ICE restart |

Every session description is signed by the sending device:

```
sig = b64(Ed25519(identity private key of device, "caw-call-v1" LF call_id LF type LF sdp))
```

Before applying a session description, the receiver MUST verify `sig` with the identity key of `device` as listed for the other party's account by that account's server, read through the contact's `out` link, and MUST end the call if verification fails. The signature covers the DTLS fingerprint, so a server relaying the call cannot substitute its own. ICE candidates are not signed.

The device that receives `joined` sends an offer to each listed peer and is the offerer for that peer. Only the offerer restarts ICE; the other side requests a restart with `{"restart": true}`.

### 8.3 Reconnection

A connection that closes during a call keeps its place for 30 seconds. A client reconnecting with the same `client` id resumes it; `hello` lists the calls the connection is in. A client SHOULD restart ICE for calls listed and end calls not listed.

### 8.4 Call record

After a call in which the callee's devices were rung, the caller sends a `call` message with the outcome and, if answered, the duration in seconds. No record is sent for a call ended by a failed verification.

## 9. Groups

A group is a conversation among accounts that may be on different servers. Each member receives the group's messages on its own server; the sender posts every group message to the server of each member. Servers do not learn group membership.

### 9.1 Group links

A member receives a group's mail through one group link on its own server, created with `POST /api/links {kind: "group", gen, token}`. Every member holds its token. Through a group link, servers accept only `POST /api/link/mail` without `blobs`, `GET /api/link/devices` and `GET /api/link/blobs/{id}`. The owner posts its own copies with `POST /api/links/{id}/mail`, uploads attachments with `POST /api/links/{id}/blobs` and replaces the token with `PUT /api/links/{id}/token`; the replaced token remains valid for 24 hours.

```
token(gen) = b64(HMAC-SHA256(secret, "caw-group-token-v1" || u64be(gen)))
```

`secret` is 32 random bytes per account and group, held in the vault and never sent to members.

### 9.2 State

Group ids and member ids are UUIDs. A member id is chosen when the member is invited or creates the group.

| card field | |
|---|---|
| `id` | member id |
| `server`, `account` | the member's server address and account id |
| `name` | display name, 1 to 64 characters |
| `link_id`, `token`, `gen` | the member's group link and its token generation |

```
state = { "name": {"v": <string>, "u": <ms>}, "ttl": {"v": <seconds>, "u": <ms>}, "members": { <member id>: entry } }
entry = { "u": <ms>, "c": <card> } | { "u": <ms>, "gone": true }
```

A member is live if its entry has a card, and gone otherwise. `u` values are integers below 2^53. A member's own token generation is `gen = gone - base`, where `gone` is the number of gone entries and `base` is that number in the first state the member accepted.

### 9.3 Applying a state

A device applies a state received from member `m` to its local state `L`:

1. The state is ignored unless `m` is live in `L`, or is the inviter before the first state (9.7).
2. For each entry `e` of member `x`:
   - `x` gone in `L`: no change.
   - `e` gone: `x` becomes gone.
   - `x` absent from `L`: `e` is added if `e.c.id` is `x` and the card is well formed: UUID ids, `server` an address (section 1), added in normalized form, `account` and `name` of 1 to 64 characters, `token` 32 bytes, `gen` a non-negative integer.
   - `x` live in `L`: `e.c` replaces the card only if `m` is `x`, `server`, `account` and `link_id` are unchanged and `gen` is greater.
3. `name` and `ttl` are replaced if `u` is greater, or equal with a greater `v` (strings compared by code units). A `u` more than 86400000 ahead of the device's clock is ignored.
4. If two live entries have the same `server` and `account`, the one with the greater (`u`, member id) becomes gone.
5. If the result has more than 256 entries, the state is ignored.

If the own entry became gone, the device leaves the group (9.9). Otherwise, if the number of gone entries increased, the account replaces its token (9.8).

### 9.4 Messages

Group messages are `text` and `media` (7.1) with `g` (group id), `m` (sender member id) and `sv` (membership digest):

```
sv   = b64(SHA-256(UTF-8(list))[0..16])
list = for each entry sorted by member id: id ":" (c.gen in decimal, or "x" if gone), joined with ","
```

| `t` | sent through | fields |
|---|---|---|
| `group` | group links | `g`, `m`, `name`, `ttl`, `members` |
| `group_invite` | the contact's link | `g`, `name`, `members` (names), `inviter` (member id), `member` (the invitee's member id), `text` |
| `group_join` | the contact's link | `g`, `card` |
| `group_decline` | the contact's link | `g` |

`group` carries the sender's full state, except the notice to a removed member (9.9). `group_invite.text` is for clients without group support. Control messages are sent with the server's maximum message lifetime.

Through a group link, only `text`, `media` and `group` with `g` equal to the group are accepted. Through a contact link, `group`, and `text` or `media` with `g`, are discarded.

### 9.5 Sending

A group message is sealed separately for each live member, the own account first. Each copy has its own id, `ck`, body and signature (7.2-7.4), with stanzas `o = "to"` for every valid device of that member's account; the own copy includes the sending device with its newest key.

- The own copy is posted with `POST /api/links/{id}/mail` on the own server, listing attachments in `blobs`.
- Other copies are posted with `POST /api/link/mail` on the member's server through its card's link, without `blobs`.

A member's device list is read with `GET /api/link/devices` through its card's link; `account` MUST equal the card's. Responses are handled as in 7.5, except that 401 and 410 keep the copy queued until the member's card changes, and 429 is retried after `Retry-After`. A queued copy is dropped when its message expires.

A message sent in response to a received one uses a derived id, so that every device of the account sends the same copy and duplicates are refused with 409:

```
id = uuid8(HMAC-SHA256(secret, "caw-group-id-v1" || UTF-8(trigger) || 0x00 || UTF-8(member id))[0..16])
```

`trigger` is `join:` followed by the join message id, `gen:` followed by the new generation, or `sync:` followed by the received message id. `uuid8` sets byte 6 to `byte 6 & 0x0f | 0x80` and byte 8 to `byte 8 & 0x3f | 0x80` and formats a lowercase UUID string.

An attachment (7.10) is uploaded once with `POST /api/links/{id}/blobs` and attached to the own copy. Members download it with `GET /api/link/blobs/{id}` through the sender's group link.

### 9.6 Reading

Mail received through a group link (`kind` in `GET /api/links`) belongs to that group and is never a pairing request (6.3).

1. Decrypt with the own stanza (7.6, steps 1 and 3).
2. Require `g` to be the group, and `m` to be the own member id, a live member, a former member whose mail has a `created_at` no later than its entry in `left`, or the inviter before the first state.
3. Verify `sig` with the identity key of `from_device`: for the own member id from the own known records (5.4), otherwise from the device list of `m`'s account read through `m`'s link, or through the contact's link for the inviter before the first state. Members' identity keys are retained while the group is kept, so that mail from former members stays readable.
4. Run the device check (5.5) with `own = "to"`.
5. For mail that arrives while the device is connected, if `sv` differs from the local digest, send the full state to `m`, at most once per member per minute.

When a mailbox is loaded, `group` messages are applied in `created_at` order before other messages are read.

### 9.7 Joining

1. A member invites one of its contacts by sending `group_invite` through the contact's link, and records the invite in its vault with a new member id for the invitee and an expiry 7 days later. Clients MUST NOT invite if live members and pending invites would exceed the own server's `max_group_members`.
2. The invitee accepts by creating a group link (new secret, `gen` 0), storing the group with its own card and the inviter, and sending `group_join` with its card through the contact's link. Clients MUST NOT accept if the members with the invitee would exceed the own server's `max_group_members`. To decline, the invitee sends `group_decline`.
3. A device of the inviting account adds the card from `group_join` only if a pending invite exists for that contact and group, `card.id` is the invited member id, and `card.server` and `card.account` are the contact's. It removes the invite and sends the full state to all live members with the trigger `join:`.
4. The invitee accepts its first state only from the inviter, signed by a device of the contact's account, with the inviter's card matching the contact's server and account and the own entry equal to its own card. It then sets `base`.

If two devices of the invitee accept, the card in the first state is kept and the other group link is deleted.

### 9.8 Token replacement

When the number of gone entries increases, each live member's account computes its `gen`, sends `PUT /api/links/{id}/token` with `token(gen)`, updates its own card and sends the full state to all live members with the trigger `gen:`. The account's devices derive the same token, so the request is idempotent.

### 9.9 Leaving and removal

- To remove a member, a member marks it gone, sends the full state to all live members, and sends `group` with only the gone entry to the removed member.
- To leave, a member marks itself gone, sends the full state to all other live members, deletes its group link and removes the group from its vault. Deleting the link deletes the attachments uploaded through it.
- A device removes a group when its own entry is gone, or its group link is missing from `GET /api/links` or named in `link_revoked`.

### 9.10 Vault

The vault document (4.2) holds `groups`: `{ <group id>: { "u": <ms>, "g": <group> } | { "u": <ms>, "gone": true } }`.

| group field | |
|---|---|
| `id`, `me` | group id, own member id |
| `name`, `ttl`, `members` | state (9.2) |
| `own` | `{link_id, secret, base, gen}` |
| `invites` | `{ <contact id>: {member, expires} }` |
| `inviter` | `{contact, member}`, until the first state |
| `left` | `{ <member id>: <seconds> }`, the server time at which the account first applied each member's removal |
| `created_at` | seconds |

Between the account's devices, a group marked gone with a greater `u` is removed. Otherwise members and registers merge by `u` with gone entries staying gone, `own` is taken from the copy with the greater `u` with `gen` the maximum, pending invites are the union, `left` takes the earlier time per member, and `inviter` is kept only if both copies have it. Clients MUST keep unknown top-level fields of the vault document.

## 10. Push notifications

Clients register a WebPush endpoint (RFC 8030) created with the server's VAPID public key from `GET /api/config`, together with a P-256 public key and a 16-byte authentication secret (RFC 8291). Payloads:

| payload | client action |
|---|---|
| `{"type": "mail"}` | fetch `GET /api/mail` and notify |
| `{"type": "request"}` | fetch `GET /api/links` and notify of new pairing requests |
| `{"type": "call", "call_id"}` | connect the WebSocket; the call arrives in `hello` |
| `{"type": "cancel", "call_id"}` | stop ringing for `call_id` |

Clients MUST discard payloads that fail to decrypt.

## 11. Server requirements

- Servers MUST store session tokens, link tokens and tickets only as hashes, and login keys only as a salted slow hash.
- For an unknown username, `POST /api/auth/salt` MUST return a salt that is stable for that username and indistinguishable from a real one.
- Servers MUST reject `POST /api/link/mail` with `412` when the stanzas with `o = "to"` do not include every device of the account that has an encryption key.
- Servers MUST delete messages and attachments at `expires_at`, and SHOULD do so in a way that does not leave the data recoverable from the database file.
- Servers MUST keep each device's encryption public keys for at least `ttl_max + 86400` seconds after they are replaced and return them in `keys`.
- Servers MUST deliver `mail` and `request` to all connections and push endpoints of the account, and `sent`, `vault`, `devices`, `requests`, `link_removed` and `link_revoked` to all of the account's connections.
- A link made by redeeming a ticket is pending until its owner accepts it: through it, servers MUST accept only `GET /api/link` and `DELETE /api/link`. An accepted link becomes open when mail is first posted through it. Servers MUST NOT accept a pending or accepted link after its `expires_at`, and SHOULD delete it then.
- Removing or expiring a device MUST delete its sessions and close their connections.
- Servers SHOULD encrypt stored vaults and wrapped keys with a secret held outside the database.
- Servers MUST send `Cache-Control: no-store` with API responses.
- Through a group link, servers MUST refuse calls, `DELETE /api/link`, `GET /api/link/mail`, `POST /api/link/blobs` and `blobs` in `POST /api/link/mail`, and MUST accept the previous token for 24 hours after it is replaced.
- Servers SHOULD rate limit as listed in [api.md](api.md#limits).

## 12. Client storage

- Identity and encryption private keys SHOULD be non-exportable or encrypted with a platform key store.
- The data key, contacts and queued messages SHOULD be encrypted at rest with a device-bound key.
- Decrypted messages SHOULD be held in memory only; decrypted attachments, if cached, MUST be deleted at the message's `expires_at`.
- Signing out MUST delete the data key and SHOULD delete contacts, queued messages and known-device records.

## 13. Test vectors

Argon2id, `m = 65536, t = 3, p = 1`, password `correct horse battery staple`, salt `AAECAwQFBgcICQoLDA0ODw`:

```
login key    = 9YPGbrDJg_a-DxgB00WA26bOORo13vYf7QQ8Nl3DfFI
password key = 8cKBUJdhXyGHl-1XTHCE7SH9Jgm40LNwa__U3sfmrrg
```

Verification words, link id `6f1c2a4e-0b7d-4e8a-9c3f-2d5e8a1b7c90`:

```
koala blanket plum ankle ribbon bunny
```

Signature input for `id = "id"`, `from_device = "dev-a"`, `ttl = 86400`, `body = "body"`, stanzas `(dev-b, kb, wb, to)` and `(dev-a, ka, wa, from)`:

```
caw-message-v1
id
dev-a
86400
body
dev-a:ka:wa:from,dev-b:kb:wb:to
```

Session description signature input for `call_id = "call-id"`, `type = "offer"`, `sdp = "v=0"`:

```
caw-call-v1
call-id
offer
v=0
```

Request key, data key `AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8`:

```
k = ruLfkL5uTuSU7f1JqZJ_tsVPJO0Ct_GYTLZ6fsMLtE4
```

Hello id and contact id for L_A `0b1f2a3c-4d5e-4f60-8a71-92b3c4d5e6f7`:

```
hello   = 924e8313-800c-8771-997e-450c0ddc8847
contact = 3aee150e-f8d1-89d8-8682-09b1418af66b
```

HPKE open, `info = "caw-message-key-v1"`, `aad = "message-id"`:

```
skR = t95UAESODeP3F2-yxoye38Lon_Ijq82us7_Zd28Bbs0
pkR = tarVPutDGeHZEOwEQPhJ0Z4KOqn-O8uRNCvYDkiDV1U
w   = yh9lYMmDB0ihEzkBytN2OU7SEf1Nl9yyccDaIig3iRXLixXnFr8dsoDan4bHYixUDCg8j9SxBYKu-LMcLN_J2Q
pt  = BwcHBwcHBwcHBwcHBwcHBw
```

Group token, `secret = AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8`, `gen = 3`:

```
token = b2I-Q59lwHpXXMEbWcdxsbZsZI5UY58weNd-dFeHtD8
```

Derived id, the same secret, trigger `gen:3`, member id `6f1c2a4e-0b7d-4e8a-9c3f-2d5e8a1b7c90`:

```
id = ada5b3c3-c255-8e7d-838a-5680fe5a3fd6
```

Membership digest:

```
list = 0b2f7c1e-5d3a-4c8e-9f10-2a3b4c5d6e7f:2,7d9e1f20-3a4b-4c5d-8e6f-708192a3b4c5:x,e1d2c3b4-a5f6-4789-8a9b-0c1d2e3f4a5b:0
sv   = zLiWXmP0hasivgj-C9CGPA
```

Server address cases and state merge cases are in `testdata/groups.json`.

These vectors are checked by `web/test/crypto.test.ts`, `web/test/groups.test.ts`, `android/app/src/test/java/com/radcrow/caw/sealed/CryptoTest.kt`, `android/app/src/test/java/com/radcrow/caw/GroupsTest.kt` and `server/src/webpush.rs`.
