<script lang="ts">
  import { onDestroy, onMount } from 'svelte'
  import qrcode from 'qrcode-generator'
  import { errText } from '../lib/api'
  import { store } from '../lib/store.svelte'
  import Icon from '../lib/Icon.svelte'

  let { onclose }: { onclose: () => void } = $props()

  let tab = $state<'show' | 'enter'>('show')
  let code = $state<string | null>(null)
  let expires = $state(0)
  let now = $state(Math.floor(Date.now() / 1000))
  let failed = $state<string | null>(null)
  let entered = $state('')
  let busy = $state(false)
  let waiting = $state<{ num: number; words: string[] } | null>(null)
  const prompts = store.prompts.length

  const left = $derived(Math.max(0, expires - now))
  const qr = $derived.by(() => {
    if (!code) return null
    const q = qrcode(0, 'M')
    q.addData(code)
    q.make()
    return q
  })
  const svg = $derived(qr ? qr.createSvgTag({ cellSize: 4, margin: 2, scalable: true }) : '')
  const canShare = 'share' in navigator

  const tick = window.setInterval(() => (now = Math.floor(Date.now() / 1000)), 1000)
  onDestroy(() => clearInterval(tick))

  // a redeemed code arrives as a prompt; close the dialog
  $effect(() => {
    if (store.prompts.length > prompts) onclose()
  })

  async function newCode() {
    failed = null
    code = null
    try {
      const r = await store.showCode()
      code = r.code
      expires = r.expires_at - store.serverNow() + Math.floor(Date.now() / 1000)
    } catch (e) {
      failed = errText(e)
    }
  }

  onMount(newCode)

  async function copy() {
    if (!code) return
    try {
      await navigator.clipboard.writeText(code)
      store.flash('Code copied.')
    } catch {
      store.flash('Copy failed. Select the code and copy it instead.')
    }
  }

  function png(): Promise<Blob | null> {
    const q = qr!
    const n = q.getModuleCount()
    const cell = 12
    const margin = 4 * cell
    const c = document.createElement('canvas')
    c.width = c.height = n * cell + 2 * margin
    const g = c.getContext('2d')!
    g.fillStyle = '#fff'
    g.fillRect(0, 0, c.width, c.height)
    g.fillStyle = '#000'
    for (let r = 0; r < n; r++) for (let k = 0; k < n; k++) if (q.isDark(r, k)) g.fillRect(margin + k * cell, margin + r * cell, cell, cell)
    return new Promise((resolve) => c.toBlob(resolve, 'image/png'))
  }

  // shares the image for scanning and the text for pasting; targets use what they support
  async function share() {
    if (!code) return
    const text = `Add me on Caw. Scan the code, or in Add contact choose Enter a code and paste this:\n\n${code}`
    try {
      const blob = await png()
      const files = blob ? [new File([blob], 'caw-code.png', { type: 'image/png' })] : []
      if (files.length && navigator.canShare?.({ files, text })) await navigator.share({ files, text, title: 'Caw code' })
      else await navigator.share({ text, title: 'Caw code' })
    } catch (e) {
      if ((e as Error).name !== 'AbortError') store.flash('Sharing failed. Copy the code instead.')
    }
  }

  async function add(e: SubmitEvent) {
    e.preventDefault()
    busy = true
    failed = null
    try {
      const num = await store.enterCode(entered)
      waiting = { num, words: await store.contactWords(num) }
      store.open(num)
    } catch (err) {
      failed = errText(err)
    } finally {
      busy = false
    }
  }

  const hours = (num: number) => {
    const h = Math.max(1, Math.round(((store.contact(num)?.expires_at ?? 0) - store.serverNow()) / 3600))
    return h === 1 ? '1 hour' : `${h} hours`
  }
  const pad = (n: number) => String(n).padStart(2, '0')
  const fmt = (s: number) => (s >= 3600 ? `${Math.floor(s / 3600)}:${pad(Math.floor(s / 60) % 60)}:${pad(s % 60)}` : `${Math.floor(s / 60)}:${pad(s % 60)}`)
</script>

<div class="backdrop">
  <button class="backdrop-hit" aria-label="Close" onclick={onclose}></button>
  <div class="dialog add-contact" role="dialog" aria-modal="true" aria-label="Add contact">
    <div class="dialog-head">
      <h2>Add contact</h2>
      <button class="icon-btn" aria-label="Close" onclick={onclose}><Icon name="close" /></button>
    </div>

    {#if waiting}
      <div class="stack pad">
        <p>Waiting for {store.userName(waiting.num)} to accept.</p>
        <p class="words">{waiting.words.join(' ')}</p>
        <p class="muted small">Check that {store.userName(waiting.num)} sees the same words. The request ends in {hours(waiting.num)}.</p>
        <div><button class="btn filled" onclick={onclose}>Done</button></div>
      </div>
    {:else}
      <div class="segmented tabs" role="tablist">
        <button role="tab" aria-selected={tab === 'show'} class:on={tab === 'show'} onclick={() => (tab = 'show')}>Show my code</button>
        <button role="tab" aria-selected={tab === 'enter'} class:on={tab === 'enter'} onclick={() => (tab = 'enter')}>Enter a code</button>
      </div>

      {#if tab === 'show'}
        <div class="stack pad code-pane">
          {#if code && left > 0}
            <div class="qr" aria-label="Pairing code">{@html svg}</div>
            <p class="muted small">Scan with Caw on the other device, or share it. Expires in {fmt(left)}.</p>
            <div class="code-actions">
              <button class="btn tonal" onclick={copy}><Icon name="content_copy" size={18} />Copy code</button>
              {#if canShare}<button class="btn filled" onclick={share}><Icon name="share" size={18} />Share</button>{/if}
            </div>
            <textarea class="code-text" readonly rows="3" aria-label="Pairing code text">{code}</textarea>
          {:else if code}
            <p class="muted">This code expired.</p>
            <div><button class="btn filled" onclick={newCode}>New code</button></div>
          {:else if !failed}
            <p class="muted">Loading...</p>
          {/if}
          {#if failed}<p class="error">{failed}</p>{/if}
        </div>
      {:else}
        <form class="stack pad" onsubmit={add}>
          <label>Code <textarea rows="3" bind:value={entered} placeholder="caw:2:..." required></textarea></label>
          {#if failed}<p class="error">{failed}</p>{/if}
          <div><button class="btn filled" disabled={busy || !entered.trim()}>{busy ? 'Adding...' : 'Add contact'}</button></div>
        </form>
      {/if}
    {/if}
  </div>
</div>
