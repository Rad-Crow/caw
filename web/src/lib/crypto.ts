// message crypto; see docs/protocol.md, section 7
export type Bytes = Uint8Array<ArrayBuffer>

const te = new TextEncoder()
const subtle = globalThis.crypto.subtle
const EMPTY: Bytes = new Uint8Array(0)

export const INFO = te.encode('caw-message-key-v1')

export function b64(b: Bytes): string {
  let s = ''
  for (const x of b) s += String.fromCharCode(x)
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

export function unb64(s: string): Bytes {
  const t = atob(s.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - (s.length % 4)) % 4))
  return Uint8Array.from(t, (c) => c.charCodeAt(0))
}

export function concat(...parts: Bytes[]): Bytes {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0))
  let o = 0
  for (const p of parts) {
    out.set(p, o)
    o += p.length
  }
  return out
}

function i2osp(n: number, len: number): Bytes {
  const out = new Uint8Array(len)
  for (let i = len - 1; i >= 0; i--) {
    out[i] = n & 0xff
    n = Math.floor(n / 256)
  }
  return out
}

export function random(n: number): Bytes {
  return globalThis.crypto.getRandomValues(new Uint8Array(n))
}

const bytes = (b: ArrayBuffer) => new Uint8Array(b)

// hmac zero-pads short keys, but webcrypto rejects a zero-length key
async function hmac(key: Bytes, data: Bytes): Promise<Bytes> {
  const k = await subtle.importKey('raw', key.length ? key : new Uint8Array(32), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign'])
  return bytes(await subtle.sign('HMAC', k, data))
}

async function expand(prk: Bytes, info: Bytes, len: number): Promise<Bytes> {
  let t = EMPTY
  let out = EMPTY
  for (let i = 1; out.length < len; i++) {
    t = await hmac(prk, concat(t, info, Uint8Array.of(i)))
    out = concat(out, t)
  }
  return out.slice(0, len)
}

// rfc 9180: DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, AES-128-GCM, base mode
const V1 = te.encode('HPKE-v1')
const KEM = concat(te.encode('KEM'), i2osp(0x20, 2))
const SUITE = concat(te.encode('HPKE'), i2osp(0x20, 2), i2osp(1, 2), i2osp(1, 2))

const labeledExtract = (suite: Bytes, salt: Bytes, label: string, ikm: Bytes) =>
  hmac(salt, concat(V1, suite, te.encode(label), ikm))

const labeledExpand = (suite: Bytes, prk: Bytes, label: string, info: Bytes, len: number) =>
  expand(prk, concat(i2osp(len, 2), V1, suite, te.encode(label), info), len)

async function x25519(priv: CryptoKey, pubRaw: Bytes): Promise<Bytes> {
  const pub = await subtle.importKey('raw', pubRaw, { name: 'X25519' }, true, [])
  return bytes(await subtle.deriveBits({ name: 'X25519', public: pub } as EcdhKeyDeriveParams, priv, 256))
}

async function context(dh: Bytes, enc: Bytes, pkR: Bytes, info: Bytes) {
  const eae = await labeledExtract(KEM, EMPTY, 'eae_prk', dh)
  const shared = await labeledExpand(KEM, eae, 'shared_secret', concat(enc, pkR), 32)
  const pskIdHash = await labeledExtract(SUITE, EMPTY, 'psk_id_hash', EMPTY)
  const infoHash = await labeledExtract(SUITE, EMPTY, 'info_hash', info)
  const ksc = concat(Uint8Array.of(0), pskIdHash, infoHash)
  const secret = await labeledExtract(SUITE, shared, 'secret', EMPTY)
  return {
    key: await labeledExpand(SUITE, secret, 'key', ksc, 16),
    nonce: await labeledExpand(SUITE, secret, 'base_nonce', ksc, 12),
  }
}

// group links and derived ids; see docs/protocol.md, section 9
const GROUP_TOKEN = te.encode('caw-group-token-v1')
const GROUP_ID = te.encode('caw-group-id-v1')

export async function groupToken(secret: Bytes, gen: number): Promise<string> {
  return b64(await hmac(secret, concat(GROUP_TOKEN, i2osp(gen, 8))))
}

export async function groupMessageId(secret: Bytes, trigger: string, member: string): Promise<string> {
  const h = (await hmac(secret, concat(GROUP_ID, te.encode(trigger), Uint8Array.of(0), te.encode(member)))).slice(0, 16)
  h[6] = (h[6] & 0x0f) | 0x80
  h[8] = (h[8] & 0x3f) | 0x80
  const x = Array.from(h, (b) => b.toString(16).padStart(2, '0')).join('')
  return `${x.slice(0, 8)}-${x.slice(8, 12)}-${x.slice(12, 16)}-${x.slice(16, 20)}-${x.slice(20)}`
}

export async function membershipDigest(list: string): Promise<string> {
  return b64(bytes(await subtle.digest('SHA-256', te.encode(list))).slice(0, 16))
}

export async function aesGcm(mode: 'encrypt' | 'decrypt', key: Bytes, iv: Bytes, aad: Bytes, data: Bytes) {
  const k = await subtle.importKey('raw', key, 'AES-GCM', false, [mode])
  const params = { name: 'AES-GCM', iv, additionalData: aad }
  return bytes(mode === 'encrypt' ? await subtle.encrypt(params, k, data) : await subtle.decrypt(params, k, data))
}

export async function hpkeSeal(pkR: Bytes, info: Bytes, aad: Bytes, pt: Bytes): Promise<Bytes> {
  const eph = (await subtle.generateKey({ name: 'X25519' }, true, ['deriveBits'])) as CryptoKeyPair
  const enc = bytes(await subtle.exportKey('raw', eph.publicKey))
  const { key, nonce } = await context(await x25519(eph.privateKey, pkR), enc, pkR, info)
  return concat(enc, await aesGcm('encrypt', key, nonce, aad, pt))
}

export async function hpkeOpen(skR: CryptoKey, pkR: Bytes, w: Bytes, info: Bytes, aad: Bytes) {
  const enc = w.slice(0, 32)
  const { key, nonce } = await context(await x25519(skR, enc), enc, pkR, info)
  return aesGcm('decrypt', key, nonce, aad, w.slice(32))
}

export async function newX25519(): Promise<{ priv: CryptoKey; pub: Bytes }> {
  const kp = (await subtle.generateKey({ name: 'X25519' }, false, ['deriveBits'])) as CryptoKeyPair
  return { priv: kp.privateKey, pub: bytes(await subtle.exportKey('raw', kp.publicKey)) }
}

export async function newEd25519(): Promise<{ priv: CryptoKey; pub: Bytes }> {
  const kp = (await subtle.generateKey({ name: 'Ed25519' }, false, ['sign', 'verify'])) as CryptoKeyPair
  return { priv: kp.privateKey, pub: bytes(await subtle.exportKey('raw', kp.publicKey)) }
}

export async function sign(priv: CryptoKey, data: Bytes): Promise<Bytes> {
  return bytes(await subtle.sign({ name: 'Ed25519' }, priv, data))
}

export async function verify(pubRaw: Bytes, sig: Bytes, data: Bytes): Promise<boolean> {
  try {
    const pub = await subtle.importKey('raw', pubRaw, { name: 'Ed25519' }, false, ['verify'])
    return await subtle.verify({ name: 'Ed25519' }, pub, sig, data)
  } catch {
    return false
  }
}

export function encCertBytes(deviceId: string, encPub: Bytes, createdAt: number): Bytes {
  return concat(te.encode('caw-enc-v1'), te.encode(deviceId), Uint8Array.of(0), encPub, i2osp(createdAt, 8))
}

export interface Stanza {
  d: string
  k: string
  w: string
  // device owner: recipient ("to") or sender ("from")
  o: 'to' | 'from'
}

export function messageSigBytes(m: { id: string; from_device: string; ttl: number; body: string; keys: Stanza[] }) {
  const stanzas = [...m.keys]
    .sort((a, b) => (a.d < b.d ? -1 : a.d > b.d ? 1 : 0))
    .map((k) => `${k.d}:${k.k}:${k.w}:${k.o}`)
    .join(',')
  return te.encode(`caw-message-v1\n${m.id}\n${m.from_device}\n${m.ttl}\n${m.body}\n${stanzas}`)
}

// a call's session description, bound to the call
export function callSigBytes(callId: string, type: string, sdp: string): Bytes {
  return te.encode(`caw-call-v1\n${callId}\n${type}\n${sdp}`)
}

// derived from the link id, known to both sides of a pairing; the scanner gets it by redeeming the code
export async function pairingWords(linkId: string, wordlist: string[]): Promise<string[]> {
  const tag = te.encode('caw-words-v1')
  const base = await subtle.importKey('raw', concat(tag, te.encode(linkId)), 'PBKDF2', false, ['deriveBits'])
  const out = bytes(await subtle.deriveBits({ name: 'PBKDF2', salt: tag, iterations: 65536, hash: 'SHA-256' }, base, 48))
  return [...out].map((b) => wordlist[b])
}
