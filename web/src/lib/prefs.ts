// per-browser flags, off by default
function flag(key: string): boolean {
  try {
    return localStorage.getItem(key) === 'on'
  } catch {
    return false
  }
}

function setFlag(key: string, on: boolean) {
  try {
    localStorage.setItem(key, on ? 'on' : 'off')
  } catch {
    // storage unavailable; stays off
  }
}

export const privateCalls = () => flag('caw.privateCalls')
export const setPrivateCalls = (on: boolean) => setFlag('caw.privateCalls', on)
export const privateMessages = () => flag('caw.privateMessages')
export const setPrivateMessages = (on: boolean) => setFlag('caw.privateMessages', on)
