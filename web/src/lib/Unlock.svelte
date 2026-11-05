<script lang="ts">
  import { errText } from './api'
  import { store } from './store.svelte'

  let password = $state('')
  let error = $state('')
  let busy = $state(false)
  let confirmOver = $state(false)

  async function run(fn: () => Promise<void>) {
    busy = true
    error = ''
    try {
      await fn()
      password = ''
    } catch (err) {
      error = errText(err)
    } finally {
      busy = false
    }
  }

  function submit(e: SubmitEvent) {
    e.preventDefault()
    run(() => store.unlockOld(password))
  }
</script>

<form class="banner info unlock" onsubmit={submit}>
  <p>Your contacts are locked with your previous password. Enter it to unlock them.</p>
  <div class="row">
    <input
      type="password"
      bind:value={password}
      autocomplete="current-password"
      aria-label="Previous password"
      placeholder="Previous password"
      required
    />
    <button class="btn filled" disabled={busy}>Unlock</button>
  </div>
  {#if error}<p class="error">{error}</p>{/if}
  {#if confirmOver}
    <p class="small">Start over with the contacts on this device?</p>
    <div class="pop-actions">
      <button type="button" class="btn text" onclick={() => (confirmOver = false)}>Cancel</button>
      <button type="button" class="btn text" disabled={busy} onclick={() => run(() => store.startOver())}>Start over</button>
    </div>
  {:else}
    <button type="button" class="btn text" onclick={() => (confirmOver = true)}>Forgot it? Start over</button>
  {/if}
</form>
