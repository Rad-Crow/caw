<script lang="ts">
  import { tick } from 'svelte'
  import { errText, fmtLeft, fmtTime, fmtTtl } from '../lib/api'
  import { calls } from '../lib/call.svelte'
  import { callText, store, type CawEvent, type Message } from '../lib/store.svelte'
  import Icon from '../lib/Icon.svelte'
  import Media from '../lib/Media.svelte'

  let { onadd }: { onadd: () => void } = $props()

  type Item = { key: string; at: number } & ({ kind: 'message'; n: Message } | { kind: 'event'; e: CawEvent })

  const PRESETS = [300, 3600, 8 * 3600, 86400, 3 * 86400, 7 * 86400, 30 * 86400]

  let timelineEl = $state<HTMLElement | null>(null)
  let composerEl = $state<HTMLTextAreaElement | null>(null)
  let timerOpen = $state(false)
  let words = $state<string[] | null>(null)
  let wordsOpen = $state(false)
  let contactOpen = $state(false)
  let aliasDraft = $state('')

  const peer = $derived(store.selected !== null ? (store.peer(store.selected) ?? null) : null)
  const ttl = $derived(peer ? store.timerFor(peer.id) : 0)
  const contact = $derived(peer ? store.contact(peer.id) : undefined)
  const pending = $derived(contact?.status === 'pending')
  const attachment = $derived(peer ? store.attachments[peer.id] : undefined)
  const fmtSize = (n: number) => (n < 1024 * 1024 ? `${Math.max(1, Math.round(n / 1024))} KB` : `${(n / 1024 / 1024).toFixed(1)} MB`)
  const canSend = $derived(contact?.status === 'active' && !store.deviceError)
  const presets = $derived(PRESETS.filter((p) => p >= store.config.message_ttl_min && p <= store.config.message_ttl_max))
  const items = $derived.by(() => {
    if (!peer) return []
    const list: Item[] = [
      ...[...(store.messages[peer.id] ?? []), ...store.pendingFor(peer.id)].map((n) => ({ kind: 'message' as const, key: n.id, at: n.created_at, n })),
      ...(store.events[peer.id] ?? []).map((e, i) => ({ kind: 'event' as const, key: `e${i}`, at: e.at, e })),
    ]
    return list.sort((a, b) => a.at - b.at)
  })
  const lastKey = $derived(items.at(-1)?.key)

  $effect(() => {
    void store.selected
    words = null
    wordsOpen = false
    timerOpen = false
    contactOpen = false
  })

  $effect(() => {
    if (!peer || store.composeFor !== peer.id || !canSend || !composerEl) return
    const el = composerEl
    tick().then(() => el.focus())
    store.composeFor = null
  })

  $effect(() => {
    void lastKey
    tick().then(() => timelineEl?.scrollTo({ top: timelineEl.scrollHeight }))
  })

  async function send(e: SubmitEvent) {
    e.preventDefault()
    if (!peer || !canSend) return
    const id = peer.id
    const text = (store.drafts[id] ?? '').trim()
    const media = store.attachments[id]
    if (!text && !media) return
    if (media?.status !== undefined && media.status !== 'ready') return
    store.drafts[id] = ''
    try {
      if (media) await store.sendMedia(id, text)
      else await store.send(id, { t: 'text', text })
    } catch (err) {
      store.drafts[id] = text
      store.flash(`Message not sent: ${errText(err)}`)
    }
  }

  async function pick(e: Event) {
    const input = e.currentTarget as HTMLInputElement
    const file = input.files?.[0]
    input.value = ''
    if (!file || !peer) return
    try {
      await store.attach(peer.id, file)
    } catch (err) {
      store.flash(errText(err))
    }
  }

  function onKey(e: KeyboardEvent) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      ;(e.currentTarget as HTMLTextAreaElement).form?.requestSubmit()
    }
  }

  async function call() {
    if (!peer) return
    try {
      await calls.call(peer)
    } catch (e) {
      store.flash(`Call failed: ${errText(e)}`)
    }
  }

  async function pickTimer(secs: number) {
    timerOpen = false
    if (!peer || secs === ttl) return
    try {
      await store.setTimer(peer.id, secs)
    } catch (e) {
      store.flash(errText(e))
    }
  }

  function toggleContact() {
    contactOpen = !contactOpen
    if (contactOpen && peer) aliasDraft = peer.name
  }

  function saveContact(e: SubmitEvent) {
    e.preventDefault()
    if (!peer) return
    store.setAlias(peer.id, aliasDraft)
    contactOpen = false
  }

  function resetContact() {
    if (!peer) return
    store.setAlias(peer.id, '')
    contactOpen = false
  }

  let removing = $state(false)

  async function removeContact() {
    if (!peer) return
    if (!removing) {
      removing = true
      return
    }
    removing = false
    contactOpen = false
    await store.removeContact(peer.id)
  }

  async function toggleWords() {
    wordsOpen = !wordsOpen
    if (wordsOpen && peer && !words) {
      try {
        words = await store.contactWords(peer.id)
      } catch (e) {
        store.flash(errText(e))
        wordsOpen = false
      }
    }
  }
