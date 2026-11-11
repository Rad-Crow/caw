<script lang="ts">
  import { errText } from '../lib/api'
  import { store } from '../lib/store.svelte'
  import Icon from '../lib/Icon.svelte'

  let { group, onclose }: { group?: number; onclose: () => void } = $props()

  const SHOWN = 50
  let name = $state('')
  let query = $state('')
  let picked = $state<number[]>([])
  let busy = $state(false)
  let failed = $state<string | null>(null)
  const adding = $derived(group !== undefined)
  const pool = $derived(new Set(group !== undefined ? store.invitable(group) : store.contacts.filter((c) => c.status === 'active').map((c) => c.num)))
  const matches = $derived(store.contactList(query).filter((num) => pool.has(num)))
  const shown = $derived(matches.slice(0, SHOWN))
  const room = $derived(store.groupRoom(group))
  const full = $derived(picked.length >= room)

  function toggle(num: number) {
    if (picked.includes(num)) picked = picked.filter((x) => x !== num)
    else if (!full) picked = [...picked, num]
  }

  async function submit(e: SubmitEvent) {
    e.preventDefault()
    busy = true
    failed = null
    try {
      if (group !== undefined) await store.inviteToGroup(group, picked)
      else store.open(await store.createGroup(name, picked))
      onclose()
    } catch (err) {
      failed = errText(err)
    } finally {
      busy = false
    }
  }
</script>

<div class="backdrop">
  <button class="backdrop-hit" aria-label="Close" onclick={onclose}></button>
  <div class="dialog new-group" role="dialog" aria-modal="true" aria-label={adding ? 'Add members' : 'New group'}>
    <div class="dialog-head">
      <h2>{adding ? 'Add members' : 'New group'}</h2>
      <button class="icon-btn" aria-label="Close" onclick={onclose}><Icon name="close" /></button>
    </div>
    <form class="stack pad" onsubmit={submit}>
      {#if !adding}
        <label>Group name <input bind:value={name} maxlength="64" required /></label>
      {/if}
      {#if picked.length}
        <div class="chips" aria-label="Selected contacts">
          {#each picked as num (num)}
            <button type="button" class="chip" aria-label="Remove {store.userName(num)}" onclick={() => toggle(num)}>{store.userName(num)}<Icon name="close" size={16} /></button>
          {/each}
        </div>
      {/if}
      <div class="contact-search">
        <Icon name="search" size={20} />
        <input type="search" placeholder="Search contacts" aria-label="Search contacts" bind:value={query} />
      </div>
      <ul class="pick-list" aria-label="Contacts">
        {#each shown as num (num)}
          {@const on = picked.includes(num)}
          <li>
            <button type="button" class="pick-row" class:on aria-pressed={on} disabled={!on && full} onclick={() => toggle(num)}>
              <span class="avatar">{store.userName(num).slice(0, 1).toUpperCase()}</span>
              <span class="grow">
                <span>{store.userName(num)}</span>
                <span class="muted small">{store.peer(num)?.host}</span>
              </span>
              <Icon name={on ? 'check_circle' : 'circle'} size={22} />
            </button>
          </li>
        {:else}
          <li class="muted small pad">{query ? 'No matches.' : 'No contacts to invite.'}</li>
        {/each}
        {#if matches.length > shown.length}
          <li class="muted small pad">Search to find more contacts.</li>
        {/if}
      </ul>
      <p class="muted small">
        {full ? `Groups on this server can have up to ${store.config.max_group_members} members.` : "Contacts join after they accept. Members can see each other's names and servers."}
      </p>
      {#if failed}<p class="error">{failed}</p>{/if}
      <div>
        <button class="btn filled" disabled={busy || (adding && !picked.length)}>{busy ? 'Saving...' : adding ? 'Invite' : 'Create group'}</button>
      </div>
    </form>
  </div>
</div>
