<script lang="ts">
  import { onMount } from 'svelte'
  import { api, errText, fmtTime, type AdminUser, type Invite, type Settings } from '../lib/api'
  import { derive, newKdf } from '../lib/kdf'
  import { store } from '../lib/store.svelte'
  import Icon, { type IconName } from '../lib/Icon.svelte'

  let settings = $state<Settings | null>(null)
  let ttl = $state({ min: 5, def: 1440, max: 10080 })
  let users = $state<AdminUser[]>([])
  let invites = $state<Invite[]>([])

  let nu = $state({ username: '', display_name: '', password: '', is_admin: false })
  let ni = $state({ note: '', max_uses: 1, expires_in_hours: 168, is_admin: false })

  const stats = $derived<{ label: string; value: number | string; icon: IconName }[]>([
    { label: 'Users', value: users.filter((u) => !u.disabled).length, icon: 'group' },
    { label: 'Online now', value: users.filter((u) => u.online).length, icon: 'person' },
    { label: 'Active invites', value: invites.length, icon: 'link' },
    { label: 'Sign-up', value: settings?.registration_open ? 'Open' : 'Invite only', icon: 'settings' },
  ])

  onMount(load)

  async function load() {
    try {
      ;[settings, users, invites] = await Promise.all([
        api.get<Settings>('/api/admin/settings'),
        api.get<AdminUser[]>('/api/admin/users'),
        api.get<Invite[]>('/api/admin/invites'),
      ])
      ttl = { min: settings!.message_ttl_min / 60, def: settings!.message_ttl_default / 60, max: settings!.message_ttl_max / 60 }
    } catch (e) {
      store.flash(errText(e))
    }
  }

  async function run(fn: () => Promise<unknown>, ok?: string) {
    try {
      await fn()
      if (ok) store.flash(ok)
      await load()
    } catch (e) {
      store.flash(errText(e))
    }
  }

  function saveSettings(e: SubmitEvent) {
    e.preventDefault()
    run(async () => {
      settings = await api.patch<Settings>('/api/admin/settings', {
        ...settings,
        message_ttl_min: Math.round(ttl.min * 60),
        message_ttl_default: Math.round(ttl.def * 60),
        message_ttl_max: Math.round(ttl.max * 60),
      })
      store.config = {
        server_name: settings!.server_name,
        registration_open: settings!.registration_open,
        message_ttl_default: settings!.message_ttl_default,
        message_ttl_min: settings!.message_ttl_min,
        message_ttl_max: settings!.message_ttl_max,
        max_upload_mb: settings!.max_upload_mb,
      }
      document.title = settings!.server_name
    }, 'Settings saved')
  }

  // temporary password: the user sets their own at first sign-in, so the admin never holds one that unlocks data
  async function loginFor(password: string) {
    const kdf = newKdf()
    return { login_key: (await derive(password, kdf)).loginKey, salt: kdf.salt }
  }

  function createUser(e: SubmitEvent) {
    e.preventDefault()
    run(async () => {
      const { password, ...rest } = nu
      await api.post('/api/admin/users', { ...rest, ...(await loginFor(password)) })
      nu = { username: '', display_name: '', password: '', is_admin: false }
    }, 'User created. They choose their own password at first sign-in.')
  }

  function patchUser(u: AdminUser, body: object, ok?: string) {
    run(() => api.patch(`/api/admin/users/${u.id}`, body), ok)
  }

  function resetPassword(u: AdminUser) {
    const password = window.prompt(`Temporary password for ${u.username} (8+ characters). They choose their own at next sign-in.`)
    if (!password) return
    if (password.length < 8) return store.flash('Use at least 8 characters.')
    run(async () => api.patch(`/api/admin/users/${u.id}`, await loginFor(password)), 'Password reset')
  }

  function deleteUser(u: AdminUser) {
    if (window.confirm(`Delete ${u.username}? This cannot be undone.`)) {
      run(() => api.del(`/api/admin/users/${u.id}`), 'User deleted')
    }
  }

  function createInvite(e: SubmitEvent) {
    e.preventDefault()
    run(async () => {
      await api.post('/api/admin/invites', ni)
      ni = { note: '', max_uses: 1, expires_in_hours: 168, is_admin: false }
    }, 'Invite created')
  }

  async function copy(text: string) {
    try {
      await navigator.clipboard.writeText(text)
      store.flash('Link copied.')
    } catch {
      window.prompt('Copy link', text)
    }
  }
</script>

