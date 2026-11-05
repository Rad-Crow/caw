<script lang="ts">
  import { onDestroy } from 'svelte'
  import { calls } from '../lib/call.svelte'
  import { errText, fmtDuration } from '../lib/api'
  import { store } from '../lib/store.svelte'
  import Icon from '../lib/Icon.svelte'

  let now = $state(Date.now())
  const tick = setInterval(() => (now = Date.now()), 1000)
  onDestroy(() => clearInterval(tick))

  const STATUS = { calling: 'Ringing...', connecting: 'Connecting...', active: '' }

  async function accept() {
    try {
      await calls.accept()
    } catch (e) {
      store.flash(`Answer failed: ${errText(e)}`)
    }
  }
</script>

{#if calls.incoming}
  <div class="backdrop">
    <div class="dialog ringing" role="alertdialog" aria-label="Incoming call">
      <div class="avatar big pulse">{store.nameFor(calls.incoming.from).slice(0, 1).toUpperCase()}</div>
      <h2>{store.nameFor(calls.incoming.from)}</h2>
      <p class="muted">Incoming call</p>
      <div class="row">
        <button class="fab danger" aria-label="Decline" onclick={() => calls.decline()}><Icon name="call_end" /></button>
        <button class="fab go" aria-label="Answer" onclick={accept}><Icon name="call" /></button>
      </div>
    </div>
  </div>
{/if}

{#if calls.active}
  <div class="callbar" class:live={calls.active.status === 'active'}>
    <span class="avatar">{store.nameFor(calls.active.with).slice(0, 1).toUpperCase()}</span>
    <div class="grow">
      <strong>{store.nameFor(calls.active.with)}</strong>
      <span class="small">
        {#if calls.active.reconnecting}
          Reconnecting...
        {:else if calls.active.status === 'active' && calls.active.startedAt}
          {fmtDuration((now - calls.active.startedAt) / 1000)}
        {:else}
          {STATUS[calls.active.status]}
        {/if}
      </span>
    </div>
    <button
      class="icon-btn tonal"
      class:toggled={calls.active.muted}
      aria-label={calls.active.muted ? 'Unmute' : 'Mute'}
      aria-pressed={calls.active.muted}
      onclick={() => calls.toggleMute()}
    >
      <Icon name={calls.active.muted ? 'mic_off' : 'mic'} />
    </button>
    <button class="btn filled danger" onclick={() => calls.hangup()}><Icon name="call_end" size={20} />Hang up</button>
  </div>
{/if}
