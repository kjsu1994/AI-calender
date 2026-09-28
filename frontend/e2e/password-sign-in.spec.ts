import { test, expect, type Page } from '@playwright/test'

const testUsername = 'browser-test-owner'
const testPassword = 'BrowserOnly-Test!47'
async function mockPasswordApp(page: Page) {
  const state = { signedIn: false, googleConnected: true, reject: false, remembered: false, generation: 0, calendarRequests: 0,
    publicBaseUrl: 'http://localhost:8085', loginBody: null as null | { username: string; password: string; rememberMe: boolean } }
  await page.route('**/api/**', async route => {
    const request = route.request(), path = new URL(request.url()).pathname
    if (path === '/api/session') return route.fulfill({ json: {
      publicBaseUrl: state.publicBaseUrl, csrfToken: `test-csrf-${state.generation}`, csrfHeader: 'X-CSRF-TOKEN',
      demoMode: false, googleConfigured: true, geminiConfigured: true, passwordLoginConfigured: true,
      googleConnected: state.signedIn && state.googleConnected, remembered: state.remembered,
      ...(state.signedIn ? { user: { name: '테스트', email: 'password-test@example.test', demo: false },
        ...(state.googleConnected ? { calendarId: 'test-calendar', calendarName: '테스트 캘린더', syncStatus: 'SYNCED' } : { syncStatus: 'DISCONNECTED' }) } : {}),
    } })
    if (path === '/api/auth/password') {
      expect(request.headers()['x-csrf-token']).toBe(`test-csrf-${state.generation}`)
      state.loginBody = request.postDataJSON()
      if (state.reject) return route.fulfill({ status: 401, json: { message: '아이디 또는 비밀번호가 올바르지 않습니다.' } })
      state.signedIn = true; state.remembered = state.loginBody!.rememberMe; state.generation++
      return route.fulfill({ json: { ok: true } })
    }
    if (path === '/api/auth/logout') {
      expect(request.headers()['x-csrf-token']).toBe(`test-csrf-${state.generation}`)
      state.signedIn = false; state.remembered = false; state.generation++
      return route.fulfill({ json: { ok: true } })
    }
    if (path === '/api/sync') return route.fulfill({ json: { issues: [] } })
    if (path === '/api/calendars') state.calendarRequests++
    if (['/api/events', '/api/records', '/api/calendars'].includes(path)) return route.fulfill({ json: [] })
    throw new Error(`Unexpected request: ${path}`)
  })
  return state
}

async function fillCredentials(page: Page) {
  await page.getByLabel('아이디', { exact: true }).fill(testUsername)
  await page.getByLabel('비밀번호', { exact: true }).fill(testPassword)
}

test('password sign-in remembers this device by default, reloads its session, and logs out', async ({ page }) => {
  const state = await mockPasswordApp(page)
  await page.goto('/')
  const username = page.getByLabel('아이디', { exact: true }), password = page.getByLabel('비밀번호', { exact: true })
  const homeIcon = page.getByRole('link', { name: '데이보이스 홈', exact: true }).locator('img')
  await expect(homeIcon).toBeVisible()
  await expect(homeIcon).toHaveAttribute('src', '/brand/dayvoice-mark.png?v=20260925')
  await expect(username).toHaveValue(''); await expect(password).toHaveValue('')
  await expect(username).toHaveAttribute('autocomplete', 'username')
  await expect(password).toHaveAttribute('autocomplete', 'current-password')
  await expect(page.getByRole('checkbox', { name: '자동 로그인 (30일)' })).toBeChecked()
  await page.screenshot({ path: `test-results/password-sign-in-${test.info().project.name}.png`, fullPage: true })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await fillCredentials(page)
  await page.getByRole('button', { name: '비밀번호 보기', exact: true }).click()
  await expect(password).toHaveAttribute('type', 'text')
  await page.getByRole('button', { name: '비밀번호 숨기기', exact: true }).click()
  await expect(password).toHaveAttribute('type', 'password')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page.getByRole('textbox', { name: '일정 요청' })).toBeEnabled()
  expect(state.loginBody).toEqual({ username: testUsername, password: testPassword, rememberMe: true })
  expect(await page.evaluate(() => JSON.stringify({ local: localStorage, session: sessionStorage }))).not.toContain(testPassword)
  await page.reload()
  await expect(username).toHaveCount(0)
  await page.getByRole('button', { name: '계정 설정', exact: true }).click()
  await page.getByRole('button', { name: '로그아웃', exact: true }).click()
  await expect(username).toHaveValue(''); await expect(password).toHaveValue('')
  expect(state.remembered).toBe(false)
  await fillCredentials(page)
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page.getByRole('textbox', { name: '일정 요청' })).toBeEnabled()
})

test('automatic login can be disabled and a new account connects Google before listing calendars', async ({ page }) => {
  const state = await mockPasswordApp(page); state.googleConnected = false
  await page.goto('/')
  await fillCredentials(page)
  await page.getByRole('checkbox', { name: '자동 로그인 (30일)' }).uncheck()
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page.getByRole('textbox', { name: '일정 요청' })).toBeEnabled()
  await expect(page.getByRole('button', { name: '일정 추가', exact: true })).toBeDisabled()
  expect(state.loginBody?.rememberMe).toBe(false)
  await page.getByRole('button', { name: 'Google Calendar 연결', exact: true }).click()
  await expect(page.getByRole('link', { name: 'Google Calendar 연결', exact: true })).toHaveAttribute('href', '/api/auth/google')
  await expect(page.getByLabel('사용할 캘린더')).toHaveCount(0)
  await expect(page.getByRole('alert')).toHaveCount(0)
  expect(state.calendarRequests).toBe(0)
})

test('invalid credentials show the server error, clear the password, and allow another attempt', async ({ page }) => {
  const state = await mockPasswordApp(page); state.reject = true
  await page.goto('/')
  await fillCredentials(page)
  await page.getByRole('button', { name: '비밀번호 보기', exact: true }).click()
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page.getByRole('alert')).toHaveText('아이디 또는 비밀번호가 올바르지 않습니다.')
  await expect(page.getByLabel('아이디', { exact: true })).toHaveValue(testUsername)
  await expect(page.getByLabel('비밀번호', { exact: true })).toHaveValue('')
  await expect(page.getByLabel('비밀번호', { exact: true })).toHaveAttribute('type', 'password')
  await expect(page.getByRole('textbox', { name: '일정 요청' })).toBeDisabled()
  state.reject = false
  await page.getByLabel('비밀번호', { exact: true }).fill(testPassword)
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page.getByRole('textbox', { name: '일정 요청' })).toBeEnabled()
})

test('a public HTTPS setup directs local visitors to the canonical domain before requesting a password', async ({ page }) => {
  const state = await mockPasswordApp(page); state.publicBaseUrl = 'https://ctfmon.shop'
  await page.goto('/')
  await expect(page.getByRole('link', { name: 'HTTPS 주소로 접속' })).toHaveAttribute('href', 'https://ctfmon.shop')
  await expect(page.getByLabel('비밀번호', { exact: true })).toHaveCount(0)
})
