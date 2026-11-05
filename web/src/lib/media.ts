import {
  ALL_FORMATS,
  BlobSource,
  BufferTarget,
  CanvasSink,
  Conversion,
  Input,
  Mp4OutputFormat,
  Output,
  QUALITY_LOW,
  QUALITY_VERY_LOW,
  canEncodeAudio,
  canEncodeVideo,
  type Quality,
} from 'mediabunny'
import { ApiError } from './api'
import { Gone, linkAuth, Unreachable, type Remote } from './contacts'
import { aesGcm, b64, concat, random, unb64, type Bytes } from './crypto'

export type MediaPlain = {
  t: 'media'
  kind: 'image' | 'video'
  mime: string
  blob: string
  key: string
  size: number
  w: number
  h: number
  dur?: number
  thumb: string
  text?: string
}

export type Prepared = {
  kind: 'image' | 'video'
  mime: string
  data: Bytes
  w: number
  h: number
  dur?: number
  thumb: string
  preview: string
}

const IMAGE_MAX = 1600
const THUMB_MAX = 64
const BLOB_AAD = new TextEncoder().encode('caw-blob-v1')

export class TooLarge extends Error {}

function canvas(w: number, h: number) {
  const c = document.createElement('canvas')
  c.width = w
  c.height = h
  return c
}

function fit(w: number, h: number, max: number) {
  const s = Math.min(1, max / Math.max(w, h))
  return { w: Math.max(1, Math.round(w * s)), h: Math.max(1, Math.round(h * s)) }
}

function toBlob(c: HTMLCanvasElement, type: string, q: number): Promise<Blob | null> {
  return new Promise((r) => c.toBlob(r, type, q))
}

async function thumbOf(src: CanvasImageSource, w: number, h: number): Promise<string> {
  const t = fit(w, h, THUMB_MAX)
  const c = canvas(t.w, t.h)
  c.getContext('2d')!.drawImage(src, 0, 0, t.w, t.h)
  return c.toDataURL('image/jpeg', 0.5).split(',')[1]
}

const bytesOf = async (b: Blob) => new Uint8Array(await b.arrayBuffer()) as Bytes

// re-encoding strips exif, so location and camera metadata never leave the device
async function prepareImage(file: File): Promise<Prepared> {
  const bmp = await createImageBitmap(file, { imageOrientation: 'from-image' })
  try {
    const { w, h } = fit(bmp.width, bmp.height, IMAGE_MAX)
    const c = canvas(w, h)
    c.getContext('2d')!.drawImage(bmp, 0, 0, w, h)
    // safari cannot encode webp and silently returns png
    let out = await toBlob(c, 'image/webp', 0.8)
    if (out?.type !== 'image/webp') out = await toBlob(c, 'image/jpeg', 0.82)
    if (!out) throw new Error('Could not process the image.')
    return {
      kind: 'image',
      mime: out.type,
      data: await bytesOf(out),
      w,
      h,
      thumb: await thumbOf(bmp, bmp.width, bmp.height),
      preview: URL.createObjectURL(out),
    }
  } finally {
    bmp.close()
  }
}

async function transcode(file: Blob, short: number, quality: Quality, onProgress: (p: number) => void): Promise<Blob | null> {
  const [avc, aac] = await Promise.all([canEncodeVideo('avc'), canEncodeAudio('aac')])
  if (!avc) return null
  const input = new Input({ source: new BlobSource(file), formats: ALL_FORMATS })
  try {
    const track = await input.getPrimaryVideoTrack()
    if (!track) return null
    const portrait = track.displayHeight > track.displayWidth
    const size = Math.min(track.displayWidth, track.displayHeight) > short ? (portrait ? { width: short } : { height: short }) : {}
    const target = new BufferTarget()
    const conv = await Conversion.init({
      input,
      output: new Output({ format: new Mp4OutputFormat({ fastStart: 'in-memory' }), target }),
      tracks: 'primary',
      video: { ...size, codec: 'avc', quality, forceTranscode: true },
      audio: { codec: aac ? 'aac' : 'opus', quality: QUALITY_LOW, numberOfChannels: 1 },
    })
    if (!conv.isValid) return null
    conv.onProgress = onProgress
    await conv.execute()
    return target.buffer ? new Blob([target.buffer], { type: 'video/mp4' }) : null
  } catch (e) {
    console.warn('transcode', e)
    return null
  } finally {
    input.dispose()
  }
}

