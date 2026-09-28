import { test, expect, type Page } from '@playwright/test'

test.use({ launchOptions: { args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'] }, permissions: ['microphone'] })

async function mockAccount(page: Page) {
  const state = { id: 'account-a' as string | null, audioUploads: 0, holdNextData: false, dataRequests: 0 }
  let releaseData!: () => void
  const dataGate = new Promise<void>(resolve => { releaseData = resolve })
  await page.clock.install({ time: new Date('2026-09-25T06:00:00Z') })
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname, owner = state.id
    if (path === '/api/session') return route.fulfill({ json: {
      publicBaseUrl: 'http://localhost:8085', csrfToken: 'test-csrf', csrfHeader: 'X-CSRF-TOKEN', demoMode: false,
      googleConfigured: true, geminiConfigured: true, passwordLoginConfigured: true, googleConnected: !!owner,
      ...(owner ? { user: { id: owner, name: owner, email: 'same-email@example.test', demo: false },
        calendarId: 'test-calendar', calendarName: owner + ' 캘린더', syncStatus: 'SYNCED' } : {}),
    } })
    const data = { title: owner + ' 비공개 일정', start: '2026-09-25T15:00:00+09:00', end: '2026-09-25T16:00:00+09:00', allDay: false, location: '', description: owner + ' 비공개 메모' }
    const event = { id: owner + '-event', version: 1, syncState: 'PENDING', recurring: false, deleted: false, data }
    const record = { id: owner + '-record', version: 1, data: { ...data, title: owner + ' 비공개 기록' } }
    if (['/api/events', '/api/records', '/api/sync'].includes(path)) {
      state.dataRequests++
      if (state.holdNextData) await dataGate
      return route.fulfill({ json: path === '/api/events' ? [event] : path === '/api/records' ? [record] : { issues: [event] } })
    }
    if (path === '/api/commands/text') return route.fulfill({ json: {
      commandId: 'private-command', status: 'CONFIRMATION', message: owner + ' 비공개 확인 메시지', before: data, after: { ...data, title: owner + ' 변경할 제목' },
    } })
    if (path === '/api/commands/audio') { state.audioUploads++; return route.fulfill({ json: { status: 'NEEDS_INPUT', message: '테스트 음성' } }) }
    if (path === '/api/calendars') return route.fulfill({ json: [{ id: 'test-calendar', name: owner + ' 캘린더' }] })
    throw new Error(`Unexpected request: ${path}`)
  })
  return { state, releaseData }
}

test('session expiry clears schedules, records, drafts, confirmations, and the open record editor', async ({ page }) => {
  const { state } = await mockAccount(page)
  await page.goto('/')
  await expect(page.getByRole('heading', { name: 'account-a 비공개 일정', exact: true })).toBeVisible()
  await page.getByRole('textbox', { name: '일정 요청' }).fill('테스트 변경 요청')
  await page.getByRole('button', { name: '요청 보내기' }).click()
  await expect(page.getByText('account-a 비공개 확인 메시지')).toBeVisible()
  await page.getByRole('textbox', { name: '일정 요청' }).fill('account-a 저장하지 않은 요청')
  await page.locator('.workspace-tabs').getByRole('button', { name: /시간별 기록/ }).click()
  await page.getByRole('button', { name: 'account-a 비공개 기록 기록 수정' }).click()
  await expect(page.getByLabel('기록 내용', { exact: true })).toHaveValue('account-a 비공개 메모')
  state.id = null
  await page.clock.runFor(2100)
  await expect(page.getByLabel('아이디', { exact: true })).toBeVisible()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(page.locator('.command-card,.issues-section,.event-row,.record-card')).toHaveCount(0)
  await expect(page.getByRole('textbox', { name: '일정 요청' })).toHaveValue('')
  await expect(page.locator('body')).not.toContainText('account-a')
  await page.locator('.workspace-tabs').getByRole('button', { name: /시간별 기록/ }).click()
  await expect(page.locator('.record-card')).toHaveCount(0)
})

test('a changed uid clears the previous account before the new account data arrives', async ({ page }) => {
  const { state, releaseData } = await mockAccount(page)
  await page.goto('/')
  await page.getByRole('button', { name: 'account-a 비공개 일정 상세 보기' }).click()
  await expect(page.getByLabel('일정 이름')).toHaveValue('account-a 비공개 일정')
  state.id = 'account-b'; state.holdNextData = true
  const previousRequests = state.dataRequests
  await page.clock.runFor(2100)
  await expect.poll(() => state.dataRequests).toBeGreaterThan(previousRequests)
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(page.locator('.event-row,.record-card,.issues-section')).toHaveCount(0)
  await expect(page.locator('body')).not.toContainText('account-a')
  releaseData()
  await expect(page.getByRole('heading', { name: 'account-b 비공개 일정', exact: true })).toBeVisible()
  await expect(page.locator('body')).not.toContainText('account-a')
})

test('an expiring session stops recording without submitting its audio under another session', async ({ page }) => {
  const { state } = await mockAccount(page)
  await page.goto('/')
  await page.getByRole('button', { name: '음성 입력 시작' }).click()
  await expect(page.getByRole('button', { name: '녹음 종료' })).toBeVisible()
  state.id = null
  await page.clock.runFor(2100)
  await expect(page.getByLabel('아이디', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '녹음 종료' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '음성 입력 시작' })).toBeDisabled()
  await page.clock.runFor(1000)
  expect(state.audioUploads).toBe(0)
})
