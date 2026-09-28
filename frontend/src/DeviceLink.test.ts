import { describe, expect, it } from 'vitest'
import { getPublicHttpsOrigin } from './DeviceLink'

describe('public HTTPS entry address', () => {
  it('uses only the configured public HTTPS origin', () => {
    expect(getPublicHttpsOrigin('https://ctfmon.shop/path?next=elsewhere#section')).toBe('https://ctfmon.shop')
  })
  it.each([
    'http://ctfmon.shop', 'https://localhost:8443', 'https://app.localhost:8443',
    'https://localhost.:8443', 'https://127.0.0.1:8443', 'https://192.168.0.10:8443',
    'https://[::1]:8443', 'javascript:alert(1)', 'https://user:password@ctfmon.shop', 'invalid',
  ])('keeps local or invalid addresses out of the public HTTPS flow: %s', address => {
    expect(getPublicHttpsOrigin(address)).toBeNull()
  })
})
