import { test, expect, type Page } from '@playwright/test'

const reason = 'Google Calendar API가 비활성화되어 있어요. Google Cloud에서 API를 사용 설정해 주세요.'
async function mockSync(page: Page, status = 'ERROR', syncError: string | undefined = reason) {
  const state = { status, syncError, failRetry: true, retries: 0 }
  await page.route('**/api/**', async route => {
    const request = route.request(), path = new URL(request.url()).pathname
    if (path === '/api/session') return route.fulfill({ json: {
      publicBaseUrl: 'http://localhost:8085', csrfToken: 'test-csrf', csrfHeader: 'X-CSRF-TOKEN', demoMode: false,
      googleConfigured: true, geminiConfigured: true, user: { name: '테스트', email: 'sync-test@example.test', demo: false },
      calendarId: 'test-calendar', calendarName: '테스트 캘린더', syncStatus: state.status, syncError: state.syncError,
      lastSync: '2026-09-25T06:25:00Z',
    } })
    if (path === '/api/sync' && request.method() === 'POST') {
      expect(request.headers()['x-csrf-token']).toBe('test-csrf')
      state.retries++
      if (state.failRetry) return route.fulfill({ status: 503, json: { message: state.syncError } })
      state.status = 'SYNCED'; state.syncError = undefined
    }
    if (path === '/api/sync') return route.fulfill({ json: { issues: [] } })
    if (['/api/events', '/api/records', '/api/calendars'].includes(path)) return route.fulfill({ json: [] })
    throw new Error(`Unexpected request: ${path}`)
  })
  return state
}

test('a saved sync failure stays visible after refresh and clears after a successful retry', async ({ page }) => {
  const state = await mockSync(page)
  await page.goto('/')
  const banner = page.getByRole('alert', { name: 'Google Calendar 동기화 오류' })
  await expect(banner).toContainText(reason)
  await expect(page.getByRole('heading', { name: '일정의 동기화가 필요해요' })).toBeVisible()
  await expect(banner).toContainText('마지막 동기화 · 9월 25일 오후 3:25')
  await page.getByRole('button', { name: '다시 동기화', exact: true }).click()
  await expect(page.getByRole('button', { name: '다시 동기화', exact: true })).toBeEnabled()
  await expect(page.getByText(reason, { exact: true })).toHaveCount(1)
  expect(state.retries).toBe(1)
  await page.reload()
  await expect(banner).toContainText(reason)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.screenshot({ path: `test-results/sync-error-${test.info().project.name}.png`, fullPage: true })
  state.failRetry = false
  await page.getByRole('button', { name: '다시 동기화', exact: true }).click()
  await expect(banner).toHaveCount(0)
  await expect(page.getByRole('alert')).toHaveCount(0)
  await expect(page.getByRole('heading', { name: '아직 정해진 일정이 없어요' })).toBeVisible()
  expect(state.retries).toBe(2)
})

test('sync attention has a useful fallback when the server gives no reason', async ({ page }) => {
  const state = await mockSync(page, 'ATTENTION'); state.syncError = undefined
  await page.goto('/')
  const banner = page.getByRole('alert', { name: 'Google Calendar 동기화 오류' })
  await expect(banner).toContainText('일부 일정의 동기화를 확인해 주세요')
  await expect(banner).toContainText('아래 동기화 상태에서 변경 내용을 확인해 주세요')
  await expect(banner.getByRole('button', { name: '다시 동기화', exact: true })).toBeEnabled()
})

test('Google reauthorization on an IP address points to the server PC', async ({ page }) => {
  const state = await mockSync(page, 'REAUTH'); state.syncError = undefined
  await page.goto('http://127.0.0.1:8085/')
  const banner = page.getByRole('alert', { name: 'Google Calendar 동기화 오류' })
  await expect(banner).toContainText('서버 PC에서 http://localhost:8085을 열고')
  await expect(banner.getByRole('link', { name: 'Google 계정 다시 연결' })).toHaveCount(0)
  await page.goto('/')
  await expect(banner.getByRole('link', { name: 'Google 계정 다시 연결' })).toHaveAttribute('href', '/api/auth/google')
})

test('pending changes override the completed badge and refresh quickly until synced', async ({ page }) => {
  const state = { pending: true, reads: 0 }
  await page.clock.install({ time: new Date('2026-09-25T06:00:00Z') })
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    const event = { id: 'pending-event', version: 1, syncState: state.pending ? 'PENDING' : 'SYNCED', recurring: false, deleted: false,
      data: { title: '동기화 검증 일정', start: '2026-09-25T15:00:00+09:00', end: '2026-09-25T16:00:00+09:00', allDay: false, location: '', description: '' } }
    if (path === '/api/session') return route.fulfill({ json: {
      publicBaseUrl: 'http://localhost:8085', csrfToken: 'test-csrf', csrfHeader: 'X-CSRF-TOKEN', demoMode: false,
      googleConfigured: true, geminiConfigured: true, user: { name: '테스트', email: 'sync-test@example.test', demo: false },
      calendarId: 'test-calendar', calendarName: '테스트 캘린더', syncStatus: 'SYNCED',
    } })
    if (path === '/api/sync') { state.reads++; return route.fulfill({ json: { issues: state.pending ? [event] : [] } }) }
    if (path === '/api/events') return route.fulfill({ json: [event] })
    if (path === '/api/records') return route.fulfill({ json: [] })
    throw new Error(`Unexpected request: ${path}`)
  })
  await page.goto('/')
  await expect(page.locator('.date-pill')).toHaveText('동기화 대기')
  await expect(page.locator('.event-row .sync-label')).toHaveText('동기화 대기')
  const initialReads = state.reads
  state.pending = false
  await page.clock.runFor(2100)
  await expect(page.locator('.date-pill')).toHaveText('동기화 완료')
  await expect(page.locator('.event-row .sync-label')).toHaveText('동기화 완료')
  await expect(page.locator('.issues-section')).toHaveCount(0)
  expect(state.reads).toBeGreaterThan(initialReads)
  const completedReads = state.reads
  await page.clock.runFor(3000)
  expect(state.reads).toBe(completedReads)
  await page.clock.runFor(12_100)
  await expect.poll(() => state.reads).toBeGreaterThan(completedReads)
})
