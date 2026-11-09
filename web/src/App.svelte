<script lang="ts">
  import { onMount } from 'svelte'
  import { errText, fmtTime, type Peer } from './lib/api'
  import { calls } from './lib/call.svelte'
  import { store } from './lib/store.svelte'
  import Login from './routes/Login.svelte'
  import Register from './routes/Register.svelte'
  import Home from './routes/Home.svelte'
  import Admin from './routes/Admin.svelte'
  import Account from './routes/Account.svelte'
  import CallOverlay from './routes/CallOverlay.svelte'
  import AddContact from './routes/AddContact.svelte'
  import NewGroup from './routes/NewGroup.svelte'
  import ChoosePassword from './routes/ChoosePassword.svelte'
  import Unlock from './lib/Unlock.svelte'
  import PasswordChange from './lib/PasswordChange.svelte'
  import { hostOf } from './lib/contacts'
  import Logo from './lib/Logo.svelte'
  import Icon from './lib/Icon.svelte'

  onMount(() => store.init())

  const invite = $derived(store.path.match(/^\/invite\/([\w-]+)/)?.[1] ?? null)
  const page = $derived(store.path === '/admin' ? 'admin' : store.path === '/account' ? 'account' : 'caw')
  const titles = { admin: 'Admin settings', account: 'Account' }

  let menuOpen = $state(false)
  let newOpen = $state(false)
  let groupOpen = $state(false)
  let query = $state('')
  let showAll = $state(false)
  const LIMIT = 50
  const list = $derived(store.contactList(query))
  const shown = $derived(showAll || query ? list : list.slice(0, LIMIT))
  const prompt = $derived(store.prompts[0] ?? null)
  const groupPrompt = $derived(store.groupPrompts[0] ?? null)
  const gone = $derived(store.unreachable !== null ? store.contact(store.unreachable) : undefined)

  function go(path: string) {
    store.navigate(path)
    menuOpen = false
  }

  async function quickCall(u: Peer) {
    store.open(u.id)
    try {
      await calls.call(u)
    } catch (e) {
      store.flash(`Call failed: ${errText(e)}`)
    }
  }

  function signOut() {
    menuOpen = false
    store.logout()
  }

  function onWindowClick(e: MouseEvent) {
    if (menuOpen && !(e.target as Element).closest('.user')) menuOpen = false
  }

  function onKey(e: KeyboardEvent) {
    if (e.key === 'Escape') {
      menuOpen = false
      newOpen = false
    }
  }
</script>

<svelte:window onclick={onWindowClick} onkeydown={onKey} />

