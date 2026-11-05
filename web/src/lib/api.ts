// the other side of a conversation or call; id is the contact's local number
export interface Peer {
  id: number
  host: string
  name: string
}

export interface Me {
  id: number
  username: string
  display_name: string
  is_admin: boolean
  must_change?: boolean
}

export interface Settings {
  server_name: string
  registration_open: boolean
  ring_timeout_secs: number
  message_ttl_default: number
  message_ttl_min: number
  message_ttl_max: number
  max_upload_mb: number
  device_expiry_days: number
  pairing_code_minutes: number
}

export interface AdminUser {
  id: number
  username: string
  display_name: string
  is_admin: boolean
  disabled: boolean
  created_at: number
  last_seen: number | null
  online: boolean
}

export interface Invite {
  id: number
  token: string
  note: string
  is_admin: boolean
  max_uses: number | null
  uses: number
  expires_at: number | null
  created_at: number
  url: string
}

export interface IceConfig {
  ice_servers: RTCIceServer[]
  ttl: number
}

export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message)
  }
}

async function req<T>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await fetch(path, {
    method,
    credentials: 'same-origin',
    headers: body === undefined ? {} : { 'content-type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
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

export const api = {
  get: <T>(path: string) => req<T>('GET', path),
  post: <T>(path: string, body: unknown = {}) => req<T>('POST', path, body),
  patch: <T>(path: string, body: unknown) => req<T>('PATCH', path, body),
  put: <T>(path: string, body: unknown) => req<T>('PUT', path, body),
  del: <T>(path: string, body?: unknown) => req<T>('DELETE', path, body),
}

export function errText(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}

export function fmtTime(ts: number): string {
  const d = new Date(ts * 1000)
  const now = new Date()
  if (d.toDateString() === now.toDateString()) {
    return d.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })
  }
  return d.toLocaleDateString([], { month: 'short', day: 'numeric' }) +
    ' ' + d.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })
}

export function fmtDuration(secs: number): string {
  const s = Math.max(0, Math.floor(secs))
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const ss = String(s % 60).padStart(2, '0')
  return h ? `${h}:${String(m).padStart(2, '0')}:${ss}` : `${m}:${ss}`
}

export function fmtTtl(secs: number): string {
  if (secs % 86400 === 0) return `${secs / 86400} day${secs === 86400 ? '' : 's'}`
  if (secs % 3600 === 0) return `${secs / 3600} hour${secs === 3600 ? '' : 's'}`
  return `${Math.round(secs / 60)} min`
}

export function fmtLeft(secs: number): string {
  if (secs >= 86400) return `${Math.floor(secs / 86400)}d left`
  if (secs >= 3600) return `${Math.floor(secs / 3600)}h left`
  return `${Math.max(1, Math.ceil(secs / 60))}m left`
}
