import { ApiError } from './api'
import { b64, unb64 } from './crypto'
import { normServer } from './groups'

// an account on any home server, including this one; see docs/protocol.md, section 4.2
export interface Contact {
  // identical across the account's devices; synced through the vault
  id: string
  name: string
  alias?: string
  favorite?: boolean
  server: string
  account: string
  // outbound: their link on their server
  out: { link_id: string; token: string }
  // inbound: the account's link on the home server
  in: string
  status: 'pending' | 'active'
  created_at: number
  ttl?: number
  words?: string[]
  // seconds; when a pending request ends
  expires_at?: number
  // ms; last writer wins on merge
  updated_at: number
  // device-local: route id and reachability state
  num: number
  unreachable_since?: number
  asked_at?: number
  // known identity keys of their devices, so messages verify while their server is down
  signers?: Record<string, string>
}

export const LOCAL_FIELDS = ['num', 'unreachable_since', 'asked_at', 'signers'] as const

export class Gone extends Error {}

export class Unreachable extends Error {
  constructor(public retryAfter = 0) {
    super('unreachable')
  }
}

export type Remote = Pick<Contact, 'server' | 'out'>

export const linkAuth = (c: Remote) => `Link ${c.out.link_id}.${c.out.token}`

// 410 means the link is gone; no response or a 5xx means the server is unreachable
export async function linkReq<T>(c: Remote, method: string, path: string, body?: unknown): Promise<T> {
  let res: Response
  try {
    res = await fetch(c.server + path, {
      method,
      // requests for different links share URLs, so a cached response would answer for another link
      cache: 'no-store',
      headers: body === undefined ? { authorization: linkAuth(c) } : { authorization: linkAuth(c), 'content-type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch {
    throw new Unreachable()
  }
  if (res.status === 410) throw new Gone()
  if (res.status >= 500) throw new Unreachable()
  if (res.status === 429) throw new Unreachable(Number(res.headers.get('retry-after')) || 60)
  const text = await res.text()
  let data: any = null
  try {
    data = text ? JSON.parse(text) : null
  } catch {
    data = null
  }
  if (!res.ok) throw new ApiError(res.status, data?.error ?? res.statusText)
  return data as T
}

export function wsBase(server: string): string {
  return server.replace(/^http/, 'ws')
}

// payload of a QR or pasted pairing code
export interface Code {
  v: 2
  s: string
  t: string
  a: string
  n: string
  // the account's request key, which seals the requester's name
  k?: string
}

const PREFIX = 'caw:2:'
export const OLD_PREFIX = 'caw:1:'

export function encodeCode(c: Code): string {
  return PREFIX + b64(new TextEncoder().encode(JSON.stringify(c)) as Uint8Array<ArrayBuffer>)
}

export function decodeCode(text: string): Code | null {
  const t = text.trim()
  if (!t.startsWith(PREFIX)) return null
  try {
    const c = JSON.parse(new TextDecoder().decode(unb64(t.slice(PREFIX.length)))) as Code
    const s = typeof c.s === 'string' ? normServer(c.s) : null
    return c.v === 2 && s && c.t && c.a ? { ...c, s } : null
  } catch {
    return null
  }
}

export function hostOf(server: string): string {
  try {
    return new URL(server).host
  } catch {
    return server
  }
}