</script>

<svelte:window
  onclick={(e) => {
    if (!(e.target as Element).closest('.pop-anchor')) {
      timerOpen = false
      wordsOpen = false
      contactOpen = false
    }
  }}
/>

{#if peer}
  <section class="card caw">
    <div class="caw-head">
      <button class="icon-btn back" aria-label="Back" onclick={() => store.navigate('/')}><Icon name="arrow_back" /></button>
      <span class="avatar">{peer.name.slice(0, 1).toUpperCase()}</span>
      <div class="grow pop-anchor">
        <button class="contact-btn" aria-haspopup="dialog" aria-expanded={contactOpen} title="Edit contact" onclick={toggleContact}>
          <strong>{peer.name}</strong>
          <span class="muted small">{pending ? 'Waiting to accept' : peer.host}</span>
        </button>
        {#if contactOpen}
          <form class="menu pop contact-pop" aria-label="Contact" onsubmit={saveContact}>
            <label>Name <input bind:value={aliasDraft} maxlength="64" required /></label>
            <p class="muted small">
              {peer.host}{#if store.contact(peer.id)?.alias} &middot; their name {store.accountName(peer.id)}{/if}
            </p>
            <div class="pop-actions">
              <button type="button" class="btn text" disabled={!store.contact(peer.id)?.alias} onclick={resetContact}>Reset</button>
              <button class="btn filled">Save</button>
            </div>
            <button type="button" class="btn text danger-text" onclick={removeContact}>{removing ? 'Tap again to remove' : 'Remove contact'}</button>
          </form>
        {/if}
      </div>
      <button
        class="icon-btn fav-btn"
        class:on={store.isFavorite(peer.id)}
        aria-label={store.isFavorite(peer.id) ? 'Remove favorite' : 'Add favorite'}
        aria-pressed={store.isFavorite(peer.id)}
        title={store.isFavorite(peer.id) ? 'Remove favorite' : 'Add favorite'}
        onclick={() => store.toggleFavorite(peer.id)}
      >
        <Icon name={store.isFavorite(peer.id) ? 'favorite' : 'favorite_border'} />
      </button>
      <div class="pop-anchor">
        <button class="btn text timer-btn" aria-haspopup="menu" aria-expanded={timerOpen} onclick={() => (timerOpen = !timerOpen)} title="Message expiry" disabled={pending}>
          <Icon name="timer" size={18} />{fmtTtl(ttl)}
        </button>
        {#if timerOpen}
          <div class="menu pop" role="menu">
            <div class="menu-label">Expire after</div>
            {#each presets as p (p)}
              <button role="menuitemradio" aria-checked={p === ttl} class:checked={p === ttl} onclick={() => pickTimer(p)}>{fmtTtl(p)}</button>
            {/each}
          </div>
        {/if}
      </div>
      <div class="pop-anchor">
        <button class="icon-btn" aria-label="Verification words" aria-expanded={wordsOpen} onclick={toggleWords}><Icon name="shield" /></button>
        {#if wordsOpen}
          <div class="menu pop words-pop" role="dialog" aria-label="Verification words">
            <div class="menu-label">Verification words</div>
            {#if words}
              <p class="words">{words.join(' ')}</p>
            {:else}
              <p class="muted small">Loading...</p>
            {/if}
            <p class="muted small">
              If {peer.name} sees the same words, your connection is verified.
            </p>
          </div>
        {/if}
      </div>
      <button class="btn filled go" disabled={!!calls.active || !canSend} onclick={call}><Icon name="call" size={20} />Call</button>
    </div>

    {#if store.deviceAlerts[peer.id]}
      <div class="banner notice" role="alert">
        <p>A message with {peer.name} was also encrypted for a device that isn't yours.</p>
        <span>
          <button class="btn text" onclick={() => store.navigate('/account')}>Check your devices</button>
          <button class="btn text" onclick={() => store.dismissAlert(peer.id)}>Dismiss</button>
        </span>
      </div>
    {/if}

    <div class="timeline" bind:this={timelineEl}>
      <div class="sealed-hint muted small"><Icon name="lock" size={14} />End-to-end encrypted. Messages expire.</div>
      {#each items as it (it.key)}
        {#if it.kind === 'event'}
          <div class="event muted">{it.e.text}</div>
        {:else if it.n.plain?.t === 'call'}
          {@const missed = it.n.plain.outcome === 'missed' && it.n.from_user === peer.id}
          <div class="event" class:missed>
            <Icon name={missed ? 'call_missed' : it.n.from_user === peer.id ? 'call_received' : 'call_made'} size={16} />
            <span>{callText(it.n, store.me?.id)}</span>
            <span class="muted">&middot; {fmtTime(it.n.created_at)}</span>
          </div>
        {:else}
          <div class="msg" class:mine={it.n.from_user === store.me?.id}>
            {#if it.n.plain?.t === 'media'}
              <Media m={it.n.plain} sentTo={it.n.from_user === store.me?.id ? store.remoteOf(peer.id) : undefined} />
              {#if it.n.plain.text}<div class="bubble">{it.n.plain.text}</div>{/if}
            {:else if it.n.plain?.t === 'text'}
              <div class="bubble">{it.n.plain.text}</div>
            {:else}
              <div class="bubble sealed"><Icon name="lock" size={14} />Not readable on this device</div>
            {/if}
            <div class="meta">{it.n.pending ? 'Sending...' : `${fmtTime(it.n.created_at)} \u00b7 ${fmtLeft(it.n.expires_at - store.clock)}`}</div>
          </div>
        {/if}
      {:else}
        <div class="empty muted">
          <span class="avatar big">{peer.name.slice(0, 1).toUpperCase()}</span>
          <p>No messages.</p>
          <p class="small">Messages expire after {fmtTtl(ttl)}.</p>
        </div>
      {/each}
    </div>

    {#if pending}
      <div class="seal-count small"><Icon name="lock" size={14} />Waiting for {peer.name} to accept.</div>
    {/if}
    {#if attachment}
      <div class="attachment">
        {#if attachment.prepared}
          {#if attachment.prepared.kind === 'image'}
            <img src={attachment.prepared.preview} alt="" />
          {:else}
            <!-- svelte-ignore a11y_media_has_caption -->
            <video src={attachment.prepared.preview} muted></video>
          {/if}
        {:else}
          <span class="spinner"></span>
        {/if}
        <span class="grow small">
          {#if attachment.status === 'preparing'}
            Compressing{attachment.progress > 0 ? ` ${Math.round(attachment.progress * 100)}%` : '...'}
          {:else if attachment.status === 'sending'}
            Sending {Math.round(attachment.progress * 100)}%
          {:else if attachment.prepared}
            {attachment.prepared.kind === 'video' ? 'Video' : 'Photo'} &middot; {fmtSize(attachment.prepared.data.length)}
          {/if}
        </span>
        {#if attachment.status !== 'sending'}
          <button class="icon-btn" aria-label="Remove attachment" onclick={() => store.detach(peer.id)}><Icon name="close" size={20} /></button>
        {/if}
      </div>
    {/if}
    <form class="composer" onsubmit={send}>
      <label class="icon-btn attach" class:disabled={!canSend || !!attachment} aria-label="Attach photo or video">
        <Icon name="attach_file" size={22} />
        <input type="file" accept="image/*,video/*" hidden disabled={!canSend || !!attachment} onchange={pick} />
      </label>
      <textarea
        rows="1"
        placeholder={canSend ? `Message to ${peer.name}` : 'Unavailable'}
        bind:value={store.drafts[peer.id]}
        bind:this={composerEl}
        onkeydown={onKey}
        maxlength="4000"
        disabled={!canSend}
      ></textarea>
      <button class="icon-btn filled" aria-label="Send" disabled={!canSend || (attachment ? attachment.status !== 'ready' : !store.drafts[peer.id]?.trim())}><Icon name="send" size={20} /></button>
    </form>
  </section>
{:else}
  <div class="empty muted landing">
    <Icon name="add_comment" size={56} />
    <p>{store.contacts.length ? 'Select a contact.' : 'Add a contact to start.'}</p>
    <button class="btn tonal" onclick={onadd}><Icon name="person_add" size={20} />Add contact</button>
  </div>
{/if}
