<script lang="ts">
  import { errText } from '../lib/api'
  import { PASSWORD_HINT, passwordProblem } from '../lib/password'
  import { store } from '../lib/store.svelte'
  import Logo from '../lib/Logo.svelte'

  let password = $state('')
  let confirm = $state('')
  let error = $state('')
  let busy = $state(false)

  async function submit(e: SubmitEvent) {
    e.preventDefault()
    const problem = passwordProblem(password, store.me?.username)
    if (problem || password !== confirm) {
      error = problem ?? 'Passwords do not match.'
      return
    }
    busy = true
    error = ''
    try {
      await store.choosePassword(password)
    } catch (err) {
      error = errText(err)
    } finally {
      busy = false
    }
  }
</script>

<div class="center">
  <form class="card auth" onsubmit={submit}>
    <div class="logo"><Logo size="5rem" /></div>
    <h1>Choose a password</h1>
    <p class="muted">Your account was set up with a temporary password. Choose your own to continue.</p>
    <label>New password <input type="password" bind:value={password} autocomplete="new-password" required /></label>
    <label>Confirm password <input type="password" bind:value={confirm} autocomplete="new-password" required /></label>
    <p class="muted small">{PASSWORD_HINT}</p>
    {#if error}<p class="error">{error}</p>{/if}
    <button class="btn filled" disabled={busy}>{busy ? 'Saving...' : 'Continue'}</button>
    <p class="muted small"><a href="/" onclick={(e) => { e.preventDefault(); store.logout() }}>Sign out</a></p>
  </form>
</div>
