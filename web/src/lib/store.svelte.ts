import wordsRaw from '../../../protocol/words.txt?raw'
import { api, ApiError, fmtTtl, type Me, type Peer } from './api'
import { calls, type CallEnd } from './call.svelte'
import { decodeCode, encodeCode, Gone, hostOf, linkReq, Unreachable, wsBase, type Contact, type Remote } from './contacts'
import { b64, callSigBytes, pairingWords, random, sign, unb64, verify, type Bytes } from './crypto'
import {
  certOk,
  ensureDevice,
  forgetLocal,
  loadDataKey,
  openHello,
  openLocal,
  openMail,
  saveDataKey,
  sealLocal,
  sealMail,
  signedBy,
  unsupported,
  type Card,
  type Device,
  type Mail,
  type OwnDevice,
  type Plain,
  type PublicDevice,
} from './device'
import { derive, newKdf, unwrapWith, wrapKey, type Kdf } from './kdf'
import { openVault, sealVault, synced, type VaultDoc, type VaultResp } from './vault'
import { dropMedia, encryptFile, prepare, seedMedia, TooLarge, upload, type MediaPlain, type Prepared } from './media'
import { privateMessages } from './prefs'
import { Socket } from './socket'
import { tones } from './tones'

const WORDS = wordsRaw.trim().split('\n')
const DAY = 86400
// retry delays (s) for an unreachable server, one attempt per step
const BACKOFF = [60, 300, 900, 1800, 3600, 7200, 14400, 21600]
// unanswered pairings expire after this
const PENDING_SECS = 1800

export interface Message {
  id: string
  from_user: number
  to_user: number
  created_at: number
  expires_at: number
  plain: Plain | null
  // queued in the outbox, not yet delivered
  pending?: boolean
}

type Outgoing = { id: string; to: number; plain: Plain; blobs?: string[]; at: number; ttl: number }

export interface CawEvent {
  at: number
  text: string
}

export interface Prompt {
  link_id: string
  card: Card
  words: string[]
}

// derived from a typed password; held only until the vault is unlocked
export interface Secret {
  password: string
  kdf: Kdf
  pwKey: Bytes
}


// own devices observed over time, so the device check tolerates recent removals and key rotations
type Known = Record<string, { keys: string[]; sign: string; label: string; seen: number }>

type PeerDevices = { at: number; devices: PublicDevice[] }

export interface NewDevice {
  id: string
  label: string
}

interface Config {
  server_name: string
  registration_open: boolean
  message_ttl_default: number
  message_ttl_min: number
  message_ttl_max: number
  max_upload_mb: number
}

export type Attachment = { status: 'preparing' | 'ready' | 'sending'; progress: number; prepared?: Prepared }

function readLocal<T>(key: string, fallback: T): T {
  try {
    const v = localStorage.getItem(key)
    return v ? (JSON.parse(v) as T) : fallback
  } catch {
    return fallback
  }
}

function writeLocal(key: string, v: unknown) {
  try {
    localStorage.setItem(key, JSON.stringify(v))
  } catch {
    // storage unavailable: unread state is not persisted
  }
}

const isControl = (p: Plain) => p.t === 'hello' || p.t === 'accept' || p.t === 'decline' || p.t === 'timer' || p.t === 'bye'

export function plainText(p: Plain): string {
  if (p.t === 'media') {
    const kind = p.kind === 'video' ? 'Video' : 'Photo'
    return p.text ? `${kind}: ${p.text}` : kind
  }
  return p.t === 'text' ? p.text : ''
}

export function callText(n: Message, me: number | undefined): string {
  if (n.plain?.t !== 'call') return ''
  const mine = n.from_user === me
  const secs = n.plain.secs ?? 0
  const dur = `${Math.floor(secs / 60)}:${String(secs % 60).padStart(2, '0')}`
  switch (n.plain.outcome) {
    case 'answered':
      return `${mine ? 'Outgoing' : 'Incoming'} call \u00b7 ${dur}`
    case 'declined':
      return mine ? 'Outgoing call, declined' : 'Incoming call, declined'
    default:
      return mine ? 'Outgoing call, no answer' : 'Missed call'
  }
}

class Store {
  ready = $state(false)
  me = $state<Me | null>(null)
  config = $state<Config>({ server_name: 'Caw', registration_open: false, message_ttl_default: 86400, message_ttl_min: 300, message_ttl_max: 604800, max_upload_mb: 25 })
  contacts = $state<Contact[]>([])
  messages = $state<Record<number, Message[]>>({})
  events = $state<Record<number, CawEvent[]>>({})
  opened = $state<number[]>([])
  // unsent text per caw; memory only, cleared on reload
  drafts = $state<Record<number, string>>({})
  attachments = $state.raw<Record<number, Attachment | undefined>>({})
  // persisted across reloads, sealed with a browser-local key
  outbox = $state<Outgoing[]>([])
  seen = $state<Record<number, number>>({})
  // pairing requests from redeemed codes
  prompts = $state<Prompt[]>([])
  // contact whose server is unreachable; the user chooses to keep trying or remove
  unreachable = $state<number | null>(null)
  composeFor = $state<number | null>(null)
  selected = $state<number | null>(null)
  connected = $state(false)
  deviceError = $state<string | null>(null)
  path = $state(location.pathname)
  toast = $state<string | null>(null)
  clock = $state(Math.floor(Date.now() / 1000))
  // vault key is wrapped under the password set before an admin reset
  vaultLocked = $state(false)
  ownDevices = $state<OwnDevice[]>([])
  newDevices = $state<NewDevice[]>([])
  // contacts whose messages were also sealed for a device the home server does not list
  deviceAlerts = $state<Record<number, boolean>>({})
  // set by "that wasn't me": prompts a password change and vault key replacement
  secureNow = $state(false)

  device: Device | null = null
  private socket: Socket | null = null
  private toastTimer: number | null = null
  private skew = 0
  private peers = new Map<string, PeerDevices>()
  private retry = new Map<string, { at: number; step: number }>()
  private outboxSave: Promise<void> = Promise.resolve()
  private contactsSave: Promise<void> = Promise.resolve()
  private flushing = false
  private dataKey: Bytes | null = null
  // key from before a rotation, to merge a copy written concurrently by another device
  private prevKey: Bytes | null = null
  private pending: Secret | null = null
  private vaultVersion = 0
  private gone: Record<string, number> = {}
  private pushQueued = false
  private pushing: Promise<void> = Promise.resolve()
  private known: Known = {}
  private enrolledAt = 0
  private ownAt = 0
  private devicesLoad: Promise<void> | null = null
  private contactsReady: Promise<void> = Promise.resolve()
  private markContactsReady: () => void = () => {}
  private devicesAgain = false