<div class="page wide">
  <div class="stats">
    {#each stats as s (s.label)}
      <div class="card stat">
        <span class="stat-icon"><Icon name={s.icon} /></span>
        <div>
          <div class="stat-value">{s.value}</div>
          <div class="muted small">{s.label}</div>
        </div>
      </div>
    {/each}
  </div>

  {#if settings}
    <section class="card">
      <h2>Settings</h2>
      <form class="stack" onsubmit={saveSettings}>
        <label>Server name <input bind:value={settings.server_name} maxlength="64" required /></label>
        <label>Ring timeout (seconds)
          <input type="number" min="10" max="300" bind:value={settings.ring_timeout_secs} required />
        </label>
        <fieldset class="ttl">
          <legend>Message expiry (minutes)</legend>
          <label>Minimum <input type="number" min="1" bind:value={ttl.min} required /></label>
          <label>Default <input type="number" min="1" bind:value={ttl.def} required /></label>
          <label>Maximum <input type="number" min="1" max="43200" bind:value={ttl.max} required /></label>
        </fieldset>
        <label>Device expiry (days)
          <input type="number" min="7" max="365" bind:value={settings.device_expiry_days} required />
        </label>
        <label>Pairing code expiry (minutes)
          <input type="number" min="1" max="1440" bind:value={settings.pairing_code_minutes} required />
        </label>
        <label>Attachment limit (MB)
          <input type="number" min="1" max="500" bind:value={settings.max_upload_mb} required />
        </label>
        <label class="switch">
          <input type="checkbox" role="switch" bind:checked={settings.registration_open} />
          <span>Allow public registration</span>
        </label>
        <div><button class="btn filled">Save settings</button></div>
      </form>
    </section>
  {/if}

  <section class="card">
    <h2>Invites</h2>
    <p class="muted small">Invite links work even when public registration is off.</p>
    <form class="inline" onsubmit={createInvite}>
      <label class="wide-field">Note <input placeholder="For" bind:value={ni.note} maxlength="200" /></label>
      <label>Uses <input type="number" min="0" bind:value={ni.max_uses} title="0 = unlimited" /></label>
      <label>Expires (h) <input type="number" min="0" bind:value={ni.expires_in_hours} title="0 = never" /></label>
      <label class="check"><input type="checkbox" bind:checked={ni.is_admin} /> Admin</label>
      <button class="btn filled"><Icon name="add" size={20} />Create invite</button>
    </form>
    <div class="table-wrap">
      <table>
        <thead><tr><th>Note</th><th>Uses</th><th>Expires</th><th></th></tr></thead>
        <tbody>
          {#each invites as i (i.id)}
            <tr>
              <td>{i.note || '-'}{#if i.is_admin} <span class="chip">admin</span>{/if}</td>
              <td>{i.uses} / {i.max_uses ?? '\u221e'}</td>
              <td>{i.expires_at ? fmtTime(i.expires_at) : 'never'}</td>
              <td class="actions">
                <button class="btn text" onclick={() => copy(i.url)}><Icon name="copy" size={18} />Copy link</button>
                <button class="icon-btn" aria-label="Revoke invite" title="Revoke" onclick={() => run(() => api.del(`/api/admin/invites/${i.id}`), 'Invite revoked')}>
                  <Icon name="delete" size={20} />
                </button>
              </td>
            </tr>
          {:else}
            <tr><td colspan="4" class="muted">No active invites.</td></tr>
          {/each}
        </tbody>
      </table>
    </div>
  </section>

  <section class="card">
    <h2>Users</h2>
    <form class="inline" onsubmit={createUser}>
      <label>Username <input bind:value={nu.username} minlength="3" maxlength="32" required /></label>
      <label>Display name <input bind:value={nu.display_name} maxlength="64" /></label>
      <label>Temporary password <input type="password" bind:value={nu.password} minlength="8" autocomplete="new-password" required /></label>
      <label class="check"><input type="checkbox" bind:checked={nu.is_admin} /> Admin</label>
      <button class="btn filled"><Icon name="add" size={20} />Add user</button>
    </form>
    <div class="table-wrap">
      <table>
        <thead><tr><th>User</th><th>Last seen</th><th>Role</th><th></th></tr></thead>
        <tbody>
          {#each users as u (u.id)}
            <tr class:dim={u.disabled}>
              <td>
                <div class="user-cell">
                  <span class="avatar sm">{u.display_name.slice(0, 1).toUpperCase()}<i class="presence" class:on={u.online}></i></span>
                  <span>
                    <strong>{u.display_name}</strong> <span class="muted">@{u.username}</span>
                    {#if u.disabled}<span class="chip">disabled</span>{/if}
                  </span>
                </div>
              </td>
              <td>{u.online ? 'now' : u.last_seen ? fmtTime(u.last_seen) : 'never'}</td>
              <td>{#if u.is_admin}<span class="chip primary">Admin</span>{:else}User{/if}</td>
              <td class="actions">
                {#if u.id !== store.me?.id}
                  <button class="btn text" onclick={() => patchUser(u, { is_admin: !u.is_admin })}>
                    {u.is_admin ? 'Make user' : 'Make admin'}
                  </button>
                  <button class="btn text" onclick={() => patchUser(u, { disabled: !u.disabled })}>
                    {u.disabled ? 'Enable' : 'Disable'}
                  </button>
                {/if}
                <button class="btn text" onclick={() => resetPassword(u)}>Reset password</button>
                {#if u.id !== store.me?.id}
                  <button class="icon-btn" aria-label="Delete {u.username}" title="Delete" onclick={() => deleteUser(u)}>
                    <Icon name="delete" size={20} />
                  </button>
                {/if}
              </td>
            </tr>
          {/each}
        </tbody>
      </table>
    </div>
  </section>
</div>
