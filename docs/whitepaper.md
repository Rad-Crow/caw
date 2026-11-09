# Caw: Private Calls and Messages on Self-Hosted Servers

White paper, version 1, November 2026

## Abstract

Caw is a self-hosted system for one-to-one audio calls and expiring, end-to-end encrypted messages between two people or in groups. Anyone can run a server, for themselves, their family and friends, or a larger community, and people on different servers can become contacts without the servers communicating with each other or exposing a user directory. Contacts are added only by pairing with a short-lived code, which creates capabilities that let each side reach the other's server directly. Messages are encrypted on the sending device for every device of both accounts and are deleted, together with the keys able to decrypt them, when they expire. Calls are encrypted between the two devices, with call setup signed by the devices' identity keys. Adding a device requires no linking or approval: each server acts as the device directory for its own accounts, and clients detect a server that adds devices by checking the device lists carried in every message. Contacts synchronize between a user's devices through a vault encrypted with a key derived from the password, which the server never receives.

The protocol is specified in [protocol.md](protocol.md) and the server interface in [api.md](api.md).

## 1. Introduction

People often want private communication on infrastructure they control, whether for themselves, their family and friends, or a larger community. The widely used end-to-end encrypted systems involve trade-offs for this case:

- Centrally operated services require users to trust a single provider and to be discoverable through a phone number or username.
- Federated systems replicate data between servers and keep a user directory on each server, so a server learns about users and conversations hosted elsewhere.
- Most systems ask users to verify keys or approve new devices. Non-technical users commonly skip or misunderstand these steps and are left with warnings or unverified devices.

Caw is designed to be usable by people who are not technical. It makes the following choices:

- Anyone can run a server, for one person or many. People on different servers reach each other directly, as easily as people on the same server.
- People become contacts only by exchanging a pairing code.
- Signing in on a new device makes it usable immediately. Instead of asking users to verify devices, clients detect a server that adds devices.
- Messages are ephemeral. Servers delete them at expiry, and devices delete the keys that could decrypt any copies.

## 2. Goals

- Message and call content readable only by the devices of the people in the conversation.
- Private user information: no directory, and contacts only through explicit pairing.
- Contacts across independently operated servers, without a server-to-server protocol.
- No history: messages expire, and both the stored ciphertext and the keys that could decrypt it are deleted.
- Several devices per account, without a linking or approval step.
- Detection of a server that adds devices to an account.
- Simple operation: a single administrator can run a server with Docker Compose and an SQLite database.

### Trust in the server

