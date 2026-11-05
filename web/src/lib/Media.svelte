<script lang="ts">
  import Icon from './Icon.svelte'
  import type { Remote } from './contacts'
  import { fmtDur, loadMedia, type MediaPlain } from './media'

  let { m, sentTo }: { m: MediaPlain; sentTo?: Remote } = $props()

  let url = $state<string | null>(null)
  let failed = $state(false)
  let playing = $state(false)
  let full = $state(false)

  const box = $derived.by(() => {
    const s = Math.min(1, 280 / m.w, 320 / m.h)
    return `width:${Math.round(m.w * s)}px;aspect-ratio:${m.w}/${m.h}`
  })

  async function load() {
    try {
      url = await loadMedia(m, sentTo)
    } catch {
      failed = true
    }
  }

  $effect(() => {
    void m.blob
    url = null
    failed = false
    playing = false
    if (m.kind === 'image') load()
  })

  async function play() {
    playing = true
    if (!url) await load()
  }
</script>

<div class="media" style={box}>
  {#if failed}
    <div class="media-gone muted small"><Icon name="lock" size={14} />Attachment unavailable</div>
  {:else if m.kind === 'image'}
    <button class="media-btn" aria-label="View photo" onclick={() => url && (full = true)}>
      <img src={url ?? `data:image/jpeg;base64,${m.thumb}`} class:blur={!url} alt="" />
    </button>
  {:else if playing && url}
    <!-- svelte-ignore a11y_media_has_caption -->
    <video src={url} controls autoplay playsinline></video>
  {:else}
    <button class="media-btn" aria-label="Play video" onclick={play}>
      {#if m.thumb}<img src={`data:image/jpeg;base64,${m.thumb}`} class="blur" alt="" />{/if}
      <span class="media-play">
        {#if playing}<span class="spinner"></span>{:else}<Icon name="play_arrow" size={32} />{/if}
      </span>
      {#if m.dur}<span class="media-dur">{fmtDur(m.dur)}</span>{/if}
    </button>
  {/if}
</div>

{#if full && url}
  <!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
  <div class="lightbox" onclick={() => (full = false)}>
    <img src={url} alt="" />
    <button class="icon-btn" aria-label="Close" onclick={() => (full = false)}><Icon name="close" size={24} /></button>
  </div>
{/if}
