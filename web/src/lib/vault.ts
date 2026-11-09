import { aesGcm, b64, concat, random, unb64, type Bytes } from './crypto'
import { LOCAL_FIELDS, type Contact } from './contacts'
import type { Group } from './groups'

export type Synced = Omit<Contact, (typeof LOCAL_FIELDS)[number]>

// one per account; see docs/protocol.md, section 4. removals are kept as markers so a stale copy cannot restore a contact
export interface VaultDoc {
  v: 1
  contacts: Record<string, { u: number; c?: Synced; gone?: true }>
  groups?: Record<string, { u: number; g?: Group; gone?: true }>
  [key: string]: unknown
}

export interface VaultResp {
  version: number
  blob: string | null
  wrapped_key: string | null
}

const AAD = new TextEncoder().encode('caw-vault-v1')

export function synced(c: Contact): Synced {
  const s: Record<string, unknown> = { ...c }
  for (const k of LOCAL_FIELDS) delete s[k]
  return s as Synced
}

export async function sealVault(key: Bytes, doc: VaultDoc): Promise<string> {
  const iv = random(12)
  return b64(concat(iv, await aesGcm('encrypt', key, iv, AAD, new TextEncoder().encode(JSON.stringify(doc)))))
}

export async function openVault(key: Bytes, blob: string): Promise<VaultDoc | null> {
  try {
    const b = unb64(blob)
    const doc = JSON.parse(new TextDecoder().decode(await aesGcm('decrypt', key, b.slice(0, 12), AAD, b.slice(12)))) as VaultDoc
    return doc?.v === 1 && doc.contacts ? doc : null
  } catch {
    return null
  }
}