  constructor() {
    calls.bind({
      own: (m) => this.socket?.send(m) ?? false,
      guestUrl: (num) => {
        const c = this.contact(num)
        if (!c || c.status !== 'active') return null
        return (client) => `${wsBase(c.server)}/api/link/ws?link=${c.out.link_id}.${c.out.token}&client=${client}`
      },
      contactFor: (linkId) => {
        const c = this.contacts.find((x) => x.in === linkId && x.status === 'active')
        return c ? this.peerOf(c) : null
      },
      flash: (t) => this.flash(t),
      notify: (title, body) => this.notify(title, body),
      name: (u) => this.nameFor(u),
      ended: (e) => this.callEnded(e),
      sign: async (callId, d) => {
        const dev = this.device
        if (!dev) return null
        return { device: dev.id, sig: b64(await sign(dev.signPriv, callSigBytes(callId, d.type ?? '', d.sdp ?? ''))) }
      },
      verify: (num, callId, d, device, sig) => this.verifyCall(num, callId, d, device, sig),
    })
    window.addEventListener('popstate', () => {
      this.path = location.pathname
      this.syncPath()
    })
    document.addEventListener('visibilitychange', () => {
      if (!document.hidden && this.selected !== null) this.markSeen(this.selected)
    })
    window.setInterval(() => this.expire(), 5000)
    window.setInterval(() => this.refreshKeys(), 3600_000)
    window.setInterval(() => this.outbox.length && this.flush(), 15_000)
    window.addEventListener('online', () => this.flush())
  }

  // server time, so expiry does not depend on an accurate local clock
  serverNow(): number {
    return Math.floor(Date.now() / 1000) + this.skew
  }

  private stamp(): number {
    return Date.now() + this.skew * 1000
  }

  contact(num: number): Contact | undefined {
    return this.contacts.find((c) => c.num === num)
  }

  private peerOf(c: Contact): Peer {
    return { id: c.num, host: hostOf(c.server), name: c.alias ?? c.name }
  }

  peer(num: number): Peer | undefined {
    const c = this.contact(num)
    return c && this.peerOf(c)
  }

  // open caws first by recency, then the rest by name
  contactList(query: string): number[] {
    const q = query.trim().toLowerCase()
    const rank = (id: number) =>
      this.messages[id]?.at(-1)?.created_at ?? (this.opened.includes(id) || id === this.selected ? Number.MAX_SAFE_INTEGER : -1)
    return this.contacts
      .filter((c) => !q || this.userName(c.num).toLowerCase().includes(q) || c.name.toLowerCase().includes(q) || hostOf(c.server).includes(q))
      .map((c) => c.num)
      .sort((a, b) => rank(b) - rank(a) || this.userName(a).localeCompare(this.userName(b)))
  }

  isOpen(id: number): boolean {
    return !!this.messages[id]?.length || this.opened.includes(id)
  }

  isFavorite(id: number): boolean {
    return !!this.contact(id)?.favorite
  }

  toggleFavorite(id: number) {
    const c = this.contact(id)
    if (!c) return
    if (c.favorite) delete c.favorite
    else c.favorite = true
    this.touch(c)
  }

  compose(id: number) {
    this.open(id)
    this.composeFor = id
  }

  unread(id: number): number {
    const since = this.seen[id] ?? 0
    return (this.messages[id] ?? []).filter((n) => n.from_user === id && n.created_at > since).length
  }

  get unreadTotal(): number {
    return Object.keys(this.messages).reduce((n, id) => n + this.unread(Number(id)), 0)
  }

  preview(id: number): { text: string; missed: boolean } {
    const c = this.contact(id)
    if (c?.status === 'pending') return { text: 'Waiting to accept', missed: false }
    const n = this.messages[id]?.at(-1)
    if (!n) return { text: hostOf(c?.server ?? ''), missed: false }
    if (!n.plain) return { text: 'Encrypted message', missed: false }
    if (n.plain.t === 'call') return { text: callText(n, this.me?.id), missed: n.plain.outcome === 'missed' && n.from_user === id }
    return { text: (n.from_user === this.me?.id ? 'You: ' : '') + plainText(n.plain), missed: false }
  }

  timerFor(id: number): number {
    return this.contact(id)?.ttl ?? this.config.message_ttl_default
  }

  navigate(path: string) {
    if (path !== location.pathname) history.pushState(null, '', path)
    this.path = path
    this.syncPath()
  }

  open(id: number) {
    this.navigate(`/c/${id}`)
  }

  private syncPath() {
    const m = this.path.match(/^\/c\/(\d+)$/)
    const id = m ? Number(m[1]) : null
    if (id !== this.selected) this.select(id)
  }

  flash(text: string) {
    this.toast = text
    if (this.toastTimer !== null) clearTimeout(this.toastTimer)
    this.toastTimer = window.setTimeout(() => (this.toast = null), 4000)
  }

  notify(title: string, body: string) {
    if (!document.hidden || !('Notification' in window) || Notification.permission !== 'granted') return
    const n = new Notification(title, { body, tag: 'caw', silent: true })
    n.onclick = () => {
      window.focus()
      n.close()
    }
  }

  async init() {
    try {
      this.config = await api.get('/api/config')
      document.title = this.config.server_name
      this.me = await api.get<Me>('/api/me')
      if (!this.me.must_change) await this.start()
      this.syncPath()
    } catch (e) {
      if (!(e instanceof ApiError && e.status === 401)) this.flash('Server unreachable.')
    } finally {
      this.ready = true
    }
  }

  // --- sign-in. only a key derived from the password is sent to the server

  async signIn(username: string, password: string) {
    const s = await api.post<Kdf>('/api/auth/salt', { username })
    const d = await derive(password, s)
    const res = await api.post<{ user: Me }>('/api/auth/login', { username, login_key: d.loginKey })
    await this.loggedIn(res.user, { password, kdf: { salt: s.salt, m: s.m, t: s.t, p: s.p }, pwKey: d.pwKey })
  }

  async register(body: { username: string; display_name: string; invite: string | null }, password: string) {
    const kdf = newKdf()
    const d = await derive(password, kdf)
    const res = await api.post<{ user: Me }>('/api/auth/register', { ...body, login_key: d.loginKey, salt: kdf.salt })
    await this.loggedIn(res.user, { password, kdf, pwKey: d.pwKey })
  }

  private async loggedIn(me: Me, secret: Secret) {
    this.me = me
    writeLocal('caw.username', me.username)
    this.navigate('/')
    if (me.must_change) return
    await this.start(secret)
  }

  // first sign-in with a password an admin set
  async choosePassword(password: string) {
    const kdf = newKdf()
    const d = await derive(password, kdf)
    this.me = await api.put<Me>('/api/me/login', { login_key: d.loginKey, salt: kdf.salt })
    await this.start({ password, kdf, pwKey: d.pwKey })
  }

