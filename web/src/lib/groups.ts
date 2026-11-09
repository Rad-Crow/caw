// group state and its merge rules; see docs/protocol.md, section 9. no imports, so node can test it directly

export interface Card {
  id: string
  server: string
  account: string
  name: string
  link_id: string
  token: string
  gen: number
}

export type Live = { u: number; c: Card }
export type Gone = { u: number; gone: true }
export type Entry = Live | Gone

export interface Register<T> {
  v: T
  u: number
}

export interface State {
  name: Register<string>
  ttl: Register<number>
  members: Record<string, Entry>
}

export interface Own {
  link_id: string
  secret: string
  base: number
  gen: number
}

export interface Group extends State {
  id: string
  me: string
  own: Own
  invites: Record<string, { member: string; expires: number }>
  inviter?: { contact: string; member: string }
  // when this account learned each member left, in its server's time; their earlier mail stays readable
  left?: Record<string, number>
  created_at: number
}

export interface Applied {
  state: State
  added: string[]
  gone: string[]
  renamed: boolean
  timer: boolean
}

export const MAX_ENTRIES = 256
const AHEAD_MS = 86_400_000
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const TOKEN = /^[A-Za-z0-9_-]{43}$/
const SERVER = /^https?:\/\/[^/\s]+$/

export const isLive = (e: Entry | undefined): e is Live => !!e && 'c' in e
export const liveCards = (s: State): Card[] => Object.values(s.members).filter(isLive).map((e) => e.c)
export const goneCount = (s: State): number => Object.values(s.members).filter((e) => !isLive(e)).length
export const ownGen = (g: Group): number => Math.max(0, goneCount(g) - g.own.base)

const chars = (s: string) => [...s].length
const text = (v: unknown, max: number): v is string => typeof v === 'string' && chars(v) >= 1 && chars(v) <= max
const stamp = (v: unknown): v is number => Number.isSafeInteger(v) && (v as number) >= 0
const obj = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v)

export const isUuid = (v: unknown): v is string => typeof v === 'string' && UUID.test(v)

export function validCard(c: unknown, id: string): c is Card {
  if (!obj(c)) return false
  return (
    c.id === id &&
    isUuid(id) &&
    isUuid(c.link_id) &&
    typeof c.server === 'string' &&
    c.server.length <= 255 &&
    SERVER.test(c.server) &&
    text(c.account, 64) &&
    text(c.name, 64) &&
    typeof c.token === 'string' &&
    TOKEN.test(c.token) &&
    stamp(c.gen)
  )
}

export const card = (c: Card): Card => ({ id: c.id, server: c.server, account: c.account, name: c.name, link_id: c.link_id, token: c.token, gen: c.gen })

export function digestList(s: State): string {
  return Object.keys(s.members)
    .sort()
    .map((id) => {
      const e = s.members[id]
      return `${id}:${isLive(e) ? e.c.gen : 'x'}`
    })
    .join(',')
}

const newer = <T extends string | number>(a: Register<T>, b: Register<T>) => b.u > a.u || (b.u === a.u && b.v > a.v)
const earlier = (u1: number, id1: string, u2: number, id2: string) => u1 < u2 || (u1 === u2 && id1 < id2)

function register<T extends string | number>(cur: Register<T>, raw: unknown, ok: (v: unknown) => v is T, now: number): Register<T> | null {
  if (!obj(raw) || !ok(raw.v) || !stamp(raw.u) || raw.u > now + AHEAD_MS) return null
  const r = { v: raw.v, u: raw.u }
  return newer(cur, r) ? r : null
}

const isName = (v: unknown): v is string => text(v, 64)
const isTtl = (v: unknown): v is number => Number.isSafeInteger(v) && (v as number) > 0

// a state from member `sender`; null if not accepted. bootstrap: the inviter's first state, verified by the caller
export function applyState(local: State, incoming: unknown, sender: string, now: number, bootstrap = false): Applied | null {
  if (!obj(incoming)) return null
  if (!bootstrap && !isLive(local.members[sender])) return null
  const members: Record<string, Entry> = { ...local.members }
  const added: string[] = []
  const gone: string[] = []
  for (const [x, e] of Object.entries(obj(incoming.members) ? incoming.members : {})) {
    if (!obj(e) || !isUuid(x) || !stamp(e.u)) continue
    const l = members[x]
    if (l && !isLive(l)) continue
    if (e.gone === true) {
      members[x] = { u: Math.max(l?.u ?? 0, e.u), gone: true }
      if (l) gone.push(x)
    } else if (!validCard(e.c, x)) {
      continue
    } else if (!l) {
      members[x] = { u: e.u, c: card(e.c) }
      added.push(x)
    } else if (sender === x && e.c.server === l.c.server && e.c.account === l.c.account && e.c.link_id === l.c.link_id && e.c.gen > l.c.gen) {
      members[x] = { u: e.u, c: card(e.c) }
    }
  }
  // one live entry per account: the earliest stays
  const first = new Map<string, string>()
  for (const [id, e] of Object.entries(members)) {
    if (!isLive(e)) continue
    const key = `${e.c.server}\n${e.c.account}`
    const other = first.get(key)
    if (other === undefined) {
      first.set(key, id)
      continue
    }
    const o = members[other] as Live
    const [keep, drop] = earlier(o.u, other, e.u, id) ? [other, id] : [id, other]
    first.set(key, keep)
    members[drop] = { u: (members[drop] as Live).u, gone: true }
    const i = added.indexOf(drop)
    if (i >= 0) added.splice(i, 1)
    else gone.push(drop)
  }
  if (Object.keys(members).length > MAX_ENTRIES) return null
  const name = register(local.name, incoming.name, isName, now)
  const ttl = register(local.ttl, incoming.ttl, isTtl, now)
  return {
    state: { name: name ?? local.name, ttl: ttl ?? local.ttl, members },
    added,
    gone,
    renamed: !!name,
    timer: !!ttl,
  }
}

// copies of one group held by the account's own devices; order does not matter
export function mergeGroup(a: Group, b: Group, now: number): Group {
  const members: Record<string, Entry> = { ...a.members }
  for (const [id, e] of Object.entries(b.members)) {
    const l = members[id]
    if (!l) members[id] = e
    else if (!isLive(l) || !isLive(e)) members[id] = { u: Math.max(l.u, e.u), gone: true }
    else if (e.c.gen > l.c.gen || (e.c.gen === l.c.gen && (e.u > l.u || (e.u === l.u && e.c.link_id > l.c.link_id)))) members[id] = e
  }
  const mine = members[a.me]
  const link = isLive(mine) ? mine.c.link_id : null
  const own = a.own.link_id === link ? a.own : b.own.link_id === link ? b.own : a.own.link_id > b.own.link_id ? a.own : b.own
  const invites: Group['invites'] = {}
  for (const src of [a.invites, b.invites]) {
    for (const [contact, inv] of Object.entries(src)) {
      if (inv.expires > now / 1000 && !(invites[contact]?.expires >= inv.expires)) invites[contact] = inv
    }
  }
  const left: Record<string, number> = { ...(a.left ?? {}) }
  for (const [id, t] of Object.entries(b.left ?? {})) left[id] = Math.min(left[id] ?? t, t)
  const g: Group = {
    ...a,
    name: newer(a.name, b.name) ? b.name : a.name,
    ttl: newer(a.ttl, b.ttl) ? b.ttl : a.ttl,
    members,
    own: { ...own, gen: Math.max(a.own.gen, b.own.gen) },
    invites,
  }
  if (!(a.inviter && b.inviter)) delete g.inviter
  if (Object.keys(left).length) g.left = left
  else delete g.left
  return g
}
