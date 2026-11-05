<script lang="ts">
  import { errText } from '../lib/api'
  import { store } from '../lib/store.svelte'
  import Logo from '../lib/Logo.svelte'

  function lastUser() {
    try {
      return JSON.parse(localStorage.getItem('caw.username') ?? '""') as string
    } catch {
      return ''
    }
  }

  let username = $state(lastUser())
  let password = $state('')
  let error = $state('')
  let busy = $state(false)

  async function submit(e: SubmitEvent) {
    e.preventDefault()
    busy = true
    error = ''
    try {
      await store.signIn(username.trim(), password)
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
    <h1>{store.config.server_name}</h1>
    <label>Username <input bind:value={username} autocomplete="username" required /></label>
    <label>Password <input type="password" bind:value={password} autocomplete="current-password" required /></label>
    {#if error}<p class="error">{error}</p>{/if}
    <button class="btn filled" disabled={busy}>{busy ? 'Signing in...' : 'Sign in'}</button>
    {#if store.config.registration_open}
      <p class="muted small">
        No account? <a href="/register" onclick={(e) => { e.preventDefault(); store.navigate('/register') }}>Register</a>
      </p>
    {/if}
  </form>
</div>
