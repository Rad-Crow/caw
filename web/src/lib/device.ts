import type { MediaPlain } from './media'
import type { Card as GroupCard, Entry, Register } from './groups'
import { api } from './api'
import {
  aesGcm,
  b64,
  concat,
  encCertBytes,
  hpkeOpen,
  hpkeSeal,
  INFO,
  newEd25519,
  newX25519,
  messageSigBytes,
  random,
  sign,
  unb64,
  verify,
  type Bytes,
  type Stanza,
} from './crypto'

const DAY = 86400
const te = new TextEncoder()
const td = new TextDecoder()
const now = () => Math.floor(Date.now() / 1000)

interface EncKey {
  pub: string
  priv: CryptoKey
  created_at: number
}

export interface Device {
  id: string
  signPub: string
  signPriv: CryptoKey
  enc: EncKey[]
}

export interface PublicDevice {
  id: string
  sign_pub: string
  enc_pub: string
  enc_created_at: number
  enc_sig: string
}

export interface OwnDevice extends Partial<PublicDevice> {
  id: string
  label: string
  sign_pub: string
  created_at: number
  last_seen: number
  keys?: string[]
}

export interface Mail {
  link_id: string
  id: string
  from_device: string
  ttl: number
  created_at: number
  expires_at: number
  body: string
  keys: Stanza[]
  sig: string
}

// sent by the scanning side over the new link: its identity and a return link
export type Card = { server: string; account: string; name: string; link_id: string; token: string }

export type GroupFields = { g?: string; m?: string; sv?: string }

export type GroupState = { t: 'group'; g: string; m: string; name?: Register<string>; ttl?: Register<number>; members?: Record<string, Entry> }

export type Plain =
  | ({ t: 'text'; text: string } & GroupFields)
  | { t: 'call'; outcome: 'answered' | 'missed' | 'declined'; secs?: number }
  | (MediaPlain & GroupFields)
  // control messages, not displayed
  | { t: 'hello'; card: Card }
  | { t: 'accept' }
  | { t: 'decline' }
  | { t: 'timer'; ttl: number }
  | { t: 'bye' }
  | GroupState
  | { t: 'group_invite'; g: string; name: string; members: string[]; inviter: string; member: string; text: string }
  | { t: 'group_join'; g: string; card: GroupCard }
  | { t: 'group_decline'; g: string }

// idb holds CryptoKeys as-is; private ones are non-extractable
function idb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const r = indexedDB.open('caw', 1)
    r.onupgradeneeded = () => r.result.createObjectStore('devices')
    r.onsuccess = () => resolve(r.result)
    r.onerror = () => reject(r.error)
  })
}

async function idbDo<T>(mode: IDBTransactionMode, fn: (s: IDBObjectStore) => IDBRequest): Promise<T> {
  const db = await idb()
  return new Promise((resolve, reject) => {
    const req = fn(db.transaction('devices', mode).objectStore('devices'))
    req.onsuccess = () => resolve(req.result as T)
    req.onerror = () => reject(req.error)
  })
}

const loadLocal = (userId: number) => idbDo<Device | undefined>('readonly', (s) => s.get(String(userId)))
const saveLocal = (userId: number, d: Device) => idbDo<void>('readwrite', (s) => s.put(d, String(userId)))
export const forgetLocal = (userId: number) => idbDo<void>('readwrite', (s) => s.delete(String(userId)))

const dataKeyName = (userId: number) => `caw.datakey.${userId}`

// vault data key, sealed at rest; survives a server-ended session but not a sign-out
export async function loadDataKey(userId: number): Promise<Bytes | null> {
  try {
    const s = localStorage.getItem(dataKeyName(userId))
    const v = s ? await openLocal<string>(s) : null
    return v ? unb64(v) : null
  } catch {
    return null
  }
}

export async function saveDataKey(userId: number, key: Bytes | null) {
  try {
    if (key) localStorage.setItem(dataKeyName(userId), await sealLocal(b64(key)))
    else localStorage.removeItem(dataKeyName(userId))
  } catch {
    // storage unavailable: key is kept for this session only
  }
}

