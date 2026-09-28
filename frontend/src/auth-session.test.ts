import { afterEach, expect, it, vi } from 'vitest'

afterEach(() => { vi.unstubAllGlobals(); vi.resetModules() })

it('reloads the rotated login session and ignores an older poll when updating CSRF', async () => {
  vi.resetModules()
  let finishOldSession!: (response: Response) => void
  const oldSession = new Promise<Response>(resolve => { finishOldSession = resolve })
  const fetchMock = vi.fn()
    .mockResolvedValueOnce(Response.json({ csrfToken: 'before-login', csrfHeader: 'X-CSRF-TOKEN' }))
    .mockReturnValueOnce(oldSession)
    .mockResolvedValueOnce(Response.json({ ok: true }))
    .mockResolvedValueOnce(Response.json({ csrfToken: 'after-login', csrfHeader: 'X-CSRF-TOKEN' }))
    .mockResolvedValueOnce(Response.json({ ok: true }))
  vi.stubGlobal('fetch', fetchMock)
  const { api } = await import('./api')
  await api('/session')
  const olderPoll = api('/session')
  await api('/auth/password', 'POST', { username: 'test-user', password: 'test-only', rememberMe: true })
  await expect(api('/session')).resolves.toMatchObject({ csrfToken: 'after-login' })
  finishOldSession(Response.json({ csrfToken: 'stale-token', csrfHeader: 'X-CSRF-TOKEN' }))
  await olderPoll
  await api('/auth/logout', 'POST')
  expect(fetchMock.mock.calls[2][1].headers['X-CSRF-TOKEN']).toBe('before-login')
  expect(fetchMock.mock.calls[4][1].headers['X-CSRF-TOKEN']).toBe('after-login')
})
