import wordsRaw from '../../../protocol/words.txt?raw'
import { api, ApiError, fmtTtl, type Me, type Peer } from './api'
import { calls, type CallEnd } from './call.svelte'
import { decodeCode, encodeCode, Gone, hostOf, linkReq, Unreachable, wsBase, type Contact, type Remote } from './contacts'
import { b64, callSigBytes, groupMessageId, groupToken, membershipDigest, pairingWords, random, sign, unb64, verify, type Bytes } from './crypto'
import {
  certOk,
  ensureDevice,
  forgetLocal,
  loadDataKey,
  openHello,
  openLocal,
  openMail,
  openSealed,
  saveDataKey,
  sealCopy,
  sealLocal,
  sealMail,
  signedBy,
  unsupported,
  type Card,
  type Device,
  type GroupState,
  type Mail,
  type OwnDevice,
  type Plain,
  type PublicDevice,
} from './device'
import { applyState, card as cardOf, digestList, goneCount, isLive, isUuid, liveCards, mergeGroup, normServer, ownGen, validCard, type Card as GroupCard, type Group } from './groups'
import { derive, newKdf, unwrapWith, wrapKey, type Kdf } from './kdf'
import { openVault, sealVault, synced, type VaultDoc, type VaultResp } from './vault'
import { dropMedia, encryptFile, prepare, seedMedia, TooLarge, upload, uploadOwn, type MediaPlain, type Prepared } from './media'
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
  sender?: string
}

// held copies wait for the member's card to change
type Outgoing = {
  id: string
  to: number
  plain: Plain
  blobs?: string[]
  at: number
  ttl: number
  group?: string
  copies?: Record<string, string>
  cards?: Record<string, GroupCard>
  held?: Record<string, string>
}

export interface CawEvent {
  at: number
  text: string
}

export interface Prompt {
  link_id: string
  card: Card
  words: string[]
}

export type LocalGroup = Group & { num: number; u: number; names?: Record<string, string>; signers?: Record<string, Record<string, string>> }

export interface GroupPrompt {
  contact: string
  from: string
  g: string
  name: string
  members: string[]
  inviter: string
  member: string
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
  max_group_members: number
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

const isControl = (p: Plain) =>
  p.t === 'hello' || p.t === 'accept' || p.t === 'decline' || p.t === 'timer' || p.t === 'bye' || p.t === 'group_invite' || p.t === 'group_join' || p.t === 'group_decline'

// never accepted through a contact link
const isGroupContent = (p: Plain) => p.t === 'group' || ((p.t === 'text' || p.t === 'media') && p.g !== undefined)

const remote = (c: GroupCard): Remote => ({ server: c.server, out: { link_id: c.link_id, token: c.token } })

// cards hold normalized addresses; contacts may hold an address as it was typed
const sameAccount = (k: Contact, c: GroupCard) => k.account === c.account && normServer(k.server) === c.server

// key order does not matter when comparing copies
const canon = (v: unknown): string =>
  JSON.stringify(v, (_, x) => (x && typeof x === 'object' && !Array.isArray(x) ? Object.fromEntries(Object.entries(x).sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))) : x))

