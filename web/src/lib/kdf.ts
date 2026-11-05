import { argon2id } from 'hash-wasm'
import { aesGcm, b64, concat, random, unb64, type Bytes } from './crypto'

// see docs/protocol.md, sections 3.1 and 4.1: first half is the login key sent to the server, second half never leaves the device
export interface Kdf {
  salt: string
  m: number
  t: number
  p: number
}

export const KDF = { m: 65536, t: 3, p: 1 }
const KEY_AAD = new TextEncoder().encode('caw-key-v1')

export const newKdf = (): Kdf => ({ salt: b64(random(16)), ...KDF })

export async function derive(password: string, k: Kdf): Promise<{ loginKey: string; pwKey: Bytes }> {
  const out = await argon2id({
    password: password.normalize('NFC'),
    salt: unb64(k.salt),
    parallelism: k.p,
    iterations: k.t,
    memorySize: k.m,
    hashLength: 64,
    outputType: 'binary',
  })
  return { loginKey: b64(out.slice(0, 32) as Bytes), pwKey: out.slice(32) as Bytes }
}

// "salt.m.t.p.nonce+ct": embeds its kdf params so it still opens after the login salt changes
export async function wrapKey(dataKey: Bytes, pwKey: Bytes, k: Kdf): Promise<string> {
  const iv = random(12)
  return `${k.salt}.${k.m}.${k.t}.${k.p}.${b64(concat(iv, await aesGcm('encrypt', pwKey, iv, KEY_AAD, dataKey)))}`
}

export function wrappedKdf(w: string): Kdf | null {
  const [salt, m, t, p] = w.split('.')
  return salt && m && t && p ? { salt, m: Number(m), t: Number(t), p: Number(p) } : null
}

export const sameKdf = (a: Kdf, b: Kdf) => a.salt === b.salt && a.m === b.m && a.t === b.t && a.p === b.p

export async function unwrapKey(w: string, pwKey: Bytes): Promise<Bytes | null> {
  try {
    const b = unb64(w.split('.')[4])
    return await aesGcm('decrypt', pwKey, b.slice(0, 12), KEY_AAD, b.slice(12))
  } catch {
    return null
  }
}

// unwraps with a typed password, reusing an existing derivation when the params match
export async function unwrapWith(w: string, password: string, have?: { kdf: Kdf; pwKey: Bytes }): Promise<Bytes | null> {
  const k = wrappedKdf(w)
  if (!k) return null
  const pwKey = have && sameKdf(have.kdf, k) ? have.pwKey : (await derive(password, k)).pwKey
  return unwrapKey(w, pwKey)
}
