import { describe, expect, it } from 'vitest';

import { LocalNotificationsError } from '../src/errors';
import {
  addEvery,
  nextCalendarOccurrence,
  nextPlanAfter,
  parseNotifications,
  toIso,
} from '../src/schedule';

const NOW = Date.parse('2026-11-05T15:30:45.000Z');

function codeOf(run: () => unknown): string {
  try {
    run();
  } catch (error) {
    if (error instanceof LocalNotificationsError) return error.code;
    throw error;
  }
  throw new Error('expected a LocalNotificationsError');
}

function one(notification: Record<string, unknown>, now = NOW) {
  return parseNotifications({ notifications: [notification] }, now)[0];
}

describe('parseNotifications', () => {
  it('accepts an immediate notification and trims text', () => {
    const notification = one({ id: 7, title: '  Hello  ', body: '  ' });
    expect(notification).toMatchObject({
      id: 7,
      title: 'Hello',
      body: '',
      autoCancel: true,
      groupSummary: false,
      allowWhileIdle: false,
      plan: { kind: 'immediate' },
    });
    expect(notification?.schedule).toBeUndefined();
  });

  it('accepts a future ISO timestamp and a Date', () => {
    const at = '2026-11-05T16:00:00.000Z';
    const fromString = one({ id: 1, title: 'Later', body: 'Body', schedule: { at } });
    const fromDate = one({
      id: 2,
      title: 'Later',
      body: 'Body',
      schedule: { at: new Date(at) },
    });
    expect(fromString?.plan).toEqual({ kind: 'once', atMs: Date.parse(at) });
    expect(fromDate?.schedule?.at).toBe(at);
    expect(fromString?.schedule).toEqual({ at });
  });

  it('accepts an offset timestamp and normalizes it to UTC', () => {
    const notification = one({
      id: 1,
      title: 'Offset',
      body: 'Body',
      schedule: { at: '2026-11-05T18:00:00+02:00' },
    });
    expect(notification?.schedule?.at).toBe('2026-11-05T16:00:00.000Z');
  });

  it('builds a fixed interval when at repeats', () => {
    const at = NOW + 120_000;
    const notification = one({
      id: 1,
      title: 'Repeat',
      body: 'Body',
      schedule: { at: new Date(at), repeats: true, allowWhileIdle: true },
    });
    expect(notification?.plan).toEqual({
      kind: 'fixed-interval',
      anchorMs: at,
      intervalMs: 120_000,
      nextMs: at,
    });
    expect(notification?.allowWhileIdle).toBe(true);
    const next = nextPlanAfter(notification!.plan, at);
    expect(next).toMatchObject({ nextMs: at + 120_000 });
  });

  it('schedules every two minutes from now', () => {
    const notification = one({
      id: 1,
      title: 'Interval',
      body: 'Body',
      schedule: { every: 'minute', count: 2 },
    });
    expect(notification?.plan).toMatchObject({
      kind: 'interval',
      unit: 'minute',
      count: 2,
      anchorMs: NOW,
      nextMs: NOW + 120_000,
    });
  });

  it('adds calendar months without a fixed 30 day approximation', () => {
    const start = Date.parse('2026-01-31T00:00:00.000Z');
    expect(toIso(addEvery(start, 'month', 1))).toBe('2026-03-03T00:00:00.000Z');
    expect(toIso(addEvery(start, 'year', 1))).toBe('2027-01-31T00:00:00.000Z');
  });

  it('rejects invalid notifications', () => {
    expect(codeOf(() => parseNotifications(undefined, NOW))).toBe('INVALID_NOTIFICATION');
    expect(codeOf(() => parseNotifications({ notifications: [] }, NOW))).toBe('INVALID_NOTIFICATION');
    expect(codeOf(() => one({ id: 1.5, title: 'A', body: 'B' }))).toBe('INVALID_NOTIFICATION');
    expect(codeOf(() => one({ id: 1, title: '   ', body: 'B' }))).toBe('INVALID_NOTIFICATION');
    expect(codeOf(() => one({ id: 1, title: 'A', body: 'B', sound: '../beep.wav' }))).toBe('INVALID_NOTIFICATION');
    expect(codeOf(() => one({ id: 1, title: 'A', body: 'B', smallIcon: 'Icon' }))).toBe('INVALID_NOTIFICATION');
    expect(codeOf(() => one({ id: 1, title: 'A', body: 'B', extra: JSON.parse('{"__proto__":{"admin":true}}') }))).toBe(
      'INVALID_NOTIFICATION',
    );
    expect(codeOf(() => one({ id: 1, title: 'A', body: 'B', inboxList: ['', 'ok'] }))).toBe('INVALID_NOTIFICATION');
    expect(codeOf(() => one({ id: 1, title: 'A', body: 'B', groupSummary: true }))).toBe('INVALID_NOTIFICATION');
    expect(
      codeOf(() =>
        parseNotifications(
          {
            notifications: [
              { id: 1, title: 'A', body: 'B' },
              { id: 1, title: 'C', body: 'D' },
            ],
          },
          NOW,
        ),
      ),
    ).toBe('INVALID_NOTIFICATION');
  });

  it('rejects ambiguous or impossible schedules', () => {
    const at = new Date(NOW + 120_000).toISOString();
    expect(codeOf(() => one({ id: 1, title: 'A', body: 'B', schedule: { at, every: 'day' } }))).toBe('INVALID_SCHEDULE');
    expect(codeOf(() => one({ id: 1, title: 'A', body: 'B', schedule: { at: new Date(NOW - 1000).toISOString() } }))).toBe(
      'INVALID_SCHEDULE',
    );
    expect(codeOf(() => one({ id: 1, title: 'A', body: 'B', schedule: { at: 'tomorrow' } }))).toBe('INVALID_SCHEDULE');
    expect(codeOf(() => one({ id: 1, title: 'A', body: 'B', schedule: { repeats: true } }))).toBe('INVALID_SCHEDULE');
    expect(codeOf(() => one({ id: 1, title: 'A', body: 'B', schedule: { every: 'second', count: 59 } }))).toBe(
      'INVALID_SCHEDULE',
    );
    expect(codeOf(() => one({ id: 1, title: 'A', body: 'B', schedule: { on: {} } }))).toBe('INVALID_SCHEDULE');
    expect(codeOf(() => one({ id: 1, title: 'A', body: 'B', schedule: { on: { month: 0 } } }))).toBe('INVALID_SCHEDULE');
    expect(codeOf(() => one({ id: 1, title: 'A', body: 'B', schedule: { count: 2 } }))).toBe('INVALID_SCHEDULE');
    expect(
      codeOf(() =>
        one({
          id: 1,
          title: 'A',
          body: 'B',
          schedule: { at: new Date(NOW + 30_000).toISOString(), repeats: true },
        }),
      ),
    ).toBe('INVALID_SCHEDULE');
  });
});

