export const MIN_PASSWORD = 12

const COMMON = [
  'password', 'passw0rd', 'qwerty', 'qwertyuiop', 'asdfgh', 'asdfghjkl', 'zxcvbnm', 'letmein', 'welcome', 'iloveyou',
  'admin', 'monkey', 'dragon', 'football', 'baseball', 'soccer', 'hockey', 'sunshine', 'princess', 'trustno',
  'master', 'shadow', 'superman', 'batman', 'starwars', 'whatever', 'freedom', 'secret', 'changeme', 'login',
  'abc', 'abcdef', 'abcdefgh', 'caw', 'hello', 'charlie', 'michael', 'jordan', 'jesus', 'god', 'love', 'pokemon',
]

const SEQS = ['0123456789', 'abcdefghijklmnopqrstuvwxyz', 'qwertyuiopasdfghjklzxcvbnm']

// null when acceptable, otherwise the problem; a basic check, not a strength meter
export function passwordProblem(pw: string, username = ''): string | null {
  if ([...pw].length < MIN_PASSWORD) return `Use at least ${MIN_PASSWORD} characters.`
  const low = pw.toLowerCase()
  if (new Set(low).size < 5) return 'Too repetitive. A few random words work well.'
  const u = username.trim().toLowerCase()
  if (u.length >= 3 && low.includes(u)) return "Don't include your username."
  const core = low.replace(/[^a-z]/g, '')
  if (core && (COMMON.includes(core) || COMMON.some((w) => core === w.repeat(Math.round(core.length / w.length)))))
    return 'Too common. A few random words work well.'
  const alnum = low.replace(/[^a-z0-9]/g, '')
  if (alnum.length >= 6 && SEQS.some((s) => (s + s).includes(alnum) || [...s].reverse().join('').includes(alnum))) return 'Too easy to guess.'
  return null
}

export const PASSWORD_HINT = `At least ${MIN_PASSWORD} characters. A few random words work well.`