// seals local-only data at rest (the outbox) with a non-exportable key
async function vaultKey(): Promise<CryptoKey> {
  const hit = await idbDo<CryptoKey | undefined>('readonly', (s) => s.get('vault'))
  if (hit) return hit
  const k = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt'])
  await idbDo<void>('readwrite', (s) => s.put(k, 'vault'))
  return k
}

export async function sealLocal(v: unknown): Promise<string> {
  const iv = random(12)
  const ct = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, await vaultKey(), te.encode(JSON.stringify(v)))) as Bytes
  return b64(concat(iv, ct))
}

export async function openLocal<T>(s: string): Promise<T | null> {
  try {
    const b = unb64(s)
    const pt = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: b.slice(0, 12) }, await vaultKey(), b.slice(12))
    return JSON.parse(td.decode(pt)) as T
  } catch {
    return null
  }
}

function label(): string {
  const ua = navigator.userAgent
  const browser = /Firefox\//.test(ua) ? 'Firefox' : /Edg\//.test(ua) ? 'Edge' : /Chrome\//.test(ua) ? 'Chrome' : /Safari\//.test(ua) ? 'Safari' : 'Browser'
  const os = /Android/.test(ua) ? 'Android' : /iPhone|iPad/.test(ua) ? 'iOS' : /Mac OS/.test(ua) ? 'macOS' : /Windows/.test(ua) ? 'Windows' : /Linux/.test(ua) ? 'Linux' : ''
  return os ? `${browser} on ${os}` : browser
}

async function works(fn: () => Promise<unknown>): Promise<boolean> {
  try {
    await fn()
    return true
  } catch {
    return false
  }
}

// null when supported, otherwise a user-facing reason
export async function unsupported(): Promise<string | null> {
  if (!globalThis.crypto?.subtle) return 'This browser does not support Web Crypto, which is needed for encryption.'
  if (typeof indexedDB === 'undefined') return 'This browser does not support IndexedDB, which is needed to store device keys.'
  const missing = []
  if (!(await works(() => crypto.subtle.generateKey({ name: 'X25519' }, false, ['deriveBits'])))) missing.push('X25519')
  if (!(await works(() => crypto.subtle.generateKey({ name: 'Ed25519' }, false, ['sign', 'verify'])))) missing.push('Ed25519')
  if (!missing.length) return null
  return `This browser does not support ${missing.join(' or ')}, which ${missing.length > 1 ? 'are' : 'is'} needed for encryption.`
}

// loads or enrolls this browser, rotates the enc key daily and prunes keys no longer needed
export async function ensureDevice(userId: number, ttlMax: number): Promise<Device> {
  let d = await loadLocal(userId)
  const server = await api.get<OwnDevice[]>('/api/devices')
  const onServer = d && server.find((x) => x.id === d!.id)
  if (d && !onServer) d = undefined

  if (!d) {
    const s = await newEd25519()
    const { id } = await api.post<{ id: string }>('/api/devices', { label: label(), sign_pub: b64(s.pub) })
    d = { id, signPub: b64(s.pub), signPriv: s.priv, enc: [] }
  }

  const t = now()
  const cur = d.enc.at(-1)
  if (!cur || t - cur.created_at > DAY) {
    const k = await newX25519()
    const sig = await sign(d.signPriv, encCertBytes(d.id, k.pub, t))
    await api.put(`/api/devices/${d.id}/key`, { enc_pub: b64(k.pub), created_at: t, sig: b64(sig) })
    d.enc.push({ pub: b64(k.pub), priv: k.priv, created_at: t })
  } else if (onServer) {
    // re-registers the current key, binding a fresh sign-in to a known device
    const sig = await sign(d.signPriv, encCertBytes(d.id, unb64(cur.pub), cur.created_at))
    await api.put(`/api/devices/${d.id}/key`, { enc_pub: cur.pub, created_at: cur.created_at, sig: b64(sig) })
  }

  d.enc = d.enc.filter((k, i) => i === d!.enc.length - 1 || k.created_at + DAY + ttlMax > t)
  await saveLocal(userId, d)
  return d
}