describe('nextCalendarOccurrence', () => {
  it('rolls a fixed local time to the next matching day', () => {
    expect(toIso(nextCalendarOccurrence({ hour: 9 }, NOW))).toBe('2026-11-06T09:00:00.000Z');
    expect(toIso(nextCalendarOccurrence({ hour: 16 }, NOW))).toBe('2026-11-05T16:00:00.000Z');
  });

  it('treats an omitted hour as a wildcard when only a minute is set', () => {
    expect(toIso(nextCalendarOccurrence({ minute: 15 }, NOW))).toBe('2026-11-05T16:15:00.000Z');
    expect(toIso(nextCalendarOccurrence({ second: 10 }, NOW))).toBe('2026-11-05T15:31:10.000Z');
    expect(toIso(nextCalendarOccurrence({ second: 50 }, NOW))).toBe('2026-11-05T15:30:50.000Z');
  });

  it('matches weekday and day-of-month in local time', () => {
    expect(toIso(nextCalendarOccurrence({ weekday: 2, hour: 9, minute: 0 }, NOW))).toBe('2026-11-09T09:00:00.000Z');
    expect(toIso(nextCalendarOccurrence({ day: 31, hour: 10 }, NOW))).toBe('2026-12-31T10:00:00.000Z');
    expect(toIso(nextCalendarOccurrence({ year: 2027, month: 1, day: 1 }, NOW))).toBe('2027-01-01T00:00:00.000Z');
  });

  it('rejects a date that never occurs', () => {
    expect(codeOf(() => nextCalendarOccurrence({ year: 2027, month: 2, day: 31 }, NOW))).toBe('INVALID_SCHEDULE');
  });

  it('finds the next calendar fire after a delivery', () => {
    const first = nextCalendarOccurrence({ hour: 9 }, NOW);
    const plan = nextPlanAfter({ kind: 'calendar', on: { hour: 9 }, nextMs: first }, first);
    expect(plan && 'nextMs' in plan ? toIso(plan.nextMs) : '').toBe('2026-11-07T09:00:00.000Z');
  });
});
