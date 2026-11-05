import { api, type IceConfig, type Peer } from './api'
import { denoise, type Mic } from './denoise'
import { privateCalls } from './prefs'
import { Socket } from './socket'
import { tones } from './tones'

export type CallStatus = 'calling' | 'connecting' | 'active'

export interface CallEnd {
  peer: Peer
  secs: number | null
  reason: string
}

interface ActiveCall {
  id: string
  status: CallStatus
  outgoing: boolean
  rang: boolean
  with: Peer
  muted: boolean
  startedAt: number | null
  reconnecting: boolean
}

interface PeerConn {
  pc: RTCPeerConnection
  audio: HTMLAudioElement
  pending: RTCIceCandidateInit[]
  // the initial offerer also drives ice restarts so both ends never offer at once
  offerer: boolean
  retry: number | null
}

// max time a call stays "reconnecting" before it ends
const RECOVER_MS = 30_000

const REASONS: Record<string, string> = {
  declined: 'Call declined',
  no_answer: 'No answer',
  cancelled: 'Call cancelled',
  completed: 'Call ended',
  answered_elsewhere: 'Answered on another device',
  left: 'Call ended',
  lost: 'Connection lost',
  unverified: 'Call could not be verified',
}

type Hooks = {
  // home server socket; incoming calls ring and are answered there
  own: (m: object) => boolean
  // a contact's server; outgoing calls to them run there as a guest
  guestUrl: (contact: number) => ((client: string) => string) | null
  contactFor: (linkId: string) => Peer | null
  flash: (text: string) => void
  notify: (title: string, body: string) => void
  ended: (e: CallEnd) => void
  name: (u: Peer) => string
  // device signature over a session description, and its check against the contact's devices
  sign: (callId: string, d: RTCSessionDescriptionInit) => Promise<{ device: string; sig: string } | null>
  verify: (contact: number, callId: string, d: RTCSessionDescriptionInit, device?: string, sig?: string) => Promise<boolean>
}

type Via = 'own' | 'guest'

const DIAL_MS = 10_000

class CallManager {
  incoming = $state<{ call_id: string; from: Peer } | null>(null)
  active = $state<ActiveCall | null>(null)

  private peers = new Map<string, PeerConn>()
  private local: MediaStream | null = null
  private mic: Mic | null = null
  private ice: RTCIceServer[] = []
  private queue: Promise<void> = Promise.resolve()
  private hooks: Hooks = {
    own: () => false,
    guestUrl: () => null,
    contactFor: () => null,
    flash: () => {},
    notify: () => {},
    ended: () => {},
    name: (u) => u.name,
    sign: async () => null,
    verify: async () => false,
  }
  private via: Via = 'own'
  private guest: Socket | null = null
  private dialed: (() => void) | null = null
  private reason = 'hangup'
  private giveUp: number | null = null

  bind(hooks: Hooks) {
    this.hooks = hooks
  }

  private send(m: object): boolean {
    return this.via === 'guest' ? (this.guest?.send(m) ?? false) : this.hooks.own(m)
  }

  async call(user: Peer) {
    if (this.active) return
    if (!window.isSecureContext) throw new Error('calls require HTTPS')
    const url = this.hooks.guestUrl(user.id)
    if (!url) throw new Error('This contact is not available.')
    const id = crypto.randomUUID()
    this.active = { id, status: 'calling', outgoing: true, rang: false, with: user, muted: false, startedAt: null, reconnecting: false }
    try {
      await this.prepare(id)
    } catch (e) {
      this.cleanup()
      throw e
    }
    if (this.active?.id !== id) return
    this.via = 'guest'
    try {
      await new Promise<void>((resolve, reject) => {
        this.dialed = resolve
        window.setTimeout(() => reject(new Error("Can't reach this contact's server.")), DIAL_MS)
        this.guest = new Socket((m) => this.handle(m, 'guest'), () => {}, url)
        this.guest.start()
      })
    } catch (e) {
      this.cleanup()
      throw e
    } finally {
      this.dialed = null
    }
    if (this.active?.id !== id) return
    if (!this.send({ type: 'call', call_id: id })) {
      this.cleanup()
      throw new Error("Can't reach this contact's server.")
    }
    tones.ringback()
  }

  async accept() {
    const inc = this.incoming
    if (!inc || this.active) return
    this.incoming = null
    tones.stop()
    this.via = 'own'
    this.active = { id: inc.call_id, status: 'connecting', outgoing: false, rang: true, with: inc.from, muted: false, startedAt: null, reconnecting: false }
    try {
      await this.prepare(inc.call_id)
    } catch (e) {
      this.cleanup()
      throw e
    }
    if (this.active?.id === inc.call_id) this.send({ type: 'accept', call_id: inc.call_id })
  }