function syncedGroup(g: LocalGroup): Group {
  const s: Partial<LocalGroup> = $state.snapshot(g) as LocalGroup
  delete s.num
  delete s.u
  delete s.names
  delete s.signers
  return s as Group
}

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
  config = $state<Config>({
    server_name: 'Caw',
    registration_open: false,
    message_ttl_default: 86400,
    message_ttl_min: 300,
    message_ttl_max: 604800,
    max_upload_mb: 25,
    max_group_members: 10,
  })
  contacts = $state<Contact[]>([])
  groups = $state<LocalGroup[]>([])
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
  groupPrompts = $state<GroupPrompt[]>([])
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
  private goneGroups: Record<string, number> = {}
  // the account's group links, known before the vault names their groups
  private groupLinks = new Set<string>()
  // vault fields this version does not use, kept when writing
  private vaultExtra: Record<string, unknown> = {}
  private memberLists = new Map<string, PeerDevices>()
  private synced = new Map<string, number>()
  private mailQueue: Promise<void> = Promise.resolve()

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
    const contacts = this.contacts
      .filter((c) => !q || this.userName(c.num).toLowerCase().includes(q) || c.name.toLowerCase().includes(q) || hostOf(c.server).includes(q))
      .map((c) => c.num)
    const groups = this.groups.filter((g) => !q || g.name.v.toLowerCase().includes(q)).map((g) => g.num)
    return [...contacts, ...groups].sort((a, b) => rank(b) - rank(a) || this.userName(a).localeCompare(this.userName(b)))
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
    const g = this.group(id)
    if (g) {
      if (g.inviter) return { text: 'Waiting to be added', missed: false }
      const n = this.messages[id]?.at(-1)
      if (!n) return { text: this.memberCount(id), missed: false }
      if (!n.plain) return { text: 'Encrypted message', missed: false }
      return { text: `${n.from_user === this.me?.id ? 'You' : this.memberName(id, n.sender)}: ${plainText(n.plain)}`, missed: false }
    }
    const c = this.contact(id)
    if (c?.status === 'pending') return { text: 'Waiting to accept', missed: false }
    const n = this.messages[id]?.at(-1)
    if (!n) return { text: hostOf(c?.server ?? ''), missed: false }
    if (!n.plain) return { text: 'Encrypted message', missed: false }
    if (n.plain.t === 'call') return { text: callText(n, this.me?.id), missed: n.plain.outcome === 'missed' && n.from_user === id }
    return { text: (n.from_user === this.me?.id ? 'You: ' : '') + plainText(n.plain), missed: false }
  }

  timerFor(id: number): number {
    return this.group(id)?.ttl.v ?? this.contact(id)?.ttl ?? this.config.message_ttl_default
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
    const g = this.group(id)
    if (g) return g.name.v || 'Group'
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
    this.groups = []
    this.groupPrompts = []
    this.goneGroups = {}
    this.groupLinks.clear()
    this.vaultExtra = {}
    this.memberLists.clear()
    this.synced.clear()
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
    const groups: NonNullable<VaultDoc['groups']> = {}
    for (const [id, u] of Object.entries(this.goneGroups)) if (u > cutoff) groups[id] = { u, gone: true }
    for (const g of this.groups) groups[g.id] = { u: g.u, g: syncedGroup(g) }
    return { ...this.vaultExtra, v: 1, contacts, groups }
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
    const { v: _v, contacts: _c, groups, ...extra } = doc
    this.vaultExtra = extra
    let added = false
    for (const [id, e] of Object.entries(groups ?? {})) {
      const i = this.groups.findIndex((g) => g.id === id)
      const local = i >= 0 ? this.groups[i] : undefined
      if (e.gone || !e.g) {
        if (e.u <= (this.goneGroups[id] ?? -1)) continue
        this.goneGroups[id] = e.u
        if (local && e.u > local.u) this.dropGroup(local)
        changed = true
        continue
      }
      if ((this.goneGroups[id] ?? -1) >= e.u) continue
      if (!local) {
        delete this.goneGroups[id]
        this.groups.push({ ...e.g, num: this.nextNum(), u: e.u })
        this.groupPrompts = this.groupPrompts.filter((p) => p.g !== id)
        changed = added = true
        continue
      }
      const mine = syncedGroup(local)
      const merged = mergeGroup(mine, e.g, this.stamp())
      if (e.u > local.u) local.u = e.u
      if (canon(merged) === canon(mine)) continue
      const old = local.own.link_id
      Object.assign(local, merged)
      if (!merged.inviter) delete local.inviter
      if (old !== local.own.link_id) api.del(`/api/links/${old}`).catch(() => {})
      changed = true
    }
    if (changed) this.saveContacts()
    if (added) this.loadMail().catch(() => {})
    const out = this.doc()
    return (
      Object.entries(out.contacts).some(([id, e]) => (doc.contacts[id]?.u ?? -1) < e.u) ||
      Object.entries(out.groups ?? {}).some(([id, e]) => canon(groups?.[id]) !== canon(e))
    )
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
    return Math.max(1_000_000, ...this.contacts.map((x) => x.num), ...this.groups.map((x) => x.num)) + 1
  }

  // --- local contact storage

  private saveContacts() {
    const key = `caw.contacts.${this.me!.id}`
    const v = { contacts: $state.snapshot(this.contacts), gone: { ...this.gone }, groups: $state.snapshot(this.groups), goneGroups: { ...this.goneGroups } }
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
    const v = stored
      ? await openLocal<{ contacts: Contact[]; gone: Record<string, number>; groups?: LocalGroup[]; goneGroups?: Record<string, number> }>(stored)
      : null
    this.contacts = Array.isArray(v?.contacts) ? v.contacts : []
    this.gone = v?.gone ?? {}
    this.groups = Array.isArray(v?.groups) ? v.groups : []
    this.goneGroups = v?.goneGroups ?? {}
  }

  // drops contacts whose links were removed while offline, by the contact or another of the account's devices
  private async reconcile() {
    const links = await api.get<{ id: string; kind?: string }[]>('/api/links')
    const have = new Set(links.map((l) => l.id))
    this.groupLinks = new Set(links.filter((l) => l.kind === 'group').map((l) => l.id))
    for (const c of [...this.contacts]) {
      if (!have.has(c.in)) this.forget(c, `${this.userName(c.num)} is no longer a contact.`)
    }
    for (const g of [...this.groups]) {
      if (!have.has(g.own.link_id)) this.forgetGroup(g, `You are no longer in ${g.name.v}.`)
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

  private async ownSealTo(fresh = false): Promise<PublicDevice[]> {
    if (fresh || Date.now() - this.ownAt > 60_000) await this.refreshDevices()
    const ok = await Promise.all(this.ownDevices.map(async (d) => ((await certOk(d)) ? (d as PublicDevice) : null)))
    return ok.filter((d): d is PublicDevice => !!d)
  }

  private ownSigners(): Record<string, string> {
    return Object.fromEntries(Object.entries(this.known).map(([id, k]) => [id, k.sign]))
  }

  // every stanza for this account must target a device the home server lists (or listed recently), with a known key
  private async checkDevices(num: number, m: Mail, ours: 'to' | 'from') {
    if (m.created_at < this.enrolledAt) return
    const odd = () => m.keys.filter((k) => k.o === ours && !this.known[k.d]?.keys.includes(k.k))
    if (!odd().length) return
    await this.refreshDevices().catch(() => {})
    if (!odd().length || this.deviceAlerts[num]) return
    this.deviceAlerts[num] = true
    this.notify('Check your devices', `A message ${this.isGroup(num) ? 'in' : 'with'} ${this.userName(num)} was also encrypted for a device that isn't yours.`)
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
    if (this.contacts.some((c) => c.account === code.a && normServer(c.server) === code.s)) throw new Error('Already a contact.')
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
    const hello = await openHello(this.device, m)
    const server = hello && normServer(hello.server)
    if (!hello || !server) return
    const card = { ...hello, server }
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
    if (plain) this.checkDevices(c.num, m, 'to')
    return plain
  }

  private loadMail(): Promise<void> {
    return this.inMailQueue(() => this.readMail())
  }

  private takeMail(m: Mail): Promise<void> {
    return this.inMailQueue(() => this.readOne(m))
  }

  // a full reload and live mail never overlap
  private inMailQueue(f: () => Promise<void>): Promise<void> {
    const run = this.mailQueue.then(f)
    this.mailQueue = run.catch(() => {})
    return run
  }

  private async readMail() {
    const dev = this.device
    if (!dev) return
    const res = await api.get<{ now: number; mail: Mail[] }>('/api/mail')
    this.skew = res.now - Math.floor(Date.now() / 1000)
    const grouped: Record<number, Message[]> = {}
    const byGroup = new Map<string, Mail[]>()
    for (const m of res.mail) {
      const c = this.contacts.find((x) => x.in === m.link_id)
      if (!c) {
        const g = this.groups.find((x) => x.own.link_id === m.link_id)
        if (g) byGroup.set(g.id, [...(byGroup.get(g.id) ?? []), m])
        else if (!this.groupLinks.has(m.link_id)) await this.maybeHello(m)
        continue
      }
      const plain = await this.openFrom(c, m)
      if (plain && isGroupContent(plain)) continue
      if (plain && isControl(plain)) {
        this.control(c, plain, m, false)
        continue
      }
      ;(grouped[c.num] ??= []).push({ id: m.id, from_user: c.num, to_user: this.me!.id, created_at: m.created_at, expires_at: m.expires_at, plain })
    }
    for (const [id, list] of byGroup) {
      const g = this.groups.find((x) => x.id === id)
      if (g) grouped[g.num] = await this.readGroup(g, list)
    }
    for (const [num, list] of Object.entries(grouped)) if (list.length) this.messages[Number(num)] = list
    for (const num of Object.keys(this.messages)) if (!grouped[Number(num)]?.length) delete this.messages[Number(num)]
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
      if (plain) this.checkDevices(c.num, m, 'from')
      if (plain && isControl(plain)) continue
      mine.push({ id: m.id, from_user: this.me!.id, to_user: c.num, created_at: m.created_at, expires_at: m.expires_at, plain })
    }
    if (!this.contact(c.num)) return
    const theirs = (this.messages[c.num] ?? []).filter((n) => n.from_user !== this.me!.id)
    const list = [...theirs, ...mine].sort((a, b) => a.created_at - b.created_at)
    if (list.length) this.messages[c.num] = list
    else delete this.messages[c.num]
  }

  private async readOne(m: Mail) {
    const dev = this.device
    if (!dev) return
    const c = this.contacts.find((x) => x.in === m.link_id)
    if (!c) {
      const g = this.groups.find((x) => x.own.link_id === m.link_id)
      if (g) return this.takeGroupMail(g, m)
      if (this.groupLinks.has(m.link_id)) return
      return this.maybeHello(m)
    }
    if (this.messages[c.num]?.some((n) => n.id === m.id)) return
    const plain = await this.openFrom(c, m)
    if (plain && isGroupContent(plain)) return
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
      case 'group_invite':
        return this.groupInvite(c, p, m)
      case 'group_join':
        this.groupJoin(c, p, m).catch((e) => console.warn('group join', e))
        return
      case 'group_decline':
        return this.groupDecline(c, p)
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

  // fractional seconds, so an event sorts after messages that arrived earlier in the same second
  private addEvent(id: number, text: string) {
    this.events[id] = [...(this.events[id] ?? []), { at: this.stamp() / 1000, text }]
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

  private async postControl(c: Contact, plain: Plain, ttl = this.config.message_ttl_default) {
    await this.deliver(c, ttl, plain)
  }

  // network errors queue the message in the outbox; a gone contact is removed; anything else throws
  async send(to: number, plain: Plain, blobs?: string[]) {
    if (!this.device) throw new Error(this.deviceError ?? 'This device is not set up yet.')
    const g = this.group(to)
    if (g) return this.sendGroup(g, plain, blobs)
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
      this.addMine(c.num, stored.id, stored.created_at, stored.expires_at, o.plain)
      api.post('/api/sent', { contact: c.id }).catch(() => {})
    }
    this.reachable(c)
  }

  private addMine(num: number, id: string, created: number, expires: number, plain: Plain) {
    if (this.messages[num]?.some((n) => n.id === id)) return
    const msg: Message = { id, from_user: this.me!.id, to_user: num, created_at: created, expires_at: expires, plain }
    this.messages[num] = [...(this.messages[num] ?? []), msg].sort((a, b) => a.created_at - b.created_at)
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
    this.outbox.push(...list.filter((o) => !known.has(o.id) && (o.copies || this.contact(o.to))))
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
    const shown = stale.filter((o) => o.plain.t !== 'group').length
    if (shown) this.flash(shown === 1 ? 'An unsent message expired.' : `${shown} unsent messages expired.`)
  }

  // in order, one server at a time, each only after its backoff elapses
  async flush() {
    if (this.flushing || !this.device) return
    this.flushing = true
    const skip = new Set<string>()
    try {
      for (const o of [...this.outbox]) {
        if (o.copies) {
          try {
            if (!(await this.postGroup(o, skip))) continue
          } catch (e) {
            this.flash(`Message not sent: ${e instanceof Error ? e.message : e}`)
          }
          this.outbox = this.outbox.filter((x) => x.id !== o.id)
          continue
        }
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
      .filter((o) => o.to === id && !sent.has(o.id) && o.plain.t !== 'group')
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
    const g = this.group(to)
    const c = this.contact(to)
    if (!p || (!c && !g) || a.status !== 'ready') return
    this.setAttachment(to, { ...a, status: 'sending', progress: 0 })
    try {
      const { body, key } = await encryptFile(p.data)
      const progress = (x: number) => this.setAttachment(to, { ...a, status: 'sending', progress: x })
      const blob = g ? await uploadOwn(g.own.link_id, body, progress) : await upload(c!, body, progress)
      seedMedia(blob, key, p.preview, g ? undefined : c)
      const plain: MediaPlain = { t: 'media', kind: p.kind, mime: p.mime, blob, key, size: p.data.length, w: p.w, h: p.h, dur: p.dur, thumb: p.thumb, text: text || undefined }
      await this.send(to, plain, [blob])
      this.setAttachment(to, undefined)
    } catch (e) {
      this.setAttachment(to, a)
      if (!c) throw e instanceof Unreachable ? new Error("Can't reach your server right now.") : e
      if (e instanceof Gone) return this.forget(c, `${this.userName(to)} is no longer reachable and was removed from your contacts.`)
      if (e instanceof Unreachable) {
        await this.unreachableServer(c, e.retryAfter)
        throw new Error(`Can't reach ${this.userName(to)}'s server right now.`)
      }
      throw e
    }
  }

  async setTimer(to: number, ttl: number) {
    const g = this.group(to)
    if (g) {
      g.ttl = { v: ttl, u: Math.max(this.stamp(), g.ttl.u + 1) }
      this.touchGroup(g)
      this.addEvent(to, `You set messages to expire after ${fmtTtl(ttl)}`)
      return this.sendState(g)
    }
    const c = this.contact(to)
    if (!c) return
    c.ttl = ttl
    this.touch(c)
    this.addEvent(to, `You set messages to expire after ${fmtTtl(ttl)}`)
    await this.postControl(c, { t: 'timer', ttl }).catch((e) => this.sendFailed(c, e))
  }

  // --- groups; see docs/protocol.md, section 9

  group(num: number): LocalGroup | undefined {
    return this.groups.find((g) => g.num === num)
  }

  isGroup(num: number): boolean {
    return this.groups.some((g) => g.num === num)
  }

  private cardName(c: GroupCard): string {
    const k = this.contacts.find((x) => sameAccount(x, c))
    return k ? (k.alias ?? k.name) : c.name
  }

  memberName(num: number, id: string | undefined): string {
    const g = this.group(num)
    if (!g || !id) return ''
    if (id === g.me) return 'You'
    const e = g.members[id]
    return isLive(e) ? this.cardName(e.c) : (g.names?.[id] ?? 'Former member')
  }

  groupRoom(num?: number): number {
    const max = this.config.max_group_members ?? 10
    const g = num === undefined ? undefined : this.group(num)
    if (!g) return max - 1
    const t = this.serverNow()
    return max - liveCards(g).length - Object.values(g.invites).filter((i) => i.expires > t).length
  }

  memberCount(num: number): string {
    const n = liveCards(this.group(num) ?? { name: { v: '', u: 0 }, ttl: { v: 0, u: 0 }, members: {} }).length
    return `${n} member${n === 1 ? '' : 's'}`
  }

  groupMembers(num: number): { id: string; name: string; host: string; contact: boolean; me: boolean }[] {
    const g = this.group(num)
    if (!g) return []
    return liveCards(g)
      .map((c) => ({
        id: c.id,
        name: c.id === g.me ? 'You' : this.cardName(c),
        host: hostOf(c.server),
        contact: this.contacts.some((x) => sameAccount(x, c)),
        me: c.id === g.me,
      }))
      .sort((a, b) => Number(b.me) - Number(a.me) || a.name.localeCompare(b.name))
  }

  invitable(num: number): number[] {
    const g = this.group(num)
    if (!g) return []
    const cards = liveCards(g)
    const t = this.serverNow()
    return this.contacts
      .filter((c) => c.status === 'active' && !((g.invites[c.id]?.expires ?? 0) > t) && !cards.some((x) => sameAccount(c, x)))
      .map((c) => c.num)
  }

  joined(num: number): boolean {
    const g = this.group(num)
    return !!g && !g.inviter
  }

  private ownCard(id: string, link: string, token: string, gen: number): GroupCard {
    const name = [...this.me!.display_name].slice(0, 64).join('') || this.me!.username
    return { id, server: location.origin, account: String(this.me!.id), name, link_id: link, token, gen }
  }

  private async newGroupLink(): Promise<{ link: string; secret: Bytes; token: string }> {
    const secret = random(32) as Bytes
    const token = await groupToken(secret, 0)
    let r: { link_id: string }
    try {
      r = await api.post<{ link_id: string }>('/api/links', { kind: 'group', gen: 0, token })
    } catch (e) {
      if (e instanceof ApiError && e.status === 400) throw new Error("Your server doesn't support groups yet.")
      throw e
    }
    this.groupLinks.add(r.link_id)
    return { link: r.link_id, secret, token }
  }

  private checkSize(n: number) {
    const max = this.config.max_group_members ?? 10
    if (n > max) throw new Error(`Groups on this server can have up to ${max} members.`)
  }

  async createGroup(name: string, nums: number[]): Promise<number> {
    if (!this.device) throw new Error(this.deviceError ?? 'This device is not set up yet.')
    const n = [...name.trim()].slice(0, 64).join('')
    if (!n) throw new Error('Enter a group name.')
    this.checkSize(nums.length + 1)
    const { link, secret, token } = await this.newGroupLink()
    const me = crypto.randomUUID()
    const t = this.stamp()
    this.groups.push({
      id: crypto.randomUUID(),
      me,
      name: { v: n, u: t },
      ttl: { v: this.config.message_ttl_default, u: t },
      members: { [me]: { u: t, c: this.ownCard(me, link, token, 0) } },
      own: { link_id: link, secret: b64(secret), base: 0, gen: 0 },
      invites: {},
      created_at: this.serverNow(),
      num: this.nextNum(),
      u: t,
    })
    const g = this.groups.at(-1)!
    this.touchGroup(g)
    this.addEvent(g.num, 'You created the group.')
    await this.invite(g, nums)
    return g.num
  }

  async inviteToGroup(num: number, nums: number[]) {
    const g = this.group(num)
    if (g) await this.invite(g, nums)
  }

  private async invite(g: LocalGroup, nums: number[]) {
    const t = this.serverNow()
    const pending = Object.values(g.invites).filter((i) => i.expires > t).length
    this.checkSize(liveCards(g).length + pending + nums.length)
    const open = this.invitable(g.num)
    for (const num of nums) {
      const c = this.contact(num)
      if (!c || !open.includes(num)) continue
      const member = crypto.randomUUID()
      g.invites[c.id] = { member, expires: t + 7 * DAY }
      this.touchGroup(g)
      this.addEvent(g.num, `You invited ${this.userName(num)}.`)
      const plain: Plain = {
        t: 'group_invite',
        g: g.id,
        name: g.name.v,
        members: liveCards(g).map((x) => x.name),
        inviter: g.me,
        member,
        text: `${this.me!.display_name} invited you to a group. Update Caw to join.`,
      }
      this.postControl(c, plain, this.config.message_ttl_max).catch((e) => this.sendFailed(c, e))
    }
  }

  async acceptGroup(p: GroupPrompt) {
    const c = this.contacts.find((x) => x.id === p.contact)
    if (!c) throw new Error('This contact is not available.')
    this.checkSize(p.members.length + 1)
    const { link, secret, token } = await this.newGroupLink()
    this.groupPrompts = this.groupPrompts.filter((x) => x.g !== p.g)
    const t = this.stamp()
    this.groups.push({
      id: p.g,
      me: p.member,
      name: { v: p.name, u: 0 },
      ttl: { v: this.config.message_ttl_default, u: 0 },
      members: { [p.member]: { u: t, c: this.ownCard(p.member, link, token, 0) } },
      own: { link_id: link, secret: b64(secret), base: 0, gen: 0 },
      invites: {},
      inviter: { contact: c.id, member: p.inviter },
      created_at: this.serverNow(),
      num: this.nextNum(),
      u: t,
    })
    const g = this.groups.at(-1)!
    this.touchGroup(g)
    this.addEvent(g.num, `Waiting for ${this.userName(c.num)} to add you.`)
    const mine = g.members[g.me]
    if (isLive(mine)) this.postControl(c, { t: 'group_join', g: g.id, card: cardOf(mine.c) }, this.config.message_ttl_max).catch((e) => this.sendFailed(c, e))
    this.open(g.num)
  }

  async declineGroup(p: GroupPrompt) {
    this.groupPrompts = this.groupPrompts.filter((x) => x.g !== p.g)
    this.goneGroups[p.g] = this.stamp()
    this.saveContacts()
    this.schedulePush()
    const c = this.contacts.find((x) => x.id === p.contact)
    if (c) await this.postControl(c, { t: 'group_decline', g: p.g }, this.config.message_ttl_max).catch(() => {})
  }

  async renameGroup(num: number, name: string) {
    const g = this.group(num)
    const n = [...name.trim()].slice(0, 64).join('')
    if (!g || !n || n === g.name.v) return
    g.name = { v: n, u: Math.max(this.stamp(), g.name.u + 1) }
    this.touchGroup(g)
    this.addEvent(num, `You renamed the group to ${n}.`)
    await this.sendState(g)
  }

  async removeMember(num: number, id: string) {
    const g = this.group(num)
    const e = g?.members[id]
    if (!g || !isLive(e) || id === g.me) return
    const removed = cardOf(e.c)
    this.cacheNames(g)
    g.members[id] = { u: Math.max(this.stamp(), e.u + 1), gone: true }
    g.left = { ...(g.left ?? {}), [id]: this.serverNow() }
    this.touchGroup(g)
    this.addEvent(num, `You removed ${this.cardName(removed)}.`)
    await this.queueGroup(g, { t: 'group', g: g.id, m: g.me, members: { [id]: { u: g.members[id].u, gone: true } } }, [removed], this.config.message_ttl_max)
    if (!(await this.rotate(g))) await this.sendState(g)
  }

  async leaveGroup(num: number) {
    const g = this.group(num)
    if (!g) return
    const mine = g.members[g.me]
    g.members[g.me] = { u: Math.max(this.stamp(), (mine?.u ?? 0) + 1), gone: true }
    await this.queueGroup(g, this.stateOf(g), liveCards(g), this.config.message_ttl_max, undefined, true).catch(() => {})
    await api.del(`/api/links/${g.own.link_id}`).catch(() => {})
    this.forgetGroup(g)
  }

  private stateOf(g: LocalGroup): GroupState {
    return { t: 'group', g: g.id, m: g.me, name: { ...g.name }, ttl: { ...g.ttl }, members: $state.snapshot(g.members) }
  }

  private sendState(g: LocalGroup, trigger?: string, to?: GroupCard[]) {
    return this.queueGroup(g, this.stateOf(g), to ?? liveCards(g).filter((c) => c.id !== g.me), this.config.message_ttl_max, trigger)
  }

  private async sendGroup(g: LocalGroup, plain: Plain, blobs?: string[]) {
    if (g.inviter) throw new Error('You can send messages once you are added to the group.')
    const p = { ...plain, g: g.id, m: g.me, sv: await membershipDigest(digestList(g)) } as Plain
    const cards = liveCards(g).sort((a, b) => Number(b.id === g.me) - Number(a.id === g.me))
    await this.queueGroup(g, p, cards, g.ttl.v, undefined, false, blobs)
  }

  // cards travel with the entry when the group may be gone by delivery
  private async queueGroup(g: LocalGroup, plain: Plain, cards: GroupCard[], ttl: number, trigger?: string, keep = false, blobs?: string[]) {
    const id = crypto.randomUUID()
    const secret = unb64(g.own.secret)
    const copies: Record<string, string> = {}
    for (const c of cards) copies[c.id] = c.id === g.me ? id : trigger ? await groupMessageId(secret, trigger, c.id) : crypto.randomUUID()
    const held = cards.filter((c) => keep || !isLive(g.members[c.id]))
    const o: Outgoing = { id, to: g.num, plain, blobs, at: this.serverNow(), ttl, group: g.id, copies }
    if (held.length) o.cards = Object.fromEntries(held.map((c) => [c.id, cardOf(c)]))
    this.outbox.push(o)
    this.saveOutbox()
    const entry = this.outbox.find((x) => x.id === id)!
    try {
      if (await this.postGroup(entry, new Set())) this.outbox = this.outbox.filter((x) => x.id !== id)
    } catch (e) {
      this.outbox = this.outbox.filter((x) => x.id !== id)
      throw e
    } finally {
      this.saveOutbox()
    }
  }

  // true once every copy is delivered or no longer wanted
  private async postGroup(o: Outgoing, skip: Set<string>): Promise<boolean> {
    const g = this.groups.find((x) => x.id === o.group)
    for (const [mid, postId] of Object.entries(o.copies ?? {})) {
      const e = g?.members[mid]
      const card = isLive(e) ? e.c : o.cards?.[mid]
      if (!card) {
        delete o.copies![mid]
        continue
      }
      const self = !!g && mid === g.me
      if (!self && (skip.has(card.server) || (this.retry.get(card.server)?.at ?? 0) > Date.now())) {
        skip.add(card.server)
        continue
      }
      if (o.held?.[mid] === card.token) continue
      try {
        const stored = await this.deliverCopy(o, card, postId, self ? g : undefined)
        delete o.copies![mid]
        if (o.held) delete o.held[mid]
        if (self && stored && g) this.addMine(g.num, stored.id, stored.created_at, stored.expires_at, o.plain)
        if (!self) this.retry.delete(card.server)
      } catch (err) {
        if (err instanceof Unreachable) {
          skip.add(card.server)
          this.later(card.server, err.retryAfter)
        } else if (err instanceof Gone || (err instanceof ApiError && err.status === 401)) {
          o.held = { ...(o.held ?? {}), [mid]: card.token }
        } else if (self) {
          throw err
        } else {
          delete o.copies![mid]
        }
      }
    }
    return !Object.keys(o.copies ?? {}).length
  }

  // no remove-contact prompt for a member's server
  private later(server: string, retryAfter = 0) {
    const r = this.retry.get(server)
    const step = retryAfter ? (r?.step ?? 0) : r ? r.step + 1 : 0
    const wait = (retryAfter || BACKOFF[Math.min(step, BACKOFF.length - 1)]) * (1 + Math.random() * 0.2)
    this.retry.set(server, { at: Date.now() + wait * 1000, step })
  }

  private async deliverCopy(o: Outgoing, card: GroupCard, id: string, own?: LocalGroup): Promise<Mail | null> {
    for (let i = 0; ; i++) {
      const devices = own ? await this.ownSealTo(i > 0) : await this.memberDevices(o.group!, card, i > 0)
      if (!devices.length && !own) throw new Error(`${card.name} has no devices that can receive messages right now.`)
      const m = await sealCopy(this.device!, devices, o.ttl, o.plain, id, !!own)
      try {
        if (!own) return await linkReq<Mail>(remote(card), 'POST', '/api/link/mail', m)
        try {
          return await api.post<Mail>(`/api/links/${own.own.link_id}/mail`, { ...m, blobs: o.blobs })
        } catch (e) {
          if (e instanceof ApiError) throw e
          throw new Unreachable()
        }
      } catch (e) {
        if (e instanceof ApiError && e.status === 409) return null
        if (!(e instanceof ApiError && e.status === 412) || i) throw e
      }
    }
  }

  // signing keys are kept to read a former member's mail
  private async memberDevices(gid: string, c: GroupCard, fresh = false): Promise<PublicDevice[]> {
    const key = `${gid}/${c.id}`
    const hit = this.memberLists.get(key)
    if (hit && !fresh && Date.now() - hit.at < 60_000) return hit.devices
    const r = await linkReq<{ account: string; devices: PublicDevice[] }>(remote(c), 'GET', '/api/link/devices')
    if (r.account !== c.account) throw new Error(`${c.name}'s server answered for someone else.`)
    const devices = (await Promise.all(r.devices.map(async (d) => ((await certOk(d)) ? d : null)))).filter((d): d is PublicDevice => !!d)
    this.memberLists.set(key, { at: Date.now(), devices })
    const g = this.groups.find((x) => x.id === gid)
    if (g) {
      const signers = { ...(g.signers?.[c.id] ?? {}), ...Object.fromEntries(devices.map((d) => [d.id, d.sign_pub])) }
      if (JSON.stringify(signers) !== JSON.stringify(g.signers?.[c.id] ?? {})) {
        g.signers = { ...(g.signers ?? {}), [c.id]: signers }
        this.saveContacts()
      }
    }
    return devices
  }

  private async groupSigners(g: LocalGroup, sender: string, m: Mail): Promise<Record<string, string> | null> {
    if (sender === g.me) {
      if (!this.known[m.from_device]) await this.refreshDevices().catch(() => {})
      return this.ownSigners()
    }
    const e = g.members[sender]
    if (isLive(e)) {
      if (!g.signers?.[sender]?.[m.from_device]) await this.memberDevices(g.id, e.c, true).catch(() => {})
      return g.signers?.[sender] ?? {}
    }
    if (g.inviter?.member === sender) {
      const c = this.contacts.find((x) => x.id === g.inviter!.contact)
      return c ? this.signersFor(c, m.from_device) : null
    }
    // a former member: only mail its server received before this account learned it left
    const at = g.left?.[sender]
    return e && at !== undefined && m.created_at <= at ? (g.signers?.[sender] ?? null) : null
  }

  // decrypted first, since the sender is named inside; nothing is used before the signature checks out
  private async openGroup(g: LocalGroup, m: Mail): Promise<{ plain: Plain; sender: string } | null> {
    const plain = await openSealed(this.device!, m)
    if (!plain || (plain.t !== 'text' && plain.t !== 'media' && plain.t !== 'group') || plain.g !== g.id || typeof plain.m !== 'string') return null
    const sender = plain.m
    const signers = await this.groupSigners(g, sender, m)
    if (!signers || !(await signedBy(m, signers))) return null
    this.checkDevices(g.num, m, 'to')
    return { plain, sender }
  }

  private groupMessage(g: LocalGroup, m: Mail, plain: Plain, sender: string): Message {
    const mine = sender === g.me
    return {
      id: m.id,
      from_user: mine ? this.me!.id : g.num,
      to_user: mine ? g.num : this.me!.id,
      created_at: m.created_at,
      expires_at: m.expires_at,
      plain,
      sender: mine ? undefined : sender,
    }
  }

  // states first, in arrival order; a second pass reads mail from members added by them
  private async readGroup(g: LocalGroup, list: Mail[]): Promise<Message[]> {
    const shown: { m: Mail; plain: Plain; sender: string }[] = []
    const order = new Map(list.map((m, i) => [m, i]))
    let rest = [...list]
    for (let pass = 0; pass < 2 && rest.length; pass++) {
      const later: Mail[] = []
      for (const m of rest) {
        const r = await this.openGroup(g, m)
        if (!r) later.push(m)
        else if (r.plain.t === 'group') await this.applyGroupState(g, r.plain, m, r.sender)
        else shown.push({ m, ...r })
        if (!this.groups.some((x) => x.id === g.id)) return []
      }
      rest = later
    }
    await this.rotate(g)
    return shown
      .sort((a, b) => a.m.created_at - b.m.created_at || order.get(a.m)! - order.get(b.m)!)
      .map(({ m, plain, sender }) => this.groupMessage(g, m, plain, sender))
  }

  private async takeGroupMail(g: LocalGroup, m: Mail) {
    if (this.messages[g.num]?.some((n) => n.id === m.id)) return
    const r = await this.openGroup(g, m)
    if (!r) return
    if (r.plain.t === 'group') return this.applyGroupState(g, r.plain, m, r.sender)
    const msg = this.groupMessage(g, m, r.plain, r.sender)
    this.messages[g.num] = [...(this.messages[g.num] ?? []), msg].sort((a, b) => a.created_at - b.created_at)
    if (r.sender === g.me) return
    const sv = (r.plain as { sv?: string }).sv
    if (sv && sv !== (await membershipDigest(digestList(g)))) this.syncTo(g, r.sender, m.id)
    if (!calls.active) tones.message()
    if (this.selected === g.num && !document.hidden) this.markSeen(g.num)
    else if (privateMessages()) this.notify('New message', '')
    else this.notify(g.name.v, `${this.memberName(g.num, r.sender)}: ${plainText(r.plain)}`)
  }

  private syncTo(g: LocalGroup, member: string, mail: string) {
    const key = `${g.id}/${member}`
    if (Date.now() - (this.synced.get(key) ?? 0) < 60_000) return
    this.synced.set(key, Date.now())
    const e = g.members[member]
    if (isLive(e)) this.sendState(g, `sync:${mail}`, [e.c]).catch(() => {})
  }

  private async applyGroupState(g: LocalGroup, p: GroupState, m: Mail, sender: string) {
    if (sender === g.me) return
    const bootstrap = !!g.inviter && sender === g.inviter.member
    const before = goneCount(g)
    this.cacheNames(g)
    const r = applyState($state.snapshot(g) as Group, p, sender, this.stamp(), bootstrap)
    if (!r) return
    if (bootstrap) {
      const mine = r.state.members[g.me]
      const inviter = r.state.members[sender]
      const c = this.contacts.find((x) => x.id === g.inviter!.contact)
      const ok =
        !!c &&
        isLive(mine) &&
        mine.c.server === location.origin &&
        mine.c.account === String(this.me!.id) &&
        isLive(inviter) &&
        sameAccount(c, inviter.c)
      if (!ok) return
    } else if (canon(r.state) === canon({ name: g.name, ttl: g.ttl, members: $state.snapshot(g.members) })) {
      return
    }
    const who = this.memberName(g.num, sender)
    g.name = r.state.name
    g.ttl = r.state.ttl
    g.members = r.state.members
    if (r.gone.length) g.left = { ...(g.left ?? {}), ...Object.fromEntries(r.gone.map((x) => [x, Math.min(g.left?.[x] ?? Infinity, this.serverNow())])) }
    if (bootstrap) {
      delete g.inviter
      g.own.base = goneCount(g)
      this.addEvent(g.num, 'You joined the group.')
    }
    this.cacheNames(g)
    this.touchGroup(g, m.created_at * 1000)
    if (!bootstrap) for (const x of r.added) this.addEvent(g.num, `${who} added ${this.memberName(g.num, x)}.`)
    for (const x of r.gone) this.addEvent(g.num, x === sender ? `${who} left.` : `${who} removed ${this.memberName(g.num, x)}.`)
    if (r.renamed && !bootstrap) this.addEvent(g.num, `${who} renamed the group to ${g.name.v}.`)
    if (r.timer && !bootstrap) this.addEvent(g.num, `${who} set messages to expire after ${fmtTtl(g.ttl.v)}.`)
    if (!isLive(g.members[g.me])) return this.leftGroup(g, `${who} removed you from ${g.name.v}.`)
    if (goneCount(g) > before) await this.rotate(g)
  }

  // every device of the account derives the same token; true when the full state went out
  private async rotate(g: LocalGroup): Promise<boolean> {
    const gen = ownGen(g)
    const mine = g.members[g.me]
    if (!isLive(mine) || mine.c.gen >= gen) return false
    const token = await groupToken(unb64(g.own.secret), gen)
    try {
      await api.put(`/api/links/${g.own.link_id}/token`, { gen, token })
    } catch (e) {
      console.warn('group token', e)
      return false
    }
    g.members[g.me] = { u: Math.max(this.stamp(), mine.u + 1), c: { ...mine.c, token, gen } }
    g.own.gen = Math.max(g.own.gen, gen)
    this.touchGroup(g)
    await this.sendState(g, `gen:${gen}`)
    return true
  }

  private groupInvite(c: Contact, p: Extract<Plain, { t: 'group_invite' }>, m: Mail) {
    if (![p.g, p.member, p.inviter].every(isUuid) || typeof p.name !== 'string' || !Array.isArray(p.members)) return
    if (this.groups.some((g) => g.id === p.g) || this.groupPrompts.some((x) => x.g === p.g)) return
    if ((this.goneGroups[p.g] ?? -1) >= m.created_at * 1000 || m.created_at + 7 * DAY < this.serverNow()) return
    const name = [...p.name].slice(0, 64).join('') || 'Group'
    this.groupPrompts.push({
      contact: c.id,
      from: this.userName(c.num),
      g: p.g,
      name,
      members: p.members.filter((x) => typeof x === 'string').slice(0, 50),
      inviter: p.inviter,
      member: p.member,
    })
    this.notify('Group invitation', `${this.userName(c.num)} invited you to ${name}.`)
  }

  // any of the inviting account's devices adds the member; the same message gives every device the same result
  private async groupJoin(c: Contact, p: Extract<Plain, { t: 'group_join' }>, m: Mail) {
    const g = this.groups.find((x) => x.id === p.g)
    const inv = g?.invites[c.id]
    const card = inv && validCard(p.card, inv.member)
    if (!g || !inv || inv.expires < this.serverNow() || !card || !sameAccount(c, card)) return
    delete g.invites[c.id]
    g.members[inv.member] = { u: m.created_at * 1000, c: card }
    this.cacheNames(g)
    this.touchGroup(g, m.created_at * 1000)
    this.addEvent(g.num, `${this.userName(c.num)} joined.`)
    await this.sendState(g, `join:${m.id}`)
  }

  private groupDecline(c: Contact, p: Extract<Plain, { t: 'group_decline' }>) {
    const g = this.groups.find((x) => x.id === p.g)
    if (!g?.invites[c.id]) return
    delete g.invites[c.id]
    this.touchGroup(g)
    this.addEvent(g.num, `${this.userName(c.num)} declined.`)
  }

  private cacheNames(g: LocalGroup) {
    const names = { ...(g.names ?? {}) }
    for (const c of liveCards(g)) names[c.id] = this.cardName(c)
    g.names = names
  }

  private touchGroup(g: LocalGroup, at = this.stamp()) {
    g.u = Math.max(at, g.u + 1)
    this.saveContacts()
    this.schedulePush()
  }

  private forgetGroup(g: LocalGroup, notice?: string) {
    if (!this.groups.some((x) => x.id === g.id)) return
    this.goneGroups[g.id] = Math.max(this.stamp(), g.u + 1)
    this.dropGroup(g)
    this.schedulePush()
    if (notice) this.flash(notice)
  }

  private leftGroup(g: LocalGroup, notice: string) {
    api.del(`/api/links/${g.own.link_id}`).catch(() => {})
    this.forgetGroup(g, notice)
  }

  private dropGroup(g: LocalGroup) {
    const num = g.num
    this.groups = this.groups.filter((x) => x.id !== g.id)
    delete this.messages[num]
    delete this.events[num]
    delete this.drafts[num]
    delete this.seen[num]
    delete this.deviceAlerts[num]
    this.opened = this.opened.filter((x) => x !== num)
    const keep = this.outbox.filter((o) => o.group !== g.id || o.cards)
    if (keep.length !== this.outbox.length) {
      this.outbox = keep
      this.saveOutbox()
    }
    this.detach(num)
    for (const k of [...this.memberLists.keys()]) if (k.startsWith(`${g.id}/`)) this.memberLists.delete(k)
    writeLocal(`caw.seen.${this.me!.id}`, this.seen)
    this.saveContacts()
    if (this.selected === num) this.navigate('/')
  }

  // a former member's attachments went with its group link
  mediaGone(num: number, n: Message): boolean {
    const g = this.group(num)
    return !!g && !!n.sender && !isLive(g.members[n.sender])
  }

  mediaFrom(num: number, n: Message): Remote | undefined {
    const g = this.group(num)
    if (!g) return n.from_user === this.me?.id ? this.remoteOf(num) : undefined
    if (n.from_user === this.me?.id || !n.sender) return undefined
    const e = g.members[n.sender]
    return isLive(e) ? remote(e.c) : undefined
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
        const g = this.groups.find((x) => x.own.link_id === m.link_id)
        if (g) this.forgetGroup(g)
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