  // rewraps the data key; with rotate, a new key replaces it so a removed device's copy stops working
  async changePassword(current: string, next: string, rotate = false) {
    const s = await api.post<Kdf>('/api/auth/salt', { username: this.me!.username })
    const cur = await derive(current, s)
    const kdf = newKdf()
    const d = await derive(next, kdf)
    let key = this.dataKey
    if (rotate && key) {
      await this.pullVault()
      this.prevKey = key
      key = random(32) as Bytes
    }
    this.me = await api.put<Me>('/api/me/login', {
      current_login_key: cur.loginKey,
      login_key: d.loginKey,
      salt: kdf.salt,
      wrapped_key: key ? await wrapKey(key, d.pwKey, kdf) : undefined,
    })
    if (rotate && key) {
      await this.setDataKey(key)
      await this.pushVault(true)
    }
  }

  async logout() {
    const uid = this.me?.id
    if (uid !== undefined) {
      try {
        for (const k of ['outbox', 'contacts', 'known', 'newdev']) localStorage.removeItem(`caw.${k}.${uid}`)
      } catch {
        // nothing stored
      }
      await saveDataKey(uid, null)
    }
    await api.post('/api/auth/logout').catch(() => {})
    this.stop()
    this.me = null
    this.navigate('/')
  }

  userName(id: number): string {
    const c = this.contact(id)
    return c ? (c.alias ?? c.name) : 'Unknown'
  }

  nameFor(p: Peer): string {
    const c = this.contact(p.id)
    return c?.alias ?? c?.name ?? p.name
  }

  accountName(id: number): string {
    return this.contact(id)?.name ?? ''
  }

  setAlias(id: number, name: string) {
    const c = this.contact(id)
    if (!c) return
    const n = name.trim()
    if (n && n !== c.name) c.alias = n
    else delete c.alias
    this.touch(c)
  }

  async start(secret?: Secret) {
    this.socket?.stop()
    const uid = this.me!.id
    this.seen = readLocal(`caw.seen.${uid}`, {})
    this.known = readLocal(`caw.known.${uid}`, {})
    this.newDevices = readLocal(`caw.newdev.${uid}`, [])
    this.socket = new Socket(
      (m) => this.onMessage(m),
      (open) => {
        this.connected = open
        if (open) this.flush()
        else this.checkSession()
      },
    )
    this.contactsReady = new Promise((resolve) => (this.markContactsReady = resolve))
    this.socket.start()
    await this.setupDevice(secret)
    this.markContactsReady()
  }

  private async setupDevice(secret?: Secret) {
    const missing = await unsupported()
    if (missing) {
      this.deviceError = missing
      return
    }
    try {
      // without the password or a stored key there is no vault key; restart at sign-in
      const kept = secret ? null : await loadDataKey(this.me!.id)
      if (!secret && !kept) return this.signedOut('Sign in again to sync your contacts.')
      await this.loadContacts()
      this.markContactsReady()
      this.device = await ensureDevice(this.me!.id, this.config.message_ttl_max)
      this.deviceError = null
      await this.refreshDevices()
      if (secret) await this.establishKey(secret)
      else await this.setDataKey(kept)
      await this.pullVault()
      await this.reconcile()
      await this.loadMail()
      await this.loadOutbox()
    } catch (e) {
      this.deviceError = `Device setup failed: ${e instanceof Error ? e.message : e}`
    }
  }

  // keys rotate daily; long-lived tabs refresh them
  private async refreshKeys() {
    if (!this.device || !this.me) return
    try {
      this.device = await ensureDevice(this.me.id, this.config.message_ttl_max)
    } catch {
      this.checkSession()
    }
  }

  private stop() {
    calls.hangup()
    this.socket?.stop()
    this.socket = null
    this.device = null
    this.contacts = []
    this.messages = {}
    this.events = {}
    this.opened = []
    this.drafts = {}
    this.outbox = []
    this.prompts = []
    this.unreachable = null
    for (const id of Object.keys(this.attachments)) this.detach(Number(id))
    this.selected = null
    this.vaultLocked = false
    this.ownDevices = []
    this.newDevices = []
    this.deviceAlerts = {}
    this.secureNow = false
    this.dataKey = this.prevKey = this.pending = null
    this.vaultVersion = 0
    this.gone = {}
    this.known = {}
    this.peers.clear()
    this.retry.clear()
  }

  private async signedOut(notice: string) {
    await api.post('/api/auth/logout').catch(() => {})
    this.stop()
    this.me = null
    this.navigate('/')
    this.flash(notice)
  }

