import { test, expect, type Page } from '@playwright/test'

const publicBaseUrl = 'http://localhost:8085'
const user = { name: '테스트', email: 'device-test@example.test', demo: false }

async function mockApp(page: Page, signedIn = false) {
  const state = { signedIn, rejectCode: false, linkedCode: '', issued: 0, publicBaseUrl }
  await page.route('**/api/**', async route => {
    const request = route.request(), path = new URL(request.url()).pathname
    let body: unknown = {}, status = 200
    if (path === '/api/session') body = { publicBaseUrl: state.publicBaseUrl, csrfToken: 'test-csrf', csrfHeader: 'X-CSRF-TOKEN', demoMode: false, googleConfigured: true, geminiConfigured: true, ...(state.signedIn ? { user, calendarId: 'test-calendar', calendarName: '테스트 캘린더', syncStatus: 'SYNCED' } : {}) }
    else if (path === '/api/auth/device-link') {
      expect(request.headers()['x-csrf-token']).toBe('test-csrf')
      state.linkedCode = request.postDataJSON().code
      if (state.rejectCode) { status = 400; body = { message: '만료되었거나 이미 사용한 연결 코드입니다.' } }
      else { state.signedIn = true; body = { ok: true } }
    }
    else if (path === '/api/auth/device-code') { state.issued++; body = { code: 'ABCD-EFGH-JKLM', expiresAt: '2026-09-25T06:05:00Z' } }
    else if (path === '/api/auth/logout') { state.signedIn = false; body = { ok: true } }
    else if (path === '/api/sync') body = { issues: [] }
    else if (['/api/events', '/api/records', '/api/calendars'].includes(path)) body = []
    else throw new Error(`Unexpected request: ${path}`)
    await route.fulfill({ status, json: body })
  })
  return state
}

test('IP access links an existing account and explains the mobile HTTP microphone limit', async ({ page }) => {
  const state = await mockApp(page)
  await page.addInitScript(() => Object.defineProperty(window, 'isSecureContext', { value: false }))
  await page.goto('http://127.0.0.1:8085/')
  await expect(page.getByRole('heading', { name: 'PC에서 연결 코드 발급' })).toBeVisible()
  await expect(page.getByRole('link', { name: 'Google 계정으로 시작' })).toHaveCount(0)
  await expect(page.getByRole('note')).toContainText('HTTPS 연결이 필요해요')
  await expect(page.getByRole('button', { name: '음성 입력 시작' })).toBeDisabled()
  await page.getByLabel('연결 코드', { exact: true }).fill('abcd efgh jklm')
  await page.getByRole('button', { name: '이 기기 연결', exact: true }).click()
  await expect(page.getByRole('textbox', { name: '일정 요청' })).toBeEnabled()
  expect(state.linkedCode).toBe('ABCD-EFGH-JKLM')
  await page.getByRole('button', { name: '계정 설정', exact: true }).click()
  await expect(page.getByRole('link', { name: 'Google 계정 다시 연결' })).toHaveCount(0)
  await expect(page.getByText('Google 계정을 다시 연결하려면')).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
})

test('a rejected connection code stays editable without signing in', async ({ page }) => {
  const state = await mockApp(page); state.rejectCode = true
  await page.goto('http://127.0.0.1:8085/')
  await page.getByLabel('연결 코드', { exact: true }).fill('ABCD-EFGH-JKLM')
  await page.getByRole('button', { name: '이 기기 연결', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('만료되었거나 이미 사용한')
  await expect(page.getByLabel('연결 코드', { exact: true })).toBeEnabled()
  await expect(page.getByRole('textbox', { name: '일정 요청' })).toBeDisabled()
})

test('canonical Google sign-in remains available and issued codes expire and disappear on logout', async ({ page }) => {
  const state = await mockApp(page)
  await page.clock.install({ time: new Date('2026-09-25T06:00:00Z') })
  await page.goto('/')
  await expect(page.getByRole('link', { name: 'Google 계정으로 시작' })).toHaveAttribute('href', '/api/auth/google')
  state.signedIn = true
  await page.reload()
  await page.getByRole('button', { name: '계정 설정', exact: true }).click()
  await page.getByRole('button', { name: '연결 코드 생성', exact: true }).click()
  await expect(page.getByLabel('발급한 연결 코드')).toHaveText('ABCD-EFGH-JKLM')
  expect(state.issued).toBe(1)
  expect(await page.evaluate(() => JSON.stringify(localStorage))).not.toContain('ABCD')
  await page.clock.fastForward(301_000)
  await expect(page.getByText('코드가 만료되었습니다. 새 코드를 만들어 주세요.')).toBeVisible()
  await expect(page.getByLabel('발급한 연결 코드')).toHaveCount(0)
  await page.getByRole('button', { name: '로그아웃', exact: true }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(page.getByRole('link', { name: 'Google 계정으로 시작' })).toBeVisible()
})

test('localhost and IP visitors use the configured public HTTPS address instead of a device code', async ({ page }) => {
  const state = await mockApp(page); state.publicBaseUrl = 'https://ctfmon.shop'
  for (const address of ['http://localhost:8085', 'http://127.0.0.1:8085']) {
    await page.goto(address)
    await expect(page.getByRole('link', { name: 'HTTPS 주소로 접속' })).toHaveAttribute('href', 'https://ctfmon.shop')
    await expect(page.getByText('이 주소로 접속하면 Google 로그인과 음성 입력을 사용할 수 있어요.')).toBeVisible()
    await expect(page.getByLabel('연결 코드', { exact: true })).toHaveCount(0)
    await expect(page.getByRole('link', { name: 'Google 계정으로 시작' })).toHaveCount(0)
  }
  state.signedIn = true
  await page.reload()
  await page.getByRole('button', { name: '계정 설정', exact: true }).click()
  await expect(page.getByRole('button', { name: '연결 코드 생성', exact: true })).toHaveCount(0)
  await expect(page.getByRole('link', { name: 'HTTPS 주소로 접속' })).toHaveAttribute('href', 'https://ctfmon.shop')
})

test('the configured HTTPS domain retains direct Google sign-in', async ({ page }) => {
  const state = await mockApp(page); state.publicBaseUrl = 'https://ctfmon.shop'
  // Serve this origin from the local dev server; no request reaches the public domain.
  await page.routeWebSocket(/wss:\/\/ctfmon\.shop/, socket => socket.close())
  await page.route('https://ctfmon.shop/**', async route => {
    const url = new URL(route.request().url())
    if (url.pathname.startsWith('/api/')) return route.fallback()
    const response = await page.request.get('http://localhost:8085' + url.pathname + url.search)
    await route.fulfill({ response })
  })
  await page.goto(state.publicBaseUrl)
  await expect(page.getByRole('link', { name: 'Google 계정으로 시작' })).toHaveAttribute('href', '/api/auth/google')
  await expect(page.getByRole('link', { name: 'HTTPS 주소로 접속' })).toHaveCount(0)
  await expect(page.getByLabel('연결 코드', { exact: true })).toHaveCount(0)
})
