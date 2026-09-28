import { describe, expect, it } from 'vitest'
import { dayKey, moveDay, onDay, type CalendarEvent } from './api'
describe('Seoul calendar boundaries', () => {
  it('uses Seoul date regardless of host timezone', () => {
    expect(dayKey(new Date('2026-09-24T15:00:00Z'))).toBe('2026-09-25')
    expect(moveDay('2026-12-31', 1)).toBe('2027-01-01')
  })
  it('excludes all-day end date and includes intermediate days', () => {
    const event = { data: { allDay: true, start: '2026-09-25', end: '2026-09-27' } } as CalendarEvent
    expect(onDay(event, '2026-09-26')).toBe(true)
    expect(onDay(event, '2026-09-27')).toBe(false)
  })
  it('handles a timed event crossing midnight', () => {
    const event = { data: { allDay: false, start: '2026-09-25T23:30:00+09:00', end: '2026-09-26T00:30:00+09:00' } } as CalendarEvent
    expect(onDay(event, '2026-09-25')).toBe(true)
    expect(onDay(event, '2026-09-26')).toBe(true)
    expect(onDay(event, '2026-09-27')).toBe(false)
  })
})
