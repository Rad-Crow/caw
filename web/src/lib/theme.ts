export type Theme = 'system' | 'light' | 'dark'

const KEY = 'caw.theme'
const media = matchMedia('(prefers-color-scheme: dark)')

export function getTheme(): Theme {
  try {
    const t = localStorage.getItem(KEY)
    return t === 'light' || t === 'dark' ? t : 'system'
  } catch {
    return 'system'
  }
}

function apply() {
  const t = getTheme()
  document.documentElement.dataset.theme = t === 'system' ? (media.matches ? 'dark' : 'light') : t
}

export function setTheme(t: Theme) {
  try {
    if (t === 'system') localStorage.removeItem(KEY)
    else localStorage.setItem(KEY, t)
  } catch {
    // storage unavailable; applies until reload
  }
  apply()
}

media.addEventListener('change', apply)
apply()