// an encryption key is valid only if signed by the device's own identity key
export async function certOk(d: Partial<PublicDevice>): Promise<boolean> {
  if (!d.id || !d.sign_pub || !d.enc_pub || !d.enc_sig || d.enc_created_at === undefined) return false
  return verify(unb64(d.sign_pub), unb64(d.enc_sig), encCertBytes(d.id, unb64(d.enc_pub), d.enc_created_at))
}

async function seal(dev: Device, recipients: (readonly [PublicDevice, 'to' | 'from'])[], ttl: number, plain: Plain, id: string) {
  const aad = te.encode(id)
  const ck = random(16)
  const nonce = random(12)
  const body = b64(concat(nonce, await aesGcm('encrypt', ck, nonce, aad, te.encode(JSON.stringify(plain)))))
  const stanza = async ([d, o]: readonly [PublicDevice, 'to' | 'from']): Promise<Stanza> => ({ d: d.id, k: d.enc_pub, w: b64(await hpkeSeal(unb64(d.enc_pub), INFO, aad, ck)), o })
  const keys = await Promise.all(recipients.map(stanza))
  const m = { id, from_device: dev.id, ttl, body, keys }
  return { ...m, sig: b64(await sign(dev.signPriv, messageSigBytes(m))) }
}

function withSelf(dev: Device, own: PublicDevice[]): PublicDevice[] {
  const mine = dev.enc.at(-1)!
  return own.some((d) => d.id === dev.id) ? own.map((d) => (d.id === dev.id ? { ...d, enc_pub: mine.pub } : d)) : [...own, { id: dev.id, enc_pub: mine.pub } as PublicDevice]
}

// sealed for every recipient device and every sender device (the sender's copies)
export function sealMail(dev: Device, to: PublicDevice[], own: PublicDevice[], ttl: number, plain: Plain, id: string = crypto.randomUUID()) {
  return seal(dev, [...to.map((d) => [d, 'to'] as const), ...withSelf(dev, own).map((d) => [d, 'from'] as const)], ttl, plain, id)
}

export function sealCopy(dev: Device, devices: PublicDevice[], ttl: number, plain: Plain, id: string, self: boolean) {
  return seal(dev, (self ? withSelf(dev, devices) : devices).map((d) => [d, 'to'] as const), ttl, plain, id)
}

async function decrypt(dev: Device, m: Mail): Promise<Plain | null> {
  const st = m.keys.find((k) => k.d === dev.id)
  const key = st && dev.enc.find((e) => e.pub === st.k)
  if (!st || !key) return null
  try {
    const aad = te.encode(m.id)
    const ck = await hpkeOpen(key.priv, unb64(key.pub), unb64(st.w), INFO, aad)
    const body = unb64(m.body)
    return JSON.parse(td.decode(await aesGcm('decrypt', ck, body.slice(0, 12), aad, body.slice(12)))) as Plain
  } catch {
    return null
  }
}

// null if not sealed for this device or not signed by a device the sender's server lists
export async function openMail(dev: Device, m: Mail, signers: Record<string, string>): Promise<Plain | null> {
  return (await signedBy(m, signers)) ? decrypt(dev, m) : null
}

export async function signedBy(m: Mail, signers: Record<string, string>): Promise<boolean> {
  const pub = signers[m.from_device]
  return !!pub && verify(unb64(pub), unb64(m.sig), messageSigBytes(m))
}

// group mail names its sender inside the plaintext; the caller verifies the signature before using anything else
export const openSealed = decrypt

// the caller verifies the hello against the devices behind its card
export async function openHello(dev: Device, m: Mail): Promise<Card | null> {
  const p = await decrypt(dev, m)
  return p?.t === 'hello' ? p.card : null
}
