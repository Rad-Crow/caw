<script lang="ts">
  import { onMount } from 'svelte'
  import { api, errText, fmtTime, type Me } from '../lib/api'
  import { denoiseOn, setDenoise } from '../lib/denoise'
  import type { OwnDevice } from '../lib/device'
  import PasswordChange from '../lib/PasswordChange.svelte'
  import { privateCalls, privateMessages, setPrivateCalls, setPrivateMessages } from '../lib/prefs'
  import { store } from '../lib/store.svelte'
  import { getTheme, setTheme, type Theme } from '../lib/theme'
  import { getSoundTheme, setSoundTheme, tones, type SoundTheme } from '../lib/tones'
  import Icon from '../lib/Icon.svelte'

  onMount(() => store.refreshDevices().catch((e) => store.flash(errText(e))))

  async function removeDevice(d: OwnDevice) {
    const self = d.id === store.device?.id
    const msg = self
      ? 'Remove this device? Its messages will no longer be readable here.'
      : `Remove ${d.label || 'this device'}?`
    if (!window.confirm(msg)) return
    try {
      await store.removeDevice(d.id)
    } catch (e) {
      store.flash(errText(e))
    }
  }

  let display_name = $state(store.me?.display_name ?? '')
  let denoise = $state(denoiseOn())
  let theme = $state<Theme>(getTheme())
  let sounds = $state<SoundTheme>(getSoundTheme())
  let privCalls = $state(privateCalls())
  let privMessages = $state(privateMessages())
  let notifyState = $state('Notification' in window ? Notification.permission : 'unsupported')

  async function saveName(e: SubmitEvent) {
    e.preventDefault()
    try {
      store.me = await api.patch<Me>('/api/me', { display_name })
      store.flash('Saved')
    } catch (err) {
      store.flash(errText(err))
    }
  }

  async function enableNotifications() {
    notifyState = await Notification.requestPermission()
  }

  function chooseSounds(t: SoundTheme) {
    setSoundTheme((sounds = t))
    tones.message()
  }
</script>

<div class="page">
  <section class="card">
    <h2>Profile</h2>
    <form class="stack" onsubmit={saveName}>
      <label>Username <input value={store.me?.username} disabled /></label>
      <label>Display name <input bind:value={display_name} maxlength="64" required /></label>
      <button class="btn filled">Save</button>
    </form>
  </section>

  <section class="card">
    <h2>Password</h2>
    <PasswordChange />
  </section>

  <section class="card">
    <h2>Devices</h2>
    <p class="muted small">As reported by this server. Messages are encrypted for each of these devices.</p>
    <ul class="device-list">
      {#each store.ownDevices as d (d.id)}
        <li>
          <Icon name="devices" />
          <span class="grow">
            <strong>{d.label || 'Device'}{#if d.id === store.device?.id} <span class="chip primary">this device</span>{/if}</strong>
            <span class="muted small">Last seen {fmtTime(d.last_seen)}</span>
          </span>
          <button class="btn text" onclick={() => removeDevice(d)}>Remove</button>
        </li>
      {:else}
        <li class="muted">No devices.</li>
      {/each}
    </ul>
  </section>

  <section class="card">
    <h2>Calls</h2>
    <label class="switch">
      <input type="checkbox" role="switch" bind:checked={denoise} onchange={() => setDenoise(denoise)} />
      <span>Noise suppression</span>
    </label>
    <p class="muted small">Reduces background noise from your microphone on this device. Applies to the next call.</p>
  </section>

  <section class="card">
    <h2>Notifications</h2>
    {#if notifyState === 'granted'}
      <p class="muted">On while this tab is open.</p>
    {:else if notifyState === 'denied'}
      <p class="muted">Blocked in browser settings.</p>
    {:else if notifyState === 'unsupported'}
      <p class="muted">Not supported in this browser.</p>
    {:else}
      <button class="btn tonal" onclick={enableNotifications}>Enable desktop notifications</button>
    {/if}
  </section>

  <section class="card">
    <h2>Theme</h2>
    <div class="stack">
      <div class="field">
        <span class="muted small" id="theme-appearance">Appearance</span>
        <div class="segmented" role="radiogroup" aria-labelledby="theme-appearance">
          {#each [['system', 'System'], ['light', 'Light'], ['dark', 'Dark']] as [value, label] (value)}
            <button
              role="radio"
              aria-checked={theme === value}
              class:on={theme === value}
              onclick={() => setTheme((theme = value as Theme))}>{label}</button
            >
          {/each}
        </div>
      </div>
      <div class="field">
        <span class="muted small" id="theme-audio">Audio</span>
        <div class="segmented" role="radiogroup" aria-labelledby="theme-audio">
          {#each [['classic', 'Classic'], ['crow', 'Crow']] as [value, label] (value)}
            <button
              role="radio"
              aria-checked={sounds === value}
              class:on={sounds === value}
              onclick={() => chooseSounds(value as SoundTheme)}>{label}</button
            >
          {/each}
        </div>
      </div>
    </div>
  </section>

  <section class="card">
    <h2>Private notifications</h2>
    <div class="stack">
      <div>
        <label class="switch">
          <input type="checkbox" role="switch" bind:checked={privCalls} onchange={() => setPrivateCalls(privCalls)} />
          <span>Calls</span>
        </label>
        <p class="muted small">Hide who's calling.</p>
      </div>
      <div>
        <label class="switch">
          <input type="checkbox" role="switch" bind:checked={privMessages} onchange={() => setPrivateMessages(privMessages)} />
          <span>Messages</span>
        </label>
        <p class="muted small">Hide who sent a message and what it says.</p>
      </div>
    </div>
  </section>
</div>
