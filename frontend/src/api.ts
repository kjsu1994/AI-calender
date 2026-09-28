export type EventData = { title: string; start: string; end: string; allDay: boolean; description: string; location: string; deleted?: boolean }
export type CalendarEvent = { id: string; data: EventData; remote?: EventData; syncState: string; version: number; deleted: boolean; recurring: boolean }
export type ActivityRecord = { id: string; eventId?: string; eventTitle?: string; data: EventData; version: number; createdAt: string; updatedAt: string; deleted?: boolean }
export type RecordInput = { title: string; start: string; end: string; content: string; eventId: string }
export type Session = { csrfToken: string; csrfHeader: string; publicBaseUrl: string; demoMode: boolean; geminiConfigured: boolean; googleConfigured: boolean; passwordLoginConfigured?: boolean; googleConnected?: boolean; remembered?: boolean; user?: { id?: string; name: string; email: string; demo: boolean }; calendarId?: string; calendarName?: string; syncStatus?: string; syncError?: string; lastSync?: string }
export type Command = { commandId: string; status: string; message: string; transcript?: string; before?: EventData; after?: EventData; events?: CalendarEvent[]; event?: CalendarEvent; record?: ActivityRecord; records?: ActivityRecord[]; kind?: string }
let csrf = { token: '', header: 'X-CSRF-TOKEN' }
let sessionRequest: Promise<unknown> | null = null
let sessionGeneration = 0
export async function api<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
  // StrictMode and polling may request a session concurrently; share the cookie/token response.
  if (path === '/session' && method === 'GET') {
    if (!sessionRequest) {
      const generation = sessionGeneration
      sessionRequest = request<T>(path, method, body).finally(() => { if (generation === sessionGeneration) sessionRequest = null })
    }
    return sessionRequest as Promise<T>
  }
  return request<T>(path, method, body)
}
async function request<T>(path: string, method: string, body?: unknown): Promise<T> {
  const generation = sessionGeneration
  const form = body instanceof FormData
  const headers: Record<string, string> = {}
  if (body && !form) headers['Content-Type'] = 'application/json'
  if (method !== 'GET') headers[csrf.header] = csrf.token
  const options: RequestInit = { method, credentials: 'same-origin', headers, body: body ? form ? body : JSON.stringify(body) : undefined }
  // On a lost connection resend the exact request (including the same requestId).
  const response = await fetch('/api' + path, options).catch(() => fetch('/api' + path, options))
  const data = await response.json().catch(() => ({}))
  if (!response.ok) throw new Error(data.message || (response.status === 403 ? '보안 세션이 만료되었습니다. 페이지를 새로고침해 주세요.' : '요청을 처리하지 못했습니다. 다시 시도해 주세요.'))
  if (method === 'POST' && ['/auth/password', '/auth/logout', '/auth/demo', '/auth/device-link'].includes(path)) {
    // Login rotates the session. An older poll must not supply the next CSRF token.
    sessionGeneration++; sessionRequest = null
  }
  if (path === '/session' && generation === sessionGeneration) csrf = { token: data.csrfToken, header: data.csrfHeader }
  return data
}
export function requestId() {
  // randomUUID is unavailable on a mobile HTTP origin; getRandomValues is available.
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  return Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('')
}
export function dayKey(date: Date) { return new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit' }).format(date) }
export function dateFromKey(key: string) { return new Date(key + 'T12:00:00+09:00') }
export function moveDay(key: string, delta: number) { const d = dateFromKey(key); d.setUTCDate(d.getUTCDate() + delta); return dayKey(d) }
export function timeLabel(data: EventData) {
  if (data.deleted) return '삭제됨'
  if (data.allDay) return `${data.start} · 종일`
  return new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', month: 'long', day: 'numeric', hour: 'numeric', minute: '2-digit' }).format(new Date(data.start))
}
export function onDay(event: { data: EventData }, day: string) {
  const a = new Date(day + 'T00:00:00+09:00').getTime(), b = a + 86400000
  const start = new Date(event.data.start + (event.data.allDay ? 'T00:00:00+09:00' : '')).getTime()
  const end = new Date(event.data.end + (event.data.allDay ? 'T00:00:00+09:00' : '')).getTime()
  return start < b && end > a
}