async function videoInfo(blob: Blob) {
  const input = new Input({ source: new BlobSource(blob), formats: ALL_FORMATS })
  try {
    const track = await input.getPrimaryVideoTrack()
    if (!track) throw new Error('No video found in this file.')
    const w = track.displayWidth
    const h = track.displayHeight
    const t = fit(w, h, THUMB_MAX * 4)
    const frame = await new CanvasSink(track, { width: t.w, height: t.h, fit: 'contain' }).getCanvas(await track.getFirstTimestamp())
    return {
      w,
      h,
      dur: Math.round(await input.computeDuration()),
      thumb: frame ? await thumbOf(frame.canvas, t.w, t.h) : '',
    }
  } finally {
    input.dispose()
  }
}

async function prepareVideo(file: File, limit: number, onProgress: (p: number) => void): Promise<Prepared> {
  const steps: [number, Quality][] = [
    [720, QUALITY_LOW],
    [480, QUALITY_VERY_LOW],
  ]
  let out: Blob | null = null
  for (const [i, [short, q]] of steps.entries()) {
    out = await transcode(file, short, q, (p) => onProgress((i + p) / steps.length))
    if (!out || out.size <= limit) break
  }
  // fall back to the original if transcoding failed or an mp4 original is smaller
  if (!out || (out.size > file.size && file.type === 'video/mp4')) out = file
  if (out.size > limit) throw new TooLarge()
  const info = await videoInfo(out)
  return { kind: 'video', mime: out.type || 'video/mp4', data: await bytesOf(out), ...info, preview: URL.createObjectURL(out) }
}

export async function prepare(file: File, limit: number, onProgress: (p: number) => void): Promise<Prepared> {
  const p = file.type.startsWith('video/') ? await prepareVideo(file, limit, onProgress) : await prepareImage(file)
  if (p.data.length > limit) {
    URL.revokeObjectURL(p.preview)
    throw new TooLarge()
  }
  return p
}

export async function encryptFile(data: Bytes) {
  const key = random(32)
  const iv = random(12)
  return { body: concat(iv, await aesGcm('encrypt', key, iv, BLOB_AAD, data)), key: b64(key) }
}

// uploads to the recipient's server via the contact link
export function upload(to: Remote, body: Bytes, onProgress: (p: number) => void): Promise<string> {
  return new Promise((resolve, reject) => {
    const x = new XMLHttpRequest()
    x.open('POST', `${to.server}/api/link/blobs`)
    x.setRequestHeader('authorization', linkAuth(to))
    x.setRequestHeader('content-type', 'application/octet-stream')
    x.upload.onprogress = (e) => e.lengthComputable && onProgress(e.loaded / e.total)
    x.onload = () => {
      let data: any = null
      try {
        data = JSON.parse(x.responseText)
      } catch {
        data = null
      }
      if (x.status >= 200 && x.status < 300 && data?.id) resolve(data.id)
      else if (x.status === 410) reject(new Gone())
      else if (x.status >= 500 || x.status === 429) reject(new Unreachable())
      else reject(new ApiError(x.status, data?.error ?? 'Upload failed.'))
    }
    x.onerror = () => reject(new Unreachable())
    x.send(body)
  })
}

const cache = new Map<string, Promise<string>>()

export function seedMedia(blob: string, url: string) {
  cache.set(blob, Promise.resolve(url))
}

// received media is on the home server; sent media is on the recipient's server, fetched via the contact link
export function loadMedia(m: MediaPlain, sentTo?: Remote): Promise<string> {
  let hit = cache.get(m.blob)
  if (!hit) {
    hit = (async () => {
      const res = sentTo
        ? await fetch(`${sentTo.server}/api/link/blobs/${m.blob}`, { headers: { authorization: linkAuth(sentTo) } })
        : await fetch(`/api/blobs/${m.blob}`, { credentials: 'same-origin' })
      if (!res.ok) throw new ApiError(res.status, 'Attachment unavailable.')
      const body = new Uint8Array(await res.arrayBuffer()) as Bytes
      const pt = await aesGcm('decrypt', unb64(m.key), body.slice(0, 12), BLOB_AAD, body.slice(12))
      return URL.createObjectURL(new Blob([pt], { type: m.mime }))
    })()
    hit.catch(() => cache.delete(m.blob))
    cache.set(m.blob, hit)
  }
  return hit
}

export function dropMedia(blob: string) {
  const hit = cache.get(blob)
  cache.delete(blob)
  hit?.then((u) => URL.revokeObjectURL(u)).catch(() => {})
}

export function fmtDur(s: number) {
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
}
