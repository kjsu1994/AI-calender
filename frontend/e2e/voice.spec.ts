import { test, expect } from '@playwright/test'

test.use({ launchOptions: { args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'] }, permissions: ['microphone'] })
test('microphone starts, stops, uploads recorded audio, and releases its tracks', async ({ page }) => {
  let uploaded = false
  // Exercise real browser recording without sending synthetic audio to a paid API.
  await page.route('**/api/commands/audio', async route => {
    const body = route.request().postDataBuffer()
    uploaded = !!body && body.length > 100
    expect(route.request().headers()['content-type']).toContain('multipart/form-data')
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ commandId: 'test-only', status: 'NEEDS_INPUT', message: '녹음 입력을 확인했습니다.', transcript: '테스트 음성' }) })
  })
  await page.goto('/'); await page.getByRole('button', { name: '샘플로 시작하기' }).click()
  await page.getByRole('button', { name: '음성 입력 시작' }).click()
  await expect(page.getByRole('heading', { name: '듣고 있어요. 편하게 말해 주세요.' })).toBeVisible()
  await expect(page.locator('.voice-state')).toContainText('1초', { timeout: 4000 })
  await page.getByRole('button', { name: '녹음 종료' }).click()
  await expect(page.getByText('녹음 입력을 확인했습니다.', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '음성 입력 시작' })).toBeEnabled()
  expect(uploaded).toBe(true)
})