  decline() {
    if (!this.incoming) return
    this.hooks.own({ type: 'reject', call_id: this.incoming.call_id })
    this.incoming = null
    tones.stop()
  }

  hangup() {
    if (!this.active) return
    this.send({ type: 'hangup', call_id: this.active.id })
    this.reason = 'hangup'
    this.cleanup()
    tones.hangup()
  }

  toggleMute() {
    if (!this.active || !this.local) return
    const muted = !this.active.muted
    for (const t of this.local.getAudioTracks()) t.enabled = !muted
    this.active.muted = muted
  }

  handle(m: any, via: Via = 'own') {
    this.queue = this.queue.then(() => this.process(m, via)).catch((e) => console.error('call', e))
  }

  private async prepare(id: string) {
    const [stream, ice] = await Promise.all([
      navigator.mediaDevices.getUserMedia({
        audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true, channelCount: 1 },
        video: false,
      }),
      api.get<IceConfig>('/api/turn'),
    ])
    const mic = await denoise(stream)
    if (this.active?.id !== id) {
      mic.stop()
      return
    }
    this.mic = mic
    this.local = mic.stream
    this.ice = ice.ice_servers
  }

  private peer(peerId: string): PeerConn {
    const existing = this.peers.get(peerId)
    if (existing) return existing
    const pc = new RTCPeerConnection({ iceServers: this.ice })
    const audio = new Audio()
    audio.autoplay = true
    const p: PeerConn = { pc, audio, pending: [], offerer: false, retry: null }
    if (this.local) for (const t of this.local.getTracks()) pc.addTrack(t, this.local)
    pc.onicecandidate = (e) => {
      if (e.candidate && this.active) {
        this.send({
          type: 'signal',
          call_id: this.active.id,
          to: peerId,
          data: { candidate: e.candidate.toJSON() },
        })
      }
    }
    pc.ontrack = (e) => {
      audio.srcObject = e.streams[0] ?? new MediaStream([e.track])
      audio.play().catch(() => {})
    }
    pc.oniceconnectionstatechange = () => {
      const st = pc.iceConnectionState
      if (p.retry !== null && (st === 'connected' || st === 'completed') && pc.connectionState === 'connected') this.recovered(p)
    }
    pc.onconnectionstatechange = () => {
      if (!this.active || !this.peers.has(peerId)) return
      const st = pc.connectionState
      if (st === 'connected') {
        this.recovered(p)
        if (this.active.status !== 'active') {
          tones.stop()
          this.active.status = 'active'
          this.active.startedAt = Date.now()
        }
      } else if (st === 'failed') {
        this.recover(peerId)
      } else if (st === 'disconnected') {
        // often recovers on its own within a few seconds
        window.setTimeout(() => {
          if (this.peers.get(peerId) === p && p.pc.connectionState === 'disconnected') this.recover(peerId)
        }, 2000)
      }
    }
    this.peers.set(peerId, p)
    return p
  }

  private async sendSdp(callId: string, to: string, pc: RTCPeerConnection) {
    const sdp = pc.localDescription!.toJSON() as RTCSessionDescriptionInit
    const proof = await this.hooks.sign(callId, sdp)
    if (proof) this.send({ type: 'signal', call_id: callId, to, data: { sdp, ...proof } })
  }

  private unverified() {
    if (!this.active) return
    this.send({ type: 'hangup', call_id: this.active.id })
    this.reason = 'unverified'
    this.cleanup()
    tones.hangup()
    this.hooks.flash(REASONS.unverified)
  }

  private async process(m: any, via: Via) {
    const mine = this.active?.id === m.call_id
    switch (m.type) {
      case 'hello':
        if (via === 'guest') this.dialed?.()
        // reconnected: restart media if the server still holds the call, otherwise the call is lost
        if (this.active && this.active.status !== 'calling' && via === this.via) {
          if (!(m.calls ?? []).includes(this.active.id)) this.lost()
          else for (const id of this.peers.keys()) this.recover(id)
        }
        for (const inc of m.incoming ?? []) await this.process({ type: 'incoming', ...inc }, via)
        return

      case 'incoming': {
        if (mine || this.incoming?.call_id === m.call_id) return
        // another of the account's devices may still take it
        const from = this.hooks.contactFor(m.link_id)
        if (!from || this.active || this.incoming) return
        this.incoming = { call_id: m.call_id, from }
        tones.ring()
        this.hooks.notify(privateCalls() ? 'Incoming call' : `Incoming call from ${this.hooks.name(from)}`, 'Open Caw to answer.')
        return
      }

      case 'ringing':
        if (mine && this.active) this.active.rang = true
        return

      case 'joined':
        if (!mine) return
        for (const peerId of m.peers) {
          const p = this.peer(peerId)
          p.offerer = true
          await p.pc.setLocalDescription(await p.pc.createOffer())
          await this.sendSdp(m.call_id, peerId, p.pc)
        }
        return

      case 'peer_joined':
        if (!mine || !this.active) return
        tones.stop()
        this.active.status = 'connecting'
        this.peer(m.peer_id)
        return

      case 'signal': {
        if (!mine) return
        const p = this.peers.get(m.from)
        if (!p) return
        const { sdp, candidate, restart } = m.data ?? {}
        if (restart) {
          if (p.offerer) await this.restart(m.from)
        } else if (sdp) {
          if (!this.active || !(await this.hooks.verify(this.active.with.id, m.call_id, sdp, m.data.device, m.data.sig))) return this.unverified()
          await p.pc.setRemoteDescription(sdp)
          for (const c of p.pending.splice(0)) await p.pc.addIceCandidate(c).catch(() => {})
          if (sdp.type === 'offer') {
            await p.pc.setLocalDescription()
            await this.sendSdp(m.call_id, m.from, p.pc)
          }
        } else if (candidate) {
          if (p.pc.remoteDescription) await p.pc.addIceCandidate(candidate).catch(() => {})
          else p.pending.push(candidate)
        }
        return
      }

      case 'peer_left':
        if (mine) this.closePeer(m.peer_id)
        return

      case 'ended':
        if (this.incoming?.call_id === m.call_id) {
          this.incoming = null
          tones.stop()
        }
        if (mine) {
          this.reason = m.reason
          this.cleanup()
          tones.hangup()
          this.hooks.flash(REASONS[m.reason] ?? 'Call ended')
        }
        return

      case 'error':
        if (mine) {
          this.cleanup()
          this.hooks.flash(m.message)
        }
        return
    }
  }

  // media path lost (network change, dropped socket): retry for up to RECOVER_MS before ending the call
  private recover(peerId: string) {
    const p = this.peers.get(peerId)
    if (!this.active || !p) return
    this.active.reconnecting = true
    this.giveUp ??= window.setTimeout(() => this.lost(), RECOVER_MS)
    if (p.retry === null) {
      this.restart(peerId)
      // the media path may survive a socket drop, so a settled connection also counts as recovered
      p.retry = window.setInterval(() => {
        if (p.pc.connectionState === 'connected' && p.pc.signalingState === 'stable') this.recovered(p)
        else this.restart(peerId)
      }, 5000)
    }
  }

  private recovered(p: PeerConn) {
    if (p.retry !== null) clearInterval(p.retry)
    p.retry = null
    if ([...this.peers.values()].some((x) => x.retry !== null)) return
    if (this.giveUp !== null) clearTimeout(this.giveUp)
    this.giveUp = null
    if (this.active) this.active.reconnecting = false
  }

  private async restart(peerId: string) {
    const p = this.peers.get(peerId)
    if (!this.active || !p) return
    const callId = this.active.id
    if (!p.offerer) {
      this.send({ type: 'signal', call_id: callId, to: peerId, data: { restart: true } })
      return
    }
    try {
      p.pc.restartIce()
      await p.pc.setLocalDescription(await p.pc.createOffer({ iceRestart: true }))
      await this.sendSdp(callId, peerId, p.pc)
    } catch (e) {
      console.warn('ice restart', e)
    }
  }

  private lost() {
    if (!this.active) return
    this.send({ type: 'hangup', call_id: this.active.id })
    this.reason = 'lost'
    this.cleanup()
    tones.hangup()
    this.hooks.flash(REASONS.lost)
  }

  private closePeer(peerId: string) {
    const p = this.peers.get(peerId)
    if (!p) return
    if (p.retry !== null) clearInterval(p.retry)
    this.peers.delete(peerId)
    p.pc.close()
    p.audio.srcObject = null
  }

  private cleanup() {
    const a = this.active
    if (a?.outgoing && a.rang && this.reason !== 'unverified') {
      const secs = a.startedAt ? Math.round((Date.now() - a.startedAt) / 1000) : null
      this.hooks.ended({ peer: a.with, secs, reason: this.reason })
    }
    this.reason = 'hangup'
    tones.stop()
    if (this.giveUp !== null) clearTimeout(this.giveUp)
    this.giveUp = null
    for (const id of [...this.peers.keys()]) this.closePeer(id)
    this.mic?.stop()
    this.mic = null
    this.local = null
    this.active = null
    this.guest?.stop()
    this.guest = null
    this.via = 'own'
  }
}

export const calls = new CallManager()