Caw gives the server a small, defined role in exchange for ease of use. The server stores each account's contact list, encrypted with a key derived from the password, so that contacts follow a user to every device without any setup. It also lists each account's devices, and clients check that list against every message they receive. Message and call content stays end-to-end encrypted between devices. [Section 5](#5-threat-model) states exactly what the server is and is not trusted with.

## 3. Overview

### 3.1 Accounts and devices

An account belongs to a home server. Each app install or browser profile signed in to the account is a device, with an Ed25519 identity key and an X25519 encryption key that is replaced daily and certified by the identity key. The server publishes each account's device list to the account's own devices and to its contacts.

A user's password is turned into two keys on the device with Argon2id. One, the login key, is sent to the server in place of the password. The other, the password key, never leaves the device. It protects a random data key, which encrypts the account's vault: the document holding the user's contacts, their names and settings. A new device signs in, derives the password key, opens the vault and has all contacts.

### 3.2 Contacts

Account A shows a pairing code, as a QR code or shared as an image and text. The code carries A's server address, a single-use ticket, A's account id and A's name. Account B scans or pastes it. B's client creates a link on B's server for A, redeems the ticket on A's server to obtain a link for B, and sends A an encrypted hello through it. Both screens show six verification words derived from the new link; A accepts or declines.

A link is a capability: a random token that lets its holder deliver messages and calls to one account, and nothing else. After pairing, each side holds a link on the other's server:

```
B's devices --(token for L_A)--> server X: link L_A --> account A
A's devices --(token for L_B)--> server Y: link L_B --> account B
```

Each side receives on its own server and sends directly to the other's. The servers never communicate.

### 3.3 Messages

A message is encrypted once with a random key, and that key is encrypted with HPKE for every device of the recipient's account and every device of the sender's account. Each of these key stanzas names its device and says whether it belongs to the recipient or the sender. The sending device signs the message, including the stanzas. The message is stored on the recipient's server until it expires; the sender's devices read their own copies from the same place through their link. Attachments are encrypted separately, uploaded to the recipient's server and expire with their message.

### 3.4 Calls

The caller connects to the callee's server through its link, and the server rings all of the callee's devices; the first to answer takes the call. The server relays WebRTC signaling between the two devices, and each device signs its session descriptions with its identity key. Audio flows between the devices, encrypted with DTLS-SRTP, through TURN relays operated by each side's own server when a direct path is not available.

### 3.5 Groups

A group's members may be on different servers. Each member receives the group's messages on its own server through a group link, a capability whose token every member holds. The sender seals a separate copy for each member's devices and posts it to that member's server; its own copy goes to its own server, and attachments are uploaded once, to the sender's server.

People join only when invited by a member who is their contact, and only after accepting. Any member can add its own contacts, remove members, rename the group and change its message lifetime. Each member's vault holds the group: the members, their links and the settings. Members send their full view to the others on every change, and every message carries a digest of the sender's member list, so views that differ are reconciled.

## 4. Design decisions

### 4.1 Capabilities instead of federation

In a federated system, servers exchange messages and user information with each other, and each server must decide which other servers to trust. Caw has no server-to-server protocol. A server accepts traffic from another server's users only when they present a link token issued during pairing.

As a result, servers do not need to trust or know about each other, a server cannot enumerate or contact users of another server, and a contact on another server is reached the same way as one on the same server. The cost is that the recipient's server sees the IP address of every sender, and there is no global address for a person.

### 4.2 Pairing as the only way to add contacts

Caw has no user directory, search, or address that can be used without the account owner's participation. Every contact relationship begins with a code the owner chose to show or share. Codes are single use, expire, and the owner must accept the resulting request. Unsolicited messages require a link, and links are rate limited and revocable. The cost is that two people must exchange a code through some channel before they can communicate.

### 4.3 The server as device directory

End-to-end encryption depends on knowing which devices belong to an account. Systems that leave this to users rely on safety numbers, device approval or cross-signing, which non-technical users often skip or do not complete. Caw instead trusts each server to list its own accounts' devices, and makes an incorrect list detectable:

- Every message lists the devices it was encrypted for, and the signature covers the list.
- Each device compares the stanzas for its own account with the device list its own server reports. A device that receives a message also encrypted for an unknown device warns its user.
- When a device is added to an account, the account's other devices show a notice. The user can remove the device, which also changes the password and replaces the vault key.
- The recipient's server rejects messages that omit any of the account's current devices, so a device that just signed in is not skipped.

A server that adds a device can read messages encrypted after that point until a device check or the new-device notice reveals it. The design detects this attack rather than preventing it, in exchange for adding devices without any user action.

### 4.4 A password-derived vault with a data key

Contacts must be available on every device of an account. Instead of a device-to-device synchronization protocol, Caw stores one encrypted document per account on its server.

- The password is never sent to the server; a separate login key derived from it is.
- The vault is encrypted with a random data key. Only the data key is wrapped with the password key, so devices keep the data key after sign-in and do not retain the password. Changing the password re-wraps the data key without affecting other devices.
- The server encrypts the stored vault again with a secret held outside the database, so a leaked database or backup does not expose it.
- When a user removes an unrecognized device, the data key is replaced, and the removed device's copy stops working.

The vault's security rests on the strength of the password against offline guessing by a malicious server. Argon2id with 64 MiB of memory per guess, and client-side password rules, raise the cost of such guessing.

### 4.5 Expiry through deletion

Caw keeps no message history. Messages carry a lifetime chosen per conversation, bounded by the server's settings. Servers delete messages and attachments when they expire. Devices hold messages in memory only, replace their encryption keys daily, and delete each encryption private key once the maximum message lifetime has passed since it was replaced. A server that retains ciphertext beyond expiry therefore holds data that no device can decrypt.

This provides forward secrecy with a granularity of days rather than per message. A per-message ratchet would add state shared between every pair of devices; daily keys keep the multi-device design simple and make expiry the main mechanism for limiting exposure.

### 4.6 Calls on the callee's server

Each call runs on the callee's server, the only server that can reach all of the callee's devices. Each side obtains relay credentials from its own server, so neither server provides relay capacity to unknown users. Call setup uses the same link as messages, so only contacts can call.

The server relays the WebRTC session descriptions, which carry the fingerprints of the keys that encrypt the audio. Each device signs its session descriptions with its identity key, and the other device verifies the signature against the contact's device list before connecting. A server relaying a call therefore cannot substitute its own keys without listing an extra device, the same condition as for messages. Network candidates are not signed; altering them can prevent a call but not intercept it.

### 4.7 Groups without a host

A group could live on one server that stores its messages. Caw instead delivers every group message to each member's own server. Members keep receiving only from their own server, so notifications and connections are unchanged, each member's device list still comes from that member's server, and no server holds a group's traffic or depends on another operator's availability. The cost is that the sender posts one copy per member, so each server sets a maximum group size, ten by default, which its users' clients enforce.

When a member leaves or is removed, every remaining member replaces its group token in place. The token is derived from a secret in the vault, so all of a member's devices compute the same one, and the previous token is accepted for 24 hours so messages already on their way arrive. A removed member's messages are discarded by the others.

## 5. Threat model

Adversaries considered:

- a network attacker; all client traffic uses TLS
- a malicious or compromised home server, either the user's own or a contact's, including its operator
- a holder of a leaked database or backup
- a party that obtains a pairing code
- a member of a group, including one that was removed
- a device signed in to the account without the owner's consent

Assumed trustworthy: the client software, the user's own devices and their operating system key stores.

| the server is trusted to | if it is not honest |
|---|---|
| list its accounts' devices accurately | it can add a device and read new messages until the device check or the new-device notice detects it |
| delete messages when they expire | it can keep ciphertext, which becomes unreadable once devices delete the keys |
| store the vault | it can attempt offline password guessing, at the cost of one Argon2id derivation per guess |
| serve the web client | it can serve code that captures the password; the Android app is distributed separately |

The server is not trusted with message or attachment contents, the password, the password key, the data key, or contacts' names and link tokens.

## 6. Security properties

- **Confidentiality.** Message and attachment contents are encrypted for the devices listed by the two accounts' servers, and call audio between the two devices in the call. Servers store only ciphertext.
- **Authenticity.** Every message and every call setup is signed by a device of the sender's account, as listed by the sender's server; for messages the signature covers the recipient list.
- **Device transparency.** A device that a server adds to an account is detected by the account's devices: immediately when added to the account's own list, and on the next message from a contact when shown only to that contact.
- **Expiry.** Expired messages are deleted by servers and cannot be decrypted by devices after their keys are deleted.
- **Vault confidentiality.** The vault is unreadable without the password; a database leaked without the server secret does not expose it.
- **No enumeration.** Accounts cannot be discovered through the server: there is no directory, salt lookups do not reveal whether an account exists, and links are useless without their token.

### 6.1 Malicious home server

| action | outcome |
|---|---|
| read message or attachment content | not possible |
| read the vault | not possible without the password |
| add a device to an account | detected by the new-device notice or the device check |
| forge a message from a contact | requires listing an extra device for that contact; detected by the contact's device check |
| present a fabricated pairing request | the user must accept it, and the verification words do not match those of the intended contact |
| drop, delay or withhold messages | possible, not detected |
| retain ciphertext past expiry | possible; unreadable once devices delete the keys |
| replay a message | message ids are unique per mailbox, and clients drop duplicates |
| move a group message to another conversation | not possible; the group and the sender are inside the signed message |
| intercept or originate calls | requires listing an extra device for the contact; detected by the device check |
| serve a modified web client | possible ([section 8](#8-limitations)) |

### 6.2 Other threats

| threat | outcome |
|---|---|
| leaked database without the server secret | the vault stays encrypted; offline password guessing against the login key hash remains possible |
| leaked database with the server secret | offline password guessing against the wrapped data key |
| online password guessing | rate limited per username and per IP address |
| leaked pairing code | single use and expiring; the owner must accept the request, and the verification words reveal a substitution |
| unrecognized device | removed by the owner, which signs it out, changes the password and replaces the data key |
| unsolicited messages | require a link; links are rate limited and revocable |
| a group member | can add and remove members and introduce people the others have not paired with; cannot change another member's identity or link |
| a removed group member | its messages are discarded; the group tokens it holds are replaced, and the previous ones expire after 24 hours |

## 7. Metadata

Caw protects content, not metadata.

- A home server knows its accounts' contact relationships at the level of links, including each contact's server and account as stated at pairing, and the time and size of every message in each mailbox.
- The recipient's server sees the IP address of every client that posts to it, including contacts on other servers.
- The sender's own server learns when its users send messages, but not to whom.
- Pairing codes contain the account's numeric id.
- The callee's server learns call times and participants. Relay servers see the IP addresses of relayed calls.
- Push services see when notifications are delivered; notifications carry no content.
- Group members see each other's names, servers and account ids. Each member's server sees when group messages arrive for its users and the IP address of every member that posts to it. The sender's server stores its attachments and sees which members download them.

## 8. Limitations

- **The web client is served by the home server.** A malicious server can deliver a client that captures the password or messages. The Android app is installed separately.
- **Device transparency detects but does not prevent.** An added device can read messages, or take part in calls, until it is detected.
- **Forward secrecy is limited to daily key rotation.** A compromised device exposes stored messages encrypted to its current keys, and a compromised identity key allows impersonation of that device until it is removed. There is no recovery from compromise comparable to a per-message ratchet.
- **Messages are signed.** A recipient holds verifiable proof of which device sent a message.
- **Metadata is visible to servers** ([section 7](#7-metadata)).
- **Verification words protect pairing only,** and only when users compare them. A code shared over another channel relies on that channel until then.
- **Link tokens appear in WebSocket URLs** and can be recorded by proxies that log request URLs.
- **Password rules are enforced by clients,** because the server receives derived keys.
- **Group members are trusted to introduce others.** A member can add someone the other members have not paired with; clients show who added whom and which members are contacts.
- **Group state converges without a single order.** Concurrent changes merge, and members whose views differ exchange them when a message's digest does not match.
- **Group size limits are enforced by clients,** since servers do not see group membership.
- **A member's attachments are stored on its own server** and are no longer available once it leaves the group.
- **Older clients** show a group invitation as a message and drop groups from the vault; a client with group support restores them.

## 9. Comparison

| | Signal | Matrix | XMPP with OMEMO | Caw |
|---|---|---|---|---|
| servers | one operator | federated; rooms replicated to participating servers | federated | independent; no server-to-server traffic |
| finding people | phone number or username | user ids and server directories | addresses | pairing codes only |
| adding a device | linked from the primary phone | verified from an existing device or a recovery key | trust decided per device key | automatic; device lists checked by clients |
| message keys | Double Ratchet | Olm and Megolm ratchets | Double Ratchet | daily encryption keys |
| history | kept on devices | kept on servers, encrypted | optional server archive | none; messages expire |

## 10. References

- RFC 9180, Hybrid Public Key Encryption. https://www.rfc-editor.org/rfc/rfc9180
- RFC 8032, Edwards-Curve Digital Signature Algorithm (EdDSA). https://www.rfc-editor.org/rfc/rfc8032
- RFC 7748, Elliptic Curves for Security (X25519). https://www.rfc-editor.org/rfc/rfc7748
- RFC 9106, Argon2 Memory-Hard Function. https://www.rfc-editor.org/rfc/rfc9106
- RFC 8018, PKCS #5: Password-Based Cryptography Specification Version 2.1 (PBKDF2). https://www.rfc-editor.org/rfc/rfc8018
- NIST SP 800-38D, Galois/Counter Mode (GCM). https://csrc.nist.gov/pubs/sp/800/38/d/final
- RFC 8291, Message Encryption for Web Push. https://www.rfc-editor.org/rfc/rfc8291
- RFC 8292, Voluntary Application Server Identification (VAPID) for Web Push. https://www.rfc-editor.org/rfc/rfc8292
- RFC 8827, WebRTC Security Architecture. https://www.rfc-editor.org/rfc/rfc8827
- RFC 5764, DTLS Extension to Establish Keys for SRTP. https://www.rfc-editor.org/rfc/rfc5764
- UnifiedPush. https://unifiedpush.org
- Signal, The Double Ratchet Algorithm. https://signal.org/docs/specifications/doubleratchet/
- Matrix Specification. https://spec.matrix.org
- XEP-0384, OMEMO Encryption. https://xmpp.org/extensions/xep-0384.html
