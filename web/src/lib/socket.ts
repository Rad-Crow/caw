export class Socket {
  private ws: WebSocket | null = null
  private retry = 0
  private stopped = false
  private timer: number | null = null
  // stable across reconnects so the server can restore an interrupted call
  private client = crypto.randomUUID()

  // url takes the client id; home server by default, a contact's server for outgoing calls
  constructor(
    private onMessage: (m: any) => void,
    private onState: (open: boolean) => void,
    private url: (client: string) => string = (client) =>
      `${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}/ws?client=${client}`,
  ) {}

  start() {
    this.stopped = false
    window.addEventListener('online', this.now)
    this.open()
  }

  // network restored: reconnect without waiting for the backoff
  private now = () => {
    if (this.stopped || this.ws) return
    if (this.timer !== null) clearTimeout(this.timer)
    this.retry = 0
    this.open()
  }

  stop() {
    this.stopped = true
    window.removeEventListener('online', this.now)
    if (this.timer !== null) clearTimeout(this.timer)
    this.ws?.close()
    this.ws = null
  }

  send(m: object): boolean {
    if (this.ws?.readyState !== WebSocket.OPEN) return false
    this.ws.send(JSON.stringify(m))
    return true
  }

  private open() {
    const ws = new WebSocket(this.url(this.client))
    this.ws = ws
    ws.onopen = () => {
      this.retry = 0
      this.onState(true)
    }
    ws.onmessage = (e) => {
      try {
        this.onMessage(JSON.parse(e.data))
      } catch (err) {
        console.error('bad ws message', err)
      }
    }
    ws.onclose = () => {
      if (this.ws !== ws) return
      this.ws = null
      this.onState(false)
      if (this.stopped) return
      const delay = Math.min(30000, 1000 * 2 ** this.retry++) * (0.5 + Math.random() / 2)
      this.timer = window.setTimeout(() => {
        this.timer = null
        this.open()
      }, delay)
    }
  }
}
