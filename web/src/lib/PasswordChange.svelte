<script lang="ts">
  import { errText } from './api'
  import { PASSWORD_HINT, passwordProblem } from './password'
  import { store } from './store.svelte'

  let { rotate = false, ondone }: { rotate?: boolean; ondone?: () => void } = $props()

  let current = $state('')
  let next = $state('')
  let confirm = $state('')
  let error = $state('')
  let busy = $state(false)

  async function submit(e: SubmitEvent) {
    e.preventDefault()
    const problem = passwordProblem(next, store.me?.username)
    if (problem || next !== confirm) {
      error = problem ?? 'Passwords do not match.'
      return
    }
    busy = true
    error = ''
    try {
      await store.changePassword(current, next, rotate)
      current = next = confirm = ''
      store.flash('Password changed.')
      ondone?.()
    } catch (err) {
      error = errText(err)
    } finally {
      busy = false
    }
  }
</script>

<form class="stack" onsubmit={submit}>
  <label>Current password <input type="password" bind:value={current} autocomplete="current-password" required /></label>
  <label>New password <input type="password" bind:value={next} autocomplete="new-password" required /></label>
  <label>Confirm new password <input type="password" bind:value={confirm} autocomplete="new-password" required /></label>
  <p class="muted small">{PASSWORD_HINT}</p>
  {#if error}<p class="error">{error}</p>{/if}
  <button class="btn filled" disabled={busy}>{busy ? 'Saving...' : 'Change password'}</button>
</form>
