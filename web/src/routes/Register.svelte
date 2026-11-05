<script lang="ts">
  import { onMount } from 'svelte'
  import { api, errText } from '../lib/api'
  import { PASSWORD_HINT, passwordProblem } from '../lib/password'
  import { store } from '../lib/store.svelte'
  import Logo from '../lib/Logo.svelte'

  let { invite = null }: { invite?: string | null } = $props()

  let username = $state('')
  let display_name = $state('')
  let password = $state('')
  let confirm = $state('')
  let error = $state('')
  let busy = $state(false)
  let inviteOk = $state<boolean | null>(null)

  onMount(async () => {
    if (!invite) return
    try {
      await api.get(`/api/invites/${invite}`)
      inviteOk = true
    } catch {
      inviteOk = false
    }
  })

  async function submit(e: SubmitEvent) {
    e.preventDefault()
    const problem = passwordProblem(password, username)
    if (problem || password !== confirm) {
      error = problem ?? 'Passwords do not match.'
      return
    }
    busy = true
    error = ''
    try {
      await store.register({ username: username.trim(), display_name, invite }, password)
    } catch (err) {
      error = errText(err)
    } finally {
      busy = false
    }
  }

  function toLogin(e: MouseEvent) {
    e.preventDefault()
    store.navigate('/')
  }
</script>

<div class="center">
  {#if invite && inviteOk === false}
    <div class="card auth">
      <h1>Invalid invitation</h1>
      <p class="muted">This link has expired or already been used.</p>
      <a href="/" onclick={toLogin}>Sign in</a>
    </div>
  {:else}
    <form class="card auth" onsubmit={submit}>
      <div class="logo"><Logo size="5rem" /></div>
      <h1>Join {store.config.server_name}</h1>
      <label>Username <input bind:value={username} autocomplete="username" minlength="3" maxlength="32" required /></label>
      <label>Display name <input bind:value={display_name} maxlength="64" placeholder="optional" /></label>
      <label>Password <input type="password" bind:value={password} autocomplete="new-password" required /></label>
      <p class="muted small">{PASSWORD_HINT}</p>
      <label>Confirm password <input type="password" bind:value={confirm} autocomplete="new-password" required /></label>
      {#if error}<p class="error">{error}</p>{/if}
      <button class="btn filled" disabled={busy || (!!invite && inviteOk === null)}>{busy ? 'Creating account...' : 'Create account'}</button>
      <p class="muted small">Have an account? <a href="/" onclick={toLogin}>Sign in</a></p>
    </form>
  {/if}
</div>