  private async checkSession() {
    try {
      await api.get('/api/me')
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        this.stop()
        this.me = null
      }
    }
  }

  // --- vault: contacts shared across the account's devices

  private async setDataKey(key: Bytes | null) {
    this.dataKey = key
    this.vaultLocked = !key && !!this.pending
    if (key) this.pending = null
    await saveDataKey(this.me!.id, key)
  }

  // data key from the password, else one stored on this device, else a new one for an empty vault
  private async establishKey(s: Secret) {
    const r = await api.get<VaultResp>('/api/vault')
    let key = r.wrapped_key ? await unwrapWith(r.wrapped_key, s.password, s) : null
    if (!key) {
      const kept = await loadDataKey(this.me!.id)
      if (kept && (!r.blob || (await openVault(kept, r.blob)))) key = kept
      else if (!r.blob) key = random(32) as Bytes
      if (key) await api.put('/api/vault/key', { wrapped_key: await wrapKey(key, s.pwKey, s.kdf) })
    }
    if (!key) this.pending = s
    await this.setDataKey(key)
  }

  // unlocks a vault wrapped under the password set before an admin reset
  async unlockOld(password: string) {
    const s = this.pending
    if (!s) throw new Error('Sign in again to unlock your contacts.')
    const r = await api.get<VaultResp>('/api/vault')
    const key = r.wrapped_key ? await unwrapWith(r.wrapped_key, password) : null
    if (!key || (r.blob && !(await openVault(key, r.blob)))) throw new Error('Incorrect password.')
    await api.put('/api/vault/key', { wrapped_key: await wrapKey(key, s.pwKey, s.kdf) })
    await this.setDataKey(key)
    await this.pullVault()
  }

  // discards the locked contact list; this device's contacts become the new list
  async startOver() {
    const s = this.pending
    if (!s) throw new Error('Sign in again to unlock your contacts.')
    const key = random(32) as Bytes
    await api.put('/api/vault/key', { wrapped_key: await wrapKey(key, s.pwKey, s.kdf) })
    await this.setDataKey(key)
    await this.pushVault(true)
  }

  private async openAny(blob: string): Promise<VaultDoc | null> {
    for (const k of [this.dataKey, this.prevKey]) {
      const doc = k ? await openVault(k, blob) : null
      if (doc) return doc
    }
    return null
  }

  private doc(): VaultDoc {
    const contacts: VaultDoc['contacts'] = {}
    const cutoff = this.stamp() - 180 * DAY * 1000
    for (const [id, u] of Object.entries(this.gone)) if (u > cutoff) contacts[id] = { u, gone: true }
    for (const c of this.contacts) contacts[c.id] = { u: c.updated_at, c: synced($state.snapshot(c) as Contact) }
    return { v: 1, contacts }
  }

  // newer entry wins; true when local state has entries the copy lacks
  private merge(doc: VaultDoc): boolean {
    let changed = false
    for (const [id, e] of Object.entries(doc.contacts)) {
      const i = this.contacts.findIndex((c) => c.id === id)
      const local = i >= 0 ? this.contacts[i] : undefined
      if (e.u <= (local?.updated_at ?? this.gone[id] ?? -1)) continue
      changed = true
      if (e.gone || !e.c) {
        this.gone[id] = e.u
        if (local) this.drop(local)
        continue
      }
      delete this.gone[id]
      if (local) {
        this.contacts[i] = { ...e.c, num: local.num, unreachable_since: local.unreachable_since, asked_at: local.asked_at, signers: local.signers }
      } else {
        this.contacts.push({ ...e.c, num: this.nextNum() })
        this.prompts = this.prompts.filter((p) => p.link_id !== e.c!.in)
      }
    }
    if (changed) this.saveContacts()
    return Object.entries(this.doc().contacts).some(([id, e]) => (doc.contacts[id]?.u ?? -1) < e.u)
  }

  private async pullVault() {
    if (!this.dataKey) return
    const r = await api.get<VaultResp>('/api/vault')
    const doc = r.blob ? await this.openAny(r.blob) : { v: 1 as const, contacts: {} }
    if (!doc) return this.keyReplaced()
    this.vaultVersion = r.version
    if (this.merge(doc)) this.schedulePush()
  }

  // another device replaced the data key ("that wasn't me"); signing in again fetches the new one
  private async keyReplaced() {
    await saveDataKey(this.me!.id, null)
    await this.signedOut('Your password was changed on another device. Sign in again.')
  }

  // coalesces changes into one push without timers, which stall in background tabs
  private schedulePush() {
    if (this.pushQueued) return
    this.pushQueued = true
    this.pushVault().catch((e) => console.warn('vault', e))
  }

  // merges into the latest copy, then compare-and-swap; retries if another device wins the race
  private pushVault(force = false): Promise<void> {
    const run = this.pushing.then(async () => {
      this.pushQueued = false
      for (let i = 0; i < 5 && this.dataKey; i++) {
        const r = await api.get<VaultResp>('/api/vault')
        const doc = r.blob ? await this.openAny(r.blob) : { v: 1 as const, contacts: {} }
        if (!doc && !force) return this.keyReplaced()
        this.vaultVersion = r.version
        if (doc && !this.merge(doc) && !force) return
        try {
          const res = await api.put<{ version: number }>('/api/vault', { version: r.version, blob: await sealVault(this.dataKey, this.doc()) })
          this.vaultVersion = res.version
          this.prevKey = null
          return
        } catch (e) {
          if (!(e instanceof ApiError && e.status === 409)) throw e
        }
      }
    })
    this.pushing = run.catch(() => {})
    return run
  }

  private touch(c: Contact, at = this.stamp()) {
    c.updated_at = Math.max(at, c.updated_at + 1)
    this.saveContacts()
    this.schedulePush()
  }

  private nextNum(): number {
    return Math.max(1_000_000, ...this.contacts.map((x) => x.num)) + 1
  }

  // --- local contact storage

  private saveContacts() {
    const key = `caw.contacts.${this.me!.id}`
    const v = { contacts: $state.snapshot(this.contacts), gone: { ...this.gone } }
    this.contactsSave = this.contactsSave.then(async () => {
      try {
        localStorage.setItem(key, await sealLocal(v))
      } catch {
        // storage unavailable: contacts remain in the vault
      }
    })
  }

  private async loadContacts() {
    let stored: string | null = null
    try {
      stored = localStorage.getItem(`caw.contacts.${this.me!.id}`)
    } catch {
      return
    }
    const v = stored ? await openLocal<{ contacts: Contact[]; gone: Record<string, number> }>(stored) : null
    this.contacts = Array.isArray(v?.contacts) ? v.contacts : []
    this.gone = v?.gone ?? {}
  }

  // drops contacts whose links were removed while offline, by the contact or another of the account's devices
  private async reconcile() {
    const links = await api.get<{ id: string }[]>('/api/links')
    const have = new Set(links.map((l) => l.id))
    for (const c of [...this.contacts]) {
      if (!have.has(c.in)) this.forget(c, `${this.userName(c.num)} is no longer a contact.`)
    }
  }

  // local only; the vault is either already current or updated by forget()
  private drop(c: Contact) {
    const num = c.num
    this.contacts = this.contacts.filter((x) => x.num !== num)
    delete this.messages[num]
    delete this.events[num]
    delete this.drafts[num]
    delete this.seen[num]
    delete this.deviceAlerts[num]
    this.opened = this.opened.filter((x) => x !== num)
    if (this.outbox.some((o) => o.to === num)) {
      this.outbox = this.outbox.filter((o) => o.to !== num)
      this.saveOutbox()
    }
    this.detach(num)
    this.peers.delete(c.id)
    if (this.unreachable === num) this.unreachable = null
    writeLocal(`caw.seen.${this.me!.id}`, this.seen)
    this.saveContacts()
    if (this.selected === num) this.navigate('/')
  }

  private forget(c: Contact, notice?: string) {
    if (!this.contacts.some((x) => x.id === c.id)) return
    this.gone[c.id] = this.stamp()
    this.drop(c)
    this.schedulePush()
    if (notice) this.flash(notice)
  }

  // also asks their server to remove the reverse link; not guaranteed
  async removeContact(num: number) {
    const c = this.contact(num)
    if (!c) return
    await api.del(`/api/links/${c.in}`).catch(() => {})
    linkReq(c, 'DELETE', '/api/link').catch(() => {})
    this.forget(c)
  }

  // --- own devices

  // one fetch at a time; a change during a fetch queues one more
  refreshDevices(): Promise<void> {
    if (this.devicesLoad) {
      this.devicesAgain = true
      return this.devicesLoad
    }
    this.devicesLoad = (async () => {
      try {
        do {
          this.devicesAgain = false
          await this.loadDevices()
        } while (this.devicesAgain)
      } finally {
        this.devicesLoad = null
      }
    })()
    return this.devicesLoad
  }

  private async loadDevices() {
    const list = await api.get<OwnDevice[]>('/api/devices')
    this.ownDevices = list
    this.ownAt = Date.now()
    const self = list.find((d) => d.id === this.device?.id)
    if (self) this.enrolledAt = self.created_at
    const first = !Object.keys(this.known).length
    const t = this.serverNow()
    for (const d of list) {
      const k = this.known[d.id]
      if (!k && !first && d.id !== this.device?.id && d.created_at >= this.enrolledAt && !this.newDevices.some((n) => n.id === d.id)) {
        this.newDevices = [...this.newDevices, { id: d.id, label: d.label || 'Device' }]
        this.notify('New device signed in', d.label || 'Device')
      }
      const keys = new Set([...(k?.keys ?? []), ...(d.keys ?? []), ...(d.enc_pub ? [d.enc_pub] : [])])
      this.known[d.id] = { keys: [...keys].slice(-30), sign: d.sign_pub, label: d.label, seen: t }
    }
    for (const [id, k] of Object.entries(this.known)) if (k.seen < t - this.config.message_ttl_max - DAY) delete this.known[id]
    this.newDevices = this.newDevices.filter((n) => list.some((d) => d.id === n.id))
    writeLocal(`caw.known.${this.me!.id}`, this.known)
    writeLocal(`caw.newdev.${this.me!.id}`, this.newDevices)
  }

  dismissDevice(id: string) {
    this.newDevices = this.newDevices.filter((n) => n.id !== id)
    writeLocal(`caw.newdev.${this.me!.id}`, this.newDevices)
  }

  async removeDevice(id: string) {
    await api.del(`/api/devices/${id}`)
    if (id === this.device?.id) {
      await forgetLocal(this.me!.id)
      location.reload()
      return
    }
    this.dismissDevice(id)
    await this.refreshDevices()
  }

  // unrecognized sign-in: removes the device, then requires a password change and vault key rotation
  async notMe(id: string) {
    await this.removeDevice(id)
    this.secureNow = true
  }

  private async ownSealTo(): Promise<PublicDevice[]> {
    if (Date.now() - this.ownAt > 60_000) await this.refreshDevices()
    const ok = await Promise.all(this.ownDevices.map(async (d) => ((await certOk(d)) ? (d as PublicDevice) : null)))
    return ok.filter((d): d is PublicDevice => !!d)
  }

  private ownSigners(): Record<string, string> {
    return Object.fromEntries(Object.entries(this.known).map(([id, k]) => [id, k.sign]))
  }

  // every stanza for this account must target a device the home server lists (or listed recently), with a known key
  private async checkDevices(c: Contact, m: Mail, ours: 'to' | 'from') {
    if (m.created_at < this.enrolledAt) return
    const odd = () => m.keys.filter((k) => k.o === ours && !this.known[k.d]?.keys.includes(k.k))
    if (!odd().length) return
    await this.refreshDevices().catch(() => {})
    if (!odd().length || this.deviceAlerts[c.num]) return
    this.deviceAlerts[c.num] = true
    this.notify('Check your devices', `A message with ${this.userName(c.num)} was also encrypted for a device that isn't yours.`)
  }

  dismissAlert(num: number) {
    delete this.deviceAlerts[num]
  }

  // --- pairing

  async showCode(): Promise<{ code: string; expires_at: number }> {
    if (!this.device) throw new Error(this.deviceError ?? 'This device is not set up yet.')
    const r = await api.post<{ ticket: string; expires_at: number }>('/api/pairings')
    const code = encodeCode({ v: 1, s: location.origin, t: r.ticket, a: String(this.me!.id), n: this.me!.display_name })
    return { code, expires_at: r.expires_at }
  }

  // scanning side: create an inbound link, redeem their ticket, then send a hello through it
  async enterCode(text: string): Promise<number> {
    if (!this.device) throw new Error(this.deviceError ?? 'This device is not set up yet.')
    const code = decodeCode(text)
    if (!code) throw new Error("That isn't a Caw code.")
    const account = String(this.me!.id)
    if (code.s === location.origin && code.a === account) throw new Error("That's your own code.")
    if (this.contacts.some((c) => c.account === code.a && c.server === code.s)) throw new Error('Already a contact.')
    const mine = await api.post<{ link_id: string; token: string }>('/api/links', { peer_server: code.s, peer_account: code.a })
    let out: { link_id: string; token: string }
    try {
      out = await redeem(code.s, { ticket: code.t, peer_server: location.origin, peer_account: account })
    } catch (e) {
      api.del(`/api/links/${mine.link_id}`).catch(() => {})
      throw e
    }
    const c: Contact = {
      id: crypto.randomUUID(),
      num: this.nextNum(),
      name: code.n || 'Contact',
      server: code.s,
      account: code.a,
      out,
      in: mine.link_id,
      status: 'pending',
      created_at: this.serverNow(),
      words: await pairingWords(out.link_id, WORDS),
      updated_at: this.stamp(),
    }
    const card: Card = { server: location.origin, account, name: this.me!.display_name, link_id: mine.link_id, token: mine.token }
    try {
      await this.postControl(c, { t: 'hello', card })
    } catch (e) {
      api.del(`/api/links/${mine.link_id}`).catch(() => {})
      linkReq(c, 'DELETE', '/api/link').catch(() => {})
      throw e instanceof Gone ? new Error('This code has expired or was already used.') : e instanceof Unreachable ? new Error("Can't reach that server.") : e
    }
    this.contacts.push(c)
    this.touch(c)
    return c.num
  }

  private async maybeHello(m: Mail) {
    if (!this.device || this.prompts.some((p) => p.link_id === m.link_id)) return
    const card = await openHello(this.device, m)
    if (!card) return
    const remote = this.remote(card)
    try {
      const devices = await this.peerDevices(remote)
      if (!(await signedBy(m, Object.fromEntries(devices.map((d) => [d.id, d.sign_pub]))))) return
    } catch {
      return
    }
    if (this.prompts.some((p) => p.link_id === m.link_id) || this.contacts.some((c) => c.in === m.link_id)) return
    const words = await pairingWords(m.link_id, WORDS)
    this.prompts.push({ link_id: m.link_id, card, words })
    this.notify('New contact', `${card.name} wants to add you.`)
  }

  // placeholder contact for a pending pairing
  private remote(card: Card): Contact {
    return {
      id: '',
      num: 0,
      name: card.name,
      server: card.server,
      account: card.account,
      out: { link_id: card.link_id, token: card.token },
      in: '',
      status: 'pending',
      created_at: 0,
      updated_at: 0,
    }
  }

  async acceptPrompt(p: Prompt) {
    this.prompts = this.prompts.filter((x) => x.link_id !== p.link_id)
    const c: Contact = {
      ...this.remote(p.card),
      id: crypto.randomUUID(),
      num: this.nextNum(),
      name: p.card.name || 'Contact',
      in: p.link_id,
      status: 'active',
      created_at: this.serverNow(),
      words: p.words,
      updated_at: this.stamp(),
    }
    this.contacts.push(c)
    this.touch(c)
    this.addEvent(c.num, 'Contact added.')
    this.postControl(c, { t: 'accept' }).catch((e) => this.sendFailed(c, e))
    this.open(c.num)
  }

  async declinePrompt(p: Prompt) {
    this.prompts = this.prompts.filter((x) => x.link_id !== p.link_id)
    await this.postControl(this.remote(p.card), { t: 'decline' }).catch(() => {})
    await api.del(`/api/links/${p.link_id}`).catch(() => {})
  }

  contactWords(num: number): string[] {
    return this.contact(num)?.words ?? []
  }

  // --- mail

  private async signersFor(c: Contact, from: string): Promise<Record<string, string>> {
    if (!c.signers?.[from]) await this.peerDevices(c, true).catch(() => {})
    return c.signers ?? {}
  }

  private async openFrom(c: Contact, m: Mail): Promise<Plain | null> {
    const plain = await openMail(this.device!, m, await this.signersFor(c, m.from_device))
    if (plain) this.checkDevices(c, m, 'to')
    return plain
  }

  private async loadMail() {
    const dev = this.device
    if (!dev) return
    const res = await api.get<{ now: number; mail: Mail[] }>('/api/mail')
    this.skew = res.now - Math.floor(Date.now() / 1000)
    const grouped: Record<number, Message[]> = {}
    for (const m of res.mail) {
      const c = this.contacts.find((x) => x.in === m.link_id)
      if (!c) {
        await this.maybeHello(m)
        continue
      }
      const plain = await this.openFrom(c, m)
      if (plain && isControl(plain)) {
        this.control(c, plain, m, false)
        continue
      }
      ;(grouped[c.num] ??= []).push({ id: m.id, from_user: c.num, to_user: this.me!.id, created_at: m.created_at, expires_at: m.expires_at, plain })
    }
    for (const [num, list] of Object.entries(grouped)) this.messages[Number(num)] = list
    for (const num of Object.keys(this.messages)) if (!grouped[Number(num)]) delete this.messages[Number(num)]
    await Promise.all(this.contacts.filter((c) => c.status === 'active').map((c) => this.loadSent(c)))
  }

  // sent copies are stored on the recipient's server and may come from any of the account's devices
  private async loadSent(c: Contact) {
    const dev = this.device
    if (!dev) return
    let sent: Mail[]
    try {
      sent = (await linkReq<{ mail: Mail[] }>(c, 'GET', '/api/link/mail')).mail
    } catch (e) {
      if (e instanceof Gone) this.forget(c, `${this.userName(c.num)} is no longer reachable and was removed from your contacts.`)
      return
    }
    const mine: Message[] = []
    for (const m of sent) {
      if (!this.known[m.from_device]) await this.refreshDevices().catch(() => {})
      const plain = await openMail(dev, m, this.ownSigners())
      if (plain) this.checkDevices(c, m, 'from')
      if (plain && isControl(plain)) continue
      mine.push({ id: m.id, from_user: this.me!.id, to_user: c.num, created_at: m.created_at, expires_at: m.expires_at, plain })
    }
    if (!this.contact(c.num)) return
    const theirs = (this.messages[c.num] ?? []).filter((n) => n.from_user !== this.me!.id)
    const list = [...theirs, ...mine].sort((a, b) => a.created_at - b.created_at)
    if (list.length) this.messages[c.num] = list
    else delete this.messages[c.num]
  }

  private async takeMail(m: Mail) {
    const dev = this.device
    if (!dev) return
    const c = this.contacts.find((x) => x.in === m.link_id)
    if (!c) return this.maybeHello(m)
    if (this.messages[c.num]?.some((n) => n.id === m.id)) return
    const plain = await this.openFrom(c, m)
    if (plain && isControl(plain)) return this.control(c, plain, m, true)
    const msg: Message = { id: m.id, from_user: c.num, to_user: this.me!.id, created_at: m.created_at, expires_at: m.expires_at, plain }
    this.messages[c.num] = [...(this.messages[c.num] ?? []), msg].sort((a, b) => a.created_at - b.created_at)
    if (!calls.active) tones.message()
    if (this.selected === c.num && !document.hidden) this.markSeen(c.num)
    else if (privateMessages()) this.notify('New message', '')
    else this.notify(this.userName(c.num), plain?.t === 'call' ? callText(msg, this.me?.id) : plain ? plainText(plain) : 'New message')
  }

  // seen by all of the account's devices; timestamps from the message keep their vault writes identical
  private control(c: Contact, p: Plain, m: Mail, live: boolean) {
    const name = this.userName(c.num)
    const at = m.created_at * 1000
    switch (p.t) {
      case 'accept':
        if (c.status !== 'pending') return
        c.status = 'active'
        this.touch(c, at)
        this.addEvent(c.num, 'Contact added.')
        if (live) this.flash(`${name} accepted.`)
        return
      case 'decline':
        api.del(`/api/links/${c.in}`).catch(() => {})
        return this.forget(c, `${name} declined.`)
      case 'bye':
        api.del(`/api/links/${c.in}`).catch(() => {})
        return this.forget(c, `${name} is no longer a contact.`)
      case 'timer':
        if (c.ttl === p.ttl) return
        c.ttl = p.ttl
        this.touch(c, at)
        this.addEvent(c.num, `${name} set messages to expire after ${fmtTtl(p.ttl)}`)
        return
    }
  }

  private expire() {
    const t = this.serverNow()
    this.clock = t
    if (this.outbox.length) this.dropStale()
    for (const c of this.contacts) {
      if (c.status === 'pending' && c.created_at + PENDING_SECS < t) {
        api.del(`/api/links/${c.in}`).catch(() => {})
        this.forget(c, `${this.userName(c.num)} didn't accept in time.`)
      }
    }
    for (const [key, list] of Object.entries(this.messages)) {
      const id = Number(key)
      const keep = list.filter((n) => n.expires_at > t)
      if (keep.length === list.length) continue
      for (const n of list) if (n.expires_at <= t && n.plain?.t === 'media') dropMedia(n.plain.blob)
      if (keep.length) this.messages[id] = keep
      else {
        delete this.messages[id]
        delete this.events[id]
      }
    }
  }

  select(id: number | null) {
    this.selected = id
    if (id === null) return
    if (!this.messages[id] && !this.opened.includes(id)) this.opened.push(id)
    this.markSeen(id)
  }

  private markSeen(id: number) {
    const last = this.messages[id]?.at(-1)?.created_at
    if (!last || (this.seen[id] ?? 0) >= last) return
    this.seen[id] = last
    writeLocal(`caw.seen.${this.me!.id}`, this.seen)
  }

  private addEvent(id: number, text: string) {
    this.events[id] = [...(this.events[id] ?? []), { at: this.serverNow(), text }]
  }

  // contact devices as their server reports them, limited to those whose identity key signed their encryption key
  private async peerDevices(c: Contact, fresh = false): Promise<PublicDevice[]> {
    const hit = c.id ? this.peers.get(c.id) : undefined
    if (hit && !fresh && Date.now() - hit.at < 60_000) return hit.devices
    const r = await linkReq<{ account: string; devices: PublicDevice[] }>(c, 'GET', '/api/link/devices')
    if (r.account !== c.account) throw new Error("This contact's server answered for someone else.")
    const devices = (await Promise.all(r.devices.map(async (d) => ((await certOk(d)) ? d : null)))).filter((d): d is PublicDevice => !!d)
    if (c.id) {
      this.peers.set(c.id, { at: Date.now(), devices })
      const signers = { ...(c.signers ?? {}), ...Object.fromEntries(devices.map((d) => [d.id, d.sign_pub])) }
      const live = this.contacts.find((x) => x.id === c.id)
      if (live && JSON.stringify(signers) !== JSON.stringify(live.signers ?? {})) {
        live.signers = signers
        this.saveContacts()
      }
    }
    return devices
  }

  // their server rejects (412) a message missing one of their devices; refetch the list and retry once
  private async deliver(c: Contact, ttl: number, plain: Plain, id: string = crypto.randomUUID(), blobs?: string[]): Promise<Mail | null> {
    for (let i = 0; ; i++) {
      const [to, own] = await Promise.all([this.peerDevices(c, i > 0), this.ownSealTo()])
      if (!to.length) throw new Error(`${c.alias ?? c.name} has no devices that can receive messages right now.`)
      const m = await sealMail(this.device!, to, own, ttl, plain, id)
      try {
        return await linkReq<Mail>(c, 'POST', '/api/link/mail', { ...m, blobs })
      } catch (e) {
        if (e instanceof ApiError && e.status === 409) return null
        if (!(e instanceof ApiError && e.status === 412) || i) throw e
      }
    }
  }

  private async postControl(c: Contact, plain: Plain) {
    await this.deliver(c, this.config.message_ttl_default, plain)
  }

  // network errors queue the message in the outbox; a gone contact is removed; anything else throws
  async send(to: number, plain: Plain, blobs?: string[]) {
    if (!this.device) throw new Error(this.deviceError ?? 'This device is not set up yet.')
    const c = this.contact(to)
    if (!c || c.status !== 'active') throw new Error('This contact is not available.')
    const o: Outgoing = { id: crypto.randomUUID(), to, plain, blobs, at: this.serverNow(), ttl: this.timerFor(to) }
    try {
      await this.post(o)
    } catch (e) {
      if (e instanceof Unreachable) {
        this.outbox.push(o)
        this.saveOutbox()
        await this.unreachableServer(c, e.retryAfter)
      } else if (e instanceof Gone) {
        this.forget(c, `${this.userName(to)} is no longer reachable and was removed from your contacts.`)
      } else throw e
    }
  }

  private sendFailed(c: Contact, e: unknown) {
    if (e instanceof Gone) this.forget(c, `${this.userName(c.num)} is no longer reachable and was removed from your contacts.`)
    else if (e instanceof Unreachable) this.unreachableServer(c, e.retryAfter)
    else this.flash(e instanceof Error ? e.message : String(e))
  }

  // same id on every attempt, so resending a delivered message returns a conflict instead of a duplicate
  private async post(o: Outgoing) {
    const c = this.contact(o.to)!
    const stored = await this.deliver(c, o.ttl, o.plain, o.id, o.blobs)
    if (stored) {
      this.addMine(c, stored.id, stored.created_at, stored.expires_at, o.plain)
      api.post('/api/sent', { contact: c.id }).catch(() => {})
    }
    this.reachable(c)
  }

  private addMine(c: Contact, id: string, created: number, expires: number, plain: Plain) {
    if (this.messages[c.num]?.some((n) => n.id === id)) return
    const msg: Message = { id, from_user: this.me!.id, to_user: c.num, created_at: created, expires_at: expires, plain }
    this.messages[c.num] = [...(this.messages[c.num] ?? []), msg].sort((a, b) => a.created_at - b.created_at)
  }

  private reachable(c: Contact) {
    this.retry.delete(c.server)
    if (c.unreachable_since || c.asked_at) {
      c.unreachable_since = undefined
      c.asked_at = undefined
      this.saveContacts()
    }
    if (this.unreachable === c.num) this.unreachable = null
  }

  // their server counts as down only if the home server responds; prompt at most once a day per contact
  private async unreachableServer(c: Contact, retryAfter = 0) {
    const r = this.retry.get(c.server)
    const step = r ? r.step + 1 : 0
    const wait = Math.max(retryAfter, BACKOFF[Math.min(step, BACKOFF.length - 1)]) * (1 + Math.random() * 0.2)
    this.retry.set(c.server, { at: Date.now() + wait * 1000, step })
    const ours = await fetch('/api/health').then((x) => x.ok).catch(() => false)
    if (!ours) return
    const t = this.serverNow()
    c.unreachable_since ??= t
    if (!c.asked_at || t - c.asked_at > DAY) {
      c.asked_at = t
      this.unreachable = c.num
    }
    this.saveContacts()
  }

  keepTrying() {
    this.unreachable = null
  }

  private saveOutbox() {
    const key = `caw.outbox.${this.me!.id}`
    const list = $state.snapshot(this.outbox)
    this.outboxSave = this.outboxSave.then(async () => {
      try {
        if (list.length) localStorage.setItem(key, await sealLocal(list))
        else localStorage.removeItem(key)
      } catch {
        // storage unavailable: outbox is kept in memory only
      }
    })
  }

  private async loadOutbox() {
    let stored: string | null = null
    try {
      stored = localStorage.getItem(`caw.outbox.${this.me!.id}`)
    } catch {
      return
    }
    const list = stored ? ((await openLocal<Outgoing[]>(stored)) ?? []) : []
    const known = new Set(this.outbox.map((o) => o.id))
    this.outbox.push(...list.filter((o) => !known.has(o.id) && this.contact(o.to)))
    this.dropStale()
    this.flush()
  }

  // drops queued messages whose ttl has already elapsed
  private dropStale() {
    const t = this.serverNow()
    const stale = this.outbox.filter((o) => o.at + o.ttl <= t)
    if (!stale.length) return
    this.outbox = this.outbox.filter((o) => o.at + o.ttl > t)
    this.saveOutbox()
    this.flash(stale.length === 1 ? 'An unsent message expired.' : `${stale.length} unsent messages expired.`)
  }

  // in order, one server at a time, each only after its backoff elapses
  async flush() {
    if (this.flushing || !this.device) return
    this.flushing = true
    const skip = new Set<string>()
    try {
      for (const o of [...this.outbox]) {
        const c = this.contact(o.to)
        if (!c) {
          this.outbox = this.outbox.filter((x) => x.id !== o.id)
          continue
        }
        if (skip.has(c.server) || (this.retry.get(c.server)?.at ?? 0) > Date.now()) {
          skip.add(c.server)
          continue
        }
        try {
          await this.post(o)
        } catch (e) {
          if (e instanceof Unreachable) {
            skip.add(c.server)
            await this.unreachableServer(c, e.retryAfter)
            continue
          }
          if (e instanceof Gone) this.forget(c, `${this.userName(c.num)} is no longer reachable and was removed from your contacts.`)
          else this.flash(`Message not sent: ${e instanceof Error ? e.message : e}`)
        }
        this.outbox = this.outbox.filter((x) => x.id !== o.id)
      }
    } finally {
      this.saveOutbox()
      this.flushing = false
    }
  }

  pendingFor(id: number): Message[] {
    const me = this.me?.id ?? 0
    const sent = new Set((this.messages[id] ?? []).map((n) => n.id))
    return this.outbox
      .filter((o) => o.to === id && !sent.has(o.id))
      .map((o) => ({ id: o.id, from_user: me, to_user: o.to, created_at: o.at, expires_at: o.at, plain: o.plain, pending: true }))
  }

  remoteOf(id: number): Remote | undefined {
    const c = this.contact(id)
    return c ? { server: c.server, out: c.out } : undefined
  }

  // --- attachments

  private setAttachment(to: number, a: Attachment | undefined) {
    this.attachments = { ...this.attachments, [to]: a }
  }

  async attach(to: number, file: File) {
    const limit = this.config.max_upload_mb * 1024 * 1024
    this.setAttachment(to, { status: 'preparing', progress: 0 })
    try {
      const prepared = await prepare(file, limit, (progress) => {
        if (this.attachments[to]?.status === 'preparing') this.setAttachment(to, { status: 'preparing', progress })
      })
      if (this.attachments[to]?.status !== 'preparing') return URL.revokeObjectURL(prepared.preview)
      this.setAttachment(to, { status: 'ready', progress: 1, prepared })
    } catch (e) {
      this.setAttachment(to, undefined)
      throw e instanceof TooLarge ? new Error(`File exceeds the ${this.config.max_upload_mb} MB limit, even after compression.`) : e
    }
  }

  detach(to: number) {
    const p = this.attachments[to]?.prepared
    if (p) URL.revokeObjectURL(p.preview)
    this.setAttachment(to, undefined)
  }

  async sendMedia(to: number, text: string) {
    const a = this.attachments[to]
    const p = a?.prepared
    const c = this.contact(to)
    if (!p || !c || a.status !== 'ready') return
    this.setAttachment(to, { ...a, status: 'sending', progress: 0 })
    try {
      const { body, key } = await encryptFile(p.data)
      const blob = await upload(c, body, (progress) => this.setAttachment(to, { ...a, status: 'sending', progress }))
      seedMedia(blob, p.preview)
      const plain: MediaPlain = { t: 'media', kind: p.kind, mime: p.mime, blob, key, size: p.data.length, w: p.w, h: p.h, dur: p.dur, thumb: p.thumb, text: text || undefined }
      await this.send(to, plain, [blob])
      this.setAttachment(to, undefined)
    } catch (e) {
      this.setAttachment(to, a)
      if (e instanceof Gone) return this.forget(c, `${this.userName(to)} is no longer reachable and was removed from your contacts.`)
      if (e instanceof Unreachable) {
        await this.unreachableServer(c, e.retryAfter)
        throw new Error(`Can't reach ${this.userName(to)}'s server right now.`)
      }
      throw e
    }
  }

  async setTimer(to: number, ttl: number) {
    const c = this.contact(to)
    if (!c) return
    c.ttl = ttl
    this.touch(c)
    this.addEvent(to, `You set messages to expire after ${fmtTtl(ttl)}`)
    await this.postControl(c, { t: 'timer', ttl }).catch((e) => this.sendFailed(c, e))
  }

  private callEnded(e: CallEnd) {
    const outcome = e.secs !== null ? 'answered' : e.reason === 'declined' ? 'declined' : 'missed'
    this.send(e.peer.id, { t: 'call', outcome, secs: e.secs ?? undefined }).catch((err) => console.warn('call record', err))
  }

  private onMessage(m: any) {
    switch (m.type) {
      case 'hello':
        this.toCalls(m)
        this.loadMail().catch(() => {})
        this.pullVault().catch(() => {})
        this.refreshDevices().catch(() => {})
        return
      case 'mail':
        this.takeMail(m).catch(() => {})
        return
      case 'sent': {
        const c = this.contacts.find((x) => x.id === m.contact)
        if (c) this.loadSent(c).catch(() => {})
        return
      }
      case 'vault':
        if (m.version > this.vaultVersion) this.pullVault().catch(() => {})
        return
      case 'devices':
        this.refreshDevices().catch(() => {})
        return
      case 'link_removed': {
        const c = this.contacts.find((x) => x.in === m.link_id)
        if (c) {
          linkReq(c, 'DELETE', '/api/link').catch(() => {})
          this.forget(c, `${this.userName(c.num)} is no longer a contact.`)
        }
        return
      }
      case 'link_revoked': {
        this.prompts = this.prompts.filter((p) => p.link_id !== m.link_id)
        const c = this.contacts.find((x) => x.in === m.link_id)
        if (c) this.forget(c)
        return
      }
      case 'error':
        if (!m.call_id) this.flash(m.message)
        else this.toCalls(m)
        return
      case 'pong':
        return
      default:
        this.toCalls(m)
    }
  }

  // an incoming call names its contact by link, so call events wait until contacts are loaded
  private toCalls(m: any) {
    this.contactsReady.then(() => calls.handle(m, 'own'))
  }

  // a session description must be signed by a device the contact's server lists for them
  private async verifyCall(num: number, callId: string, d: RTCSessionDescriptionInit, device?: string, sig?: string): Promise<boolean> {
    const c = this.contact(num)
    if (!c || !device || !sig) return false
    const pub = (await this.signersFor(c, device))[device]
    return !!pub && verify(unb64(pub), unb64(sig), callSigBytes(callId, d.type ?? '', d.sdp ?? ''))
  }
}

async function redeem(server: string, body: { ticket: string; peer_server: string; peer_account: string }) {
  let res: Response
  try {
    res = await fetch(`${server}/api/pairings/redeem`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) })
  } catch {
    throw new Error("Can't reach that server.")
  }
  if (res.status === 410) throw new Error('This code has expired or was already used.')
  if (!res.ok) throw new Error("Couldn't add this contact.")
  return (await res.json()) as { link_id: string; token: string }
}

export const store = new Store()