{#if !store.ready}
  <div class="center muted">Loading...</div>
{:else if invite}
  <Register {invite} />
{:else if !store.me}
  {#if store.path === '/register' && store.config.registration_open}
    <Register />
  {:else}
    <Login />
  {/if}
{:else if store.me.must_change}
  <ChoosePassword />
{:else}
  <div class="shell" class:list-view={store.path === '/'}>
    <aside class="drawer">
      <div class="drawer-head">
        <Logo size="2.2rem" />
        <div class="grow">
          <strong>{store.config.server_name}</strong>
          <span class="small muted status">
            <i class="dot" class:on={store.connected}></i>{store.connected ? 'Connected' : 'Reconnecting...'}
          </span>
        </div>
      </div>

      <div class="contact-search">
        <Icon name="search" size={20} />
        <input type="search" placeholder="Search contacts" aria-label="Search contacts" bind:value={query} />
      </div>

      <div class="caws-wrap">
        <ul class="caws" aria-label="Contacts">
          {#each shown as id (id)}
            {@const name = store.userName(id)}
            {@const grp = store.isGroup(id)}
            {@const p = store.preview(id)}
            {@const unread = store.unread(id)}
            {@const last = store.messages[id]?.at(-1)}
            <li class="caw-row">
              <a href="/c/{id}" class="caw-item" class:active={store.selected === id && page === 'caw'} onclick={(e) => { e.preventDefault(); go(`/c/${id}`) }}>
                <span class="avatar">{#if grp}<Icon name="group" size={20} />{:else}{name.slice(0, 1).toUpperCase()}{/if}</span>
                <span class="grow">
                  <span class="caw-top">
                    <strong>{name}</strong>
                    {#if store.isFavorite(id)}<span class="fav-mark" title="Favorite"><Icon name="favorite" size={14} /></span>{/if}
                    {#if last}<span class="muted small">{fmtTime(last.created_at)}</span>{/if}
                  </span>
                  <span class="caw-bottom">
                    <span class="small preview" class:missed={p.missed} class:unread={unread}>{p.text}</span>
                    {#if unread}<span class="badge">{unread}</span>{/if}
                  </span>
                </span>
              </a>
              <span class="quick">
                <button class="icon-btn sm" aria-label="Message {name}" title="Message" onclick={() => { store.compose(id); menuOpen = false }}><Icon name="chat" size={18} /></button>
                {#if !grp}
                  <button class="icon-btn sm" aria-label="Call {name}" title="Call" disabled={!!calls.active || store.contact(id)?.status !== 'active'} onclick={() => quickCall(store.peer(id)!)}><Icon name="call" size={18} /></button>
                {/if}
              </span>
            </li>
          {:else}
            <li class="muted small pad">{query ? 'No matches.' : 'No contacts yet.'}</li>
          {/each}
          {#if !showAll && !query && list.length > LIMIT}
            <li><button class="btn text show-all" onclick={() => (showAll = true)}>Show all ({list.length})</button></li>
          {/if}
        </ul>
        <button class="btn tonal group-fab" onclick={() => (groupOpen = true)}><Icon name="group" size={20} />New group</button>
        <button class="btn filled add-fab" onclick={() => (newOpen = true)}><Icon name="person_add" size={20} />Add contact</button>
      </div>

      <div class="user">
        {#if menuOpen}
          <div class="menu" role="menu">
            <button role="menuitem" onclick={() => go('/account')}><Icon name="account" />Account</button>
            {#if store.me.is_admin}
              <button role="menuitem" onclick={() => go('/admin')}><Icon name="settings" />Admin settings</button>
            {/if}
            <hr />
            <button role="menuitem" onclick={signOut}><Icon name="logout" />Sign out</button>
          </div>
        {/if}
        <button class="user-btn" aria-haspopup="menu" aria-expanded={menuOpen} onclick={() => (menuOpen = !menuOpen)}>
          <span class="avatar">{store.me.display_name.slice(0, 1).toUpperCase()}</span>
          <span class="grow">
            <strong>{store.me.display_name}</strong>
            <span class="small muted">@{store.me.username}</span>
          </span>
          <span class="chev" class:flip={!menuOpen}><Icon name="expand_less" /></span>
        </button>
      </div>
    </aside>

    <div class="main">
      {#if store.deviceError}<div class="banner">{store.deviceError}</div>{/if}
      {#if store.vaultLocked}<Unlock />{/if}
      {#each store.newDevices as d (d.id)}
        <div class="banner info notice" role="status">
          <p>New device signed in: <strong>{d.label}</strong></p>
          <span>
            <button class="btn text" onclick={() => store.notMe(d.id).catch((e) => store.flash(errText(e)))}>That wasn't me</button>
            <button class="btn text" onclick={() => store.dismissDevice(d.id)}>OK</button>
          </span>
        </div>
      {/each}
      <CallOverlay />
      {#if page === 'caw'}
        <main class="caw-main"><Home onadd={() => (newOpen = true)} ongroup={() => (groupOpen = true)} /></main>
      {:else}
        <header class="appbar">
          <button class="icon-btn back" aria-label="Back" onclick={() => go('/')}><Icon name="arrow_back" /></button>
          <h1>{titles[page]}</h1>
        </header>
        <main>
          {#if page === 'admin' && store.me.is_admin}
            <Admin />
          {:else}
            <Account />
          {/if}
        </main>
      {/if}
    </div>
  </div>

  {#if newOpen}
    <AddContact onclose={() => (newOpen = false)} />
  {/if}
  {#if groupOpen}
    <NewGroup onclose={() => (groupOpen = false)} />
  {/if}

  {#if store.secureNow}
    <div class="backdrop">
      <div class="dialog prompt" role="dialog" aria-label="Change your password">
        <h2>Change your password</h2>
        <p class="muted">The device was removed. Change your password so it can't sign in again.</p>
        <PasswordChange rotate ondone={() => (store.secureNow = false)} />
        <button class="btn text" onclick={() => (store.secureNow = false)}>Later</button>
      </div>
    </div>
  {:else if prompt}
    <div class="backdrop">
      <div class="dialog prompt" role="dialog" aria-label="New contact">
        <h2>{prompt.card.name} wants to add you</h2>
        <p class="muted small">{hostOf(prompt.card.server)}</p>
        <p class="words">{prompt.words.join(' ')}</p>
        <p class="muted small">Check that {prompt.card.name} sees the same words.</p>
        <div class="pop-actions">
          <button class="btn text" onclick={() => store.declinePrompt(prompt)}>Decline</button>
          <button class="btn filled" onclick={() => store.acceptPrompt(prompt)}>Accept</button>
        </div>
      </div>
    </div>
  {:else if groupPrompt}
    <div class="backdrop">
      <div class="dialog prompt" role="dialog" aria-label="Group invitation">
        <h2>{groupPrompt.from} invited you to {groupPrompt.name}</h2>
        <p class="muted small">Members: {groupPrompt.members.join(', ')}</p>
        <p class="muted small">Members can see your name and server.</p>
        <div class="pop-actions">
          <button class="btn text" onclick={() => store.declineGroup(groupPrompt)}>Decline</button>
          <button class="btn filled" onclick={() => store.acceptGroup(groupPrompt).catch((e) => store.flash(errText(e)))}>Join</button>
        </div>
      </div>
    </div>
  {:else if gone}
    <div class="backdrop">
      <div class="dialog prompt" role="dialog" aria-label="Contact unreachable">
        <h2>Can't reach {store.userName(gone.num)}</h2>
        <p class="muted">Their server ({hostOf(gone.server)}) isn't answering. Messages will wait and be sent when it's back.</p>
        <div class="pop-actions">
          <button class="btn text" onclick={() => store.removeContact(gone.num)}>Remove contact</button>
          <button class="btn filled" onclick={() => store.keepTrying()}>Keep trying</button>
        </div>
      </div>
    </div>
  {/if}
{/if}

{#if store.toast}
  <div class="snackbar" role="status">{store.toast}</div>
{/if}
