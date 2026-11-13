import type { LocalNotificationSchema, Schedule, ScheduleEvery } from './definitions';
import { LocalNotificationsError, type LocalNotificationErrorCode } from './errors';

export const MIN_REPEAT_MS = 60_000;
export const MAX_LEAD_MS = 8 * 366 * 24 * 60 * 60 * 1000;
export const MAX_BATCH = 50;
export const MAX_PENDING = 100;
export const MAX_EXTRA_JSON = 4096;
export const INT32_MIN = -2_147_483_648;
export const INT32_MAX = 2_147_483_647;

const EVERY_UNITS: readonly ScheduleEvery[] = [
  'year',
  'month',
  'two-weeks',
  'week',
  'day',
  'hour',
  'minute',
  'second',
];

const SOUND_FILE_PATTERN = /^[A-Za-z0-9_-]{1,100}\.[A-Za-z0-9]{1,8}$/;
const ISO_INSTANT =
  /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?(?:Z|[+-]\d{2}:\d{2})$/;

export interface CalendarMatch {
  year?: number;
  month?: number;
  day?: number;
  weekday?: number;
  hour?: number;
  minute?: number;
  second?: number;
}

export type FirePlan =
  | { kind: 'immediate' }
  | { kind: 'once'; atMs: number }
  | { kind: 'fixed-interval'; anchorMs: number; intervalMs: number; nextMs: number }
  | { kind: 'interval'; unit: ScheduleEvery; count: number; anchorMs: number; nextMs: number }
  | { kind: 'calendar'; on: CalendarMatch; nextMs: number };

export interface NormalizedSchedule {
  at?: string;
  repeats?: true;
  on?: CalendarMatch;
  every?: ScheduleEvery;
  count?: number;
}

export interface ValidatedNotification {
  id: number;
  title: string;
  body: string;
  sound?: string;
  extra?: Record<string, unknown>;
  schedule?: NormalizedSchedule;
  plan: FirePlan;
}

export function parseNotifications(options: unknown, now: number): ValidatedNotification[] {
  if (!isPlainObject(options)) {
    throw new LocalNotificationsError('INVALID_NOTIFICATION', 'schedule() expects an object with a notifications array.');
  }
  const list = options.notifications;
  if (!Array.isArray(list) || list.length === 0) {
    throw new LocalNotificationsError('INVALID_NOTIFICATION', 'notifications must be a non-empty array.');
  }
  if (list.length > MAX_BATCH) {
    throw new LocalNotificationsError('INVALID_NOTIFICATION', `At most ${MAX_BATCH} notifications can be scheduled in one call.`);
  }
  const parsed = list.map((item, index) => parseNotification(item, now, index));
  const ids = new Set<number>();
  for (const notification of parsed) {
    if (ids.has(notification.id)) {
      throw new LocalNotificationsError('INVALID_NOTIFICATION', `Duplicate notification id ${notification.id} in one schedule() call.`);
    }
    ids.add(notification.id);
  }
  return parsed;
}

export function parseNotification(value: unknown, now: number, index = 0): ValidatedNotification {
  if (!isPlainObject(value)) {
    throw new LocalNotificationsError('INVALID_NOTIFICATION', `Notification ${index} must be an object.`);
  }
  const id = requireInt(value.id, INT32_MIN, INT32_MAX, 'id');
  const title = requireText(value.title, 1, 200, 'title', true);
  const body = requireText(value.body, 0, 2000, 'body', true);
  const scheduled = parseSchedule(value.schedule, now);
  return {
    id,
    title,
    body,
    sound: parseSound(value.sound),
    extra: parseExtra(value.extra),
    schedule: scheduled.schedule,
    plan: scheduled.plan,
  };
}

export function parseSchedule(value: unknown, now: number): { schedule?: NormalizedSchedule; plan: FirePlan } {
  if (value == null) {
    return { plan: { kind: 'immediate' } };
  }
  if (!isPlainObject(value)) {
    throw new LocalNotificationsError('INVALID_SCHEDULE', 'schedule must be an object.');
  }
  const hasAt = value.at !== undefined;
  const hasOn = value.on !== undefined;
  const hasEvery = value.every !== undefined;
  if (Number(hasAt) + Number(hasOn) + Number(hasEvery) > 1) {
    throw new LocalNotificationsError('INVALID_SCHEDULE', 'Use only one of schedule.at, schedule.on, or schedule.every.');
  }
  if (value.count !== undefined && !hasEvery) {
    throw new LocalNotificationsError('INVALID_SCHEDULE', 'schedule.count is only valid together with schedule.every.');
  }
  if (value.repeats !== undefined && !hasAt) {
    throw new LocalNotificationsError('INVALID_SCHEDULE', 'schedule.repeats is only valid together with schedule.at.');
  }

  if (hasEvery) {
    const every = parseEvery(value.every);
    const count = value.count === undefined ? 1 : requireInt(value.count, 1, 1000, 'count', 'INVALID_SCHEDULE');
    const nextMs = addEvery(now, every, count);
    const interval = nextMs - now;
    if (interval < MIN_REPEAT_MS) {
      throw new LocalNotificationsError('INVALID_SCHEDULE', 'Repeating notifications must be at least 60 seconds apart.');
    }
    if (interval > MAX_LEAD_MS) {
      throw new LocalNotificationsError('INVALID_SCHEDULE', 'The first fire time must be within 8 years.');
    }
    return {
      schedule: { every, count },
      plan: { kind: 'interval', unit: every, count, anchorMs: now, nextMs },
    };
  }

  if (hasOn) {
    const on = parseCalendarMatch(value.on);
    const nextMs = nextCalendarOccurrence(on, now);
    return {
      schedule: { on },
      plan: { kind: 'calendar', on, nextMs },
    };
  }

  if (hasAt) {
    const atMs = parseAt(value.at);
    const repeats = optionalBool(value.repeats, false, 'repeats', 'INVALID_SCHEDULE');
    if (atMs <= now) {
      throw new LocalNotificationsError('INVALID_SCHEDULE', 'schedule.at must be in the future.');
    }
    if (atMs - now > MAX_LEAD_MS) {
      throw new LocalNotificationsError('INVALID_SCHEDULE', 'schedule.at must be within 8 years.');
    }
    if (repeats && atMs - now < MIN_REPEAT_MS) {
      throw new LocalNotificationsError('INVALID_SCHEDULE', 'A repeating schedule.at must be at least 60 seconds in the future.');
    }
    const at = toIso(atMs);
    if (repeats) {
      const intervalMs = atMs - now;
      return {
        schedule: { at, repeats: true },
        plan: { kind: 'fixed-interval', anchorMs: atMs, intervalMs, nextMs: atMs },
      };
    }
    return {
      schedule: { at },
      plan: { kind: 'once', atMs },
    };
  }

  return { plan: { kind: 'immediate' } };
}

export function parseCancelIds(value: unknown): number[] {
  if (!isPlainObject(value) || !Array.isArray(value.notifications) || value.notifications.length === 0) {
    throw new LocalNotificationsError('INVALID_NOTIFICATION', 'notifications must be a non-empty array of { id } objects.');
  }
  if (value.notifications.length > MAX_BATCH) {
    throw new LocalNotificationsError('INVALID_NOTIFICATION', `At most ${MAX_BATCH} notifications can be cancelled in one call.`);
  }
  return value.notifications.map((entry) => {
    if (!isPlainObject(entry)) {
      throw new LocalNotificationsError('INVALID_NOTIFICATION', 'Each cancellation entry must be an object with an id.');
    }
    return requireInt(entry.id, INT32_MIN, INT32_MAX, 'id');
  });
}

export function nextCalendarOccurrence(match: CalendarMatch, fromMs: number): number {
  validateCalendarMatch(match);
  const mode = timeMode(match);
  if (mode.minuteWild) {
    return nextSecond(fromMs, mode.second);
  }
  if (mode.hourWild) {
    return nextMinuteOfHour(fromMs, mode.minute, mode.second);
  }
  const limit = fromMs + MAX_LEAD_MS;
  const day = new Date(fromMs);
  day.setHours(0, 0, 0, 0);
  for (let i = 0; i < 366 * 8 + 3; i += 1) {
    const candidate = atLocalTime(day, mode.hour, mode.minute, mode.second);
    if (candidate != null && candidate > fromMs && dateFieldsMatch(candidate, match)) {
      return candidate;
    }
    day.setDate(day.getDate() + 1);
    if (day.getTime() > limit) {
      break;
    }
  }
  throw new LocalNotificationsError('INVALID_SCHEDULE', 'No matching local time in the next 8 years.');
}

export function addEvery(fromMs: number, unit: ScheduleEvery, count: number): number {
  if (unit === 'second' || unit === 'minute' || unit === 'hour') {
    const unitMs = unit === 'second' ? 1000 : unit === 'minute' ? 60_000 : 3_600_000;
    return fromMs + count * unitMs;
  }
  const date = new Date(fromMs);
  switch (unit) {
    case 'day':
      date.setDate(date.getDate() + count);
      break;
    case 'week':
      date.setDate(date.getDate() + 7 * count);
      break;
    case 'two-weeks':
      date.setDate(date.getDate() + 14 * count);
      break;
    case 'month':
      date.setMonth(date.getMonth() + count);
      break;
    case 'year':
      date.setFullYear(date.getFullYear() + count);
      break;
    default:
      throw new LocalNotificationsError('INVALID_SCHEDULE', 'Unknown schedule.every value.');
  }
  return date.getTime();
}

/** Next fire strictly after `fromMs`, or null when the plan does not repeat. */
export function nextPlanAfter(plan: FirePlan, fromMs: number): FirePlan | null {
  switch (plan.kind) {
    case 'immediate':
    case 'once':
      return null;
    case 'fixed-interval': {
      if (fromMs < plan.anchorMs) return { ...plan, nextMs: plan.anchorMs };
      const steps = Math.floor((fromMs - plan.anchorMs) / plan.intervalMs) + 1;
      return { ...plan, nextMs: plan.anchorMs + steps * plan.intervalMs };
    }
    case 'interval': {
      if (fromMs < plan.anchorMs) return plan;
      if (plan.unit === 'second' || plan.unit === 'minute' || plan.unit === 'hour') {
        const unitMs = plan.unit === 'second' ? 1000 : plan.unit === 'minute' ? 60_000 : 3_600_000;
        const intervalMs = plan.count * unitMs;
        const steps = Math.floor((fromMs - plan.anchorMs) / intervalMs) + 1;
        return { ...plan, nextMs: plan.anchorMs + steps * intervalMs };
      }
      let nextMs = plan.anchorMs;
      let guard = 0;
      do {
        nextMs = addEvery(nextMs, plan.unit, plan.count);
        guard += 1;
      } while (nextMs <= fromMs && guard < 20_000);
      if (nextMs <= fromMs) {
        throw new LocalNotificationsError('INVALID_SCHEDULE', 'The repeating schedule could not find a future time.');
      }
      return { ...plan, nextMs };
    }
    case 'calendar':
      return { ...plan, nextMs: nextCalendarOccurrence(plan.on, fromMs) };
    default:
      return null;
  }
}

export function planNextMs(plan: FirePlan): number | null {
  switch (plan.kind) {
    case 'immediate':
      return null;
    case 'once':
      return plan.atMs;
    case 'fixed-interval':
    case 'interval':
    case 'calendar':
      return plan.nextMs;
    default:
      return null;
  }
}

export function toPublicNotification(notification: ValidatedNotification): LocalNotificationSchema {
  return {
    id: notification.id,
    title: notification.title,
    body: notification.body,
    schedule: notification.schedule as Schedule | undefined,
    sound: notification.sound,
    extra: notification.extra,
  };
}

export function toIso(ms: number): string {
  return new Date(ms).toISOString();
}

function parseAt(value: unknown): number {
  if (value instanceof Date) {
    const ms = value.getTime();
    if (Number.isNaN(ms)) {
      throw new LocalNotificationsError('INVALID_SCHEDULE', 'schedule.at is an invalid Date.');
    }
    return ms;
  }
  if (typeof value === 'number') {
    if (!Number.isSafeInteger(value)) {
      throw new LocalNotificationsError('INVALID_SCHEDULE', 'schedule.at milliseconds must be an integer.');
    }
    return value;
  }
  if (typeof value === 'string' && ISO_INSTANT.test(value)) {
    const ms = Date.parse(value);
    if (Number.isNaN(ms)) {
      throw new LocalNotificationsError('INVALID_SCHEDULE', 'schedule.at is not a valid instant.');
    }
    return ms;
  }
  throw new LocalNotificationsError(
    'INVALID_SCHEDULE',
    'schedule.at must be a Date or an ISO-8601 timestamp such as 2026-11-05T18:00:00.000Z.',
  );
}

function parseEvery(value: unknown): ScheduleEvery {
  if (typeof value === 'string' && (EVERY_UNITS as readonly string[]).includes(value)) {
    return value as ScheduleEvery;
  }
  throw new LocalNotificationsError(
    'INVALID_SCHEDULE',
    'schedule.every must be year, month, two-weeks, week, day, hour, minute, or second.',
  );
}

function parseCalendarMatch(value: unknown): CalendarMatch {
  if (!isPlainObject(value)) {
    throw new LocalNotificationsError('INVALID_SCHEDULE', 'schedule.on must be an object.');
  }
  const match: CalendarMatch = {};
  if (value.year !== undefined) match.year = requireInt(value.year, 1970, 2100, 'year', 'INVALID_SCHEDULE');
  if (value.month !== undefined) match.month = requireInt(value.month, 1, 12, 'month', 'INVALID_SCHEDULE');
  if (value.day !== undefined) match.day = requireInt(value.day, 1, 31, 'day', 'INVALID_SCHEDULE');
  if (value.weekday !== undefined) match.weekday = requireInt(value.weekday, 1, 7, 'weekday', 'INVALID_SCHEDULE');
  if (value.hour !== undefined) match.hour = requireInt(value.hour, 0, 23, 'hour', 'INVALID_SCHEDULE');
  if (value.minute !== undefined) match.minute = requireInt(value.minute, 0, 59, 'minute', 'INVALID_SCHEDULE');
  if (value.second !== undefined) match.second = requireInt(value.second, 0, 59, 'second', 'INVALID_SCHEDULE');
  validateCalendarMatch(match);
  return match;
}

function validateCalendarMatch(match: CalendarMatch): void {
  if (
    match.year == null &&
    match.month == null &&
    match.day == null &&
    match.weekday == null &&
    match.hour == null &&
    match.minute == null &&
    match.second == null
  ) {
    throw new LocalNotificationsError('INVALID_SCHEDULE', 'schedule.on must include at least one field.');
  }
}

function timeMode(match: CalendarMatch): { minuteWild: boolean; hourWild: boolean; hour: number; minute: number; second: number } {
  const anyDate = match.year != null || match.month != null || match.day != null || match.weekday != null;
  const secondSpec = match.second != null;
  const minuteSpec = match.minute != null;
  const hourSpec = match.hour != null;
  const minuteWild = !minuteSpec && !hourSpec && secondSpec && !anyDate;
  const hourWild = !hourSpec && (minuteSpec || secondSpec) && !anyDate;
  return {
    minuteWild,
    hourWild,
    hour: hourSpec ? (match.hour as number) : 0,
    minute: minuteSpec ? (match.minute as number) : 0,
    second: secondSpec ? (match.second as number) : 0,
  };
}

function nextSecond(fromMs: number, second: number): number {
  const date = new Date(fromMs);
  date.setMilliseconds(0);
  date.setSeconds(second, 0);
  if (date.getTime() <= fromMs || date.getSeconds() !== second) {
    date.setMinutes(date.getMinutes() + 1);
    date.setSeconds(second, 0);
  }
  return date.getTime();
}

function nextMinuteOfHour(fromMs: number, minute: number, second: number): number {
  const date = new Date(fromMs);
  date.setMilliseconds(0);
  date.setMinutes(minute, second, 0);
  if (date.getTime() <= fromMs || date.getMinutes() !== minute || date.getSeconds() !== second) {
    date.setHours(date.getHours() + 1);
    date.setMinutes(minute, second, 0);
  }
  return date.getTime();
}

function atLocalTime(day: Date, hour: number, minute: number, second: number): number | null {
  const date = new Date(day.getTime());
  date.setHours(hour, minute, second, 0);
  if (
    date.getFullYear() !== day.getFullYear() ||
    date.getMonth() !== day.getMonth() ||
    date.getDate() !== day.getDate() ||
    date.getHours() !== hour ||
    date.getMinutes() !== minute ||
    date.getSeconds() !== second
  ) {
    return null;
  }
  return date.getTime();
}

function dateFieldsMatch(ms: number, match: CalendarMatch): boolean {
  const date = new Date(ms);
  if (match.year != null && date.getFullYear() !== match.year) return false;
  if (match.month != null && date.getMonth() + 1 !== match.month) return false;
  if (match.day != null && date.getDate() !== match.day) return false;
  if (match.weekday != null && date.getDay() + 1 !== match.weekday) return false;
  return true;
}

function parseExtra(value: unknown): Record<string, unknown> | undefined {
  if (value == null) return undefined;
  if (!isPlainObject(value)) {
    throw new LocalNotificationsError('INVALID_NOTIFICATION', 'extra must be a plain object.');
  }
  const sanitized = sanitizeJson(value, 0, { count: 0 }) as Record<string, unknown>;
  if (JSON.stringify(sanitized).length > MAX_EXTRA_JSON) {
    throw new LocalNotificationsError('INVALID_NOTIFICATION', 'extra must be at most 4096 characters once encoded.');
  }
  return sanitized;
}

function sanitizeJson(value: unknown, depth: number, counter: { count: number }): unknown {
  if (value == null) return null;
  const kind = typeof value;
  if (kind === 'string') {
    if ((value as string).length > 500) {
      throw new LocalNotificationsError('INVALID_NOTIFICATION', 'Strings inside extra must be at most 500 characters.');
    }
    return value;
  }
  if (kind === 'number') {
    if (!Number.isFinite(value as number)) {
      throw new LocalNotificationsError('INVALID_NOTIFICATION', 'extra cannot contain NaN or Infinity.');
    }
    return value;
  }
  if (kind === 'boolean') return value;
  if (Array.isArray(value)) {
    if (depth >= 5 || value.length > 20) {
      throw new LocalNotificationsError('INVALID_NOTIFICATION', 'extra is too deep or has a list longer than 20 items.');
    }
    return value.map((item) => sanitizeJson(item, depth + 1, counter));
  }
  if (!isPlainObject(value)) {
    throw new LocalNotificationsError('INVALID_NOTIFICATION', 'extra only supports JSON values.');
  }
  if (depth >= 5) {
    throw new LocalNotificationsError('INVALID_NOTIFICATION', 'extra is nested more than 5 levels.');
  }
  const output: Record<string, unknown> = {};
  for (const [key, child] of Object.entries(value)) {
    if (key === '__proto__' || key === 'prototype' || key === 'constructor' || key.length === 0 || key.length > 64) {
      throw new LocalNotificationsError('INVALID_NOTIFICATION', 'extra contains a key that is not allowed.');
    }
    counter.count += 1;
    if (counter.count > 50) {
      throw new LocalNotificationsError('INVALID_NOTIFICATION', 'extra can contain at most 50 keys.');
    }
    output[key] = sanitizeJson(child, depth + 1, counter);
  }
  return output;
}

function parseSound(value: unknown): string | undefined {
  if (value == null) return undefined;
  if (typeof value !== 'string') {
    throw new LocalNotificationsError('INVALID_NOTIFICATION', 'sound must be a string.');
  }
  if (value === '' || value === 'default') return value;
  if (!SOUND_FILE_PATTERN.test(value)) {
    throw new LocalNotificationsError(
      'INVALID_NOTIFICATION',
      'sound must be "default", an empty string, or a file name such as beep.wav.',
    );
  }
  return value;
}

function requireText(
  value: unknown,
  min: number,
  max: number,
  name: string,
  trim: boolean,
  code: LocalNotificationErrorCode = 'INVALID_NOTIFICATION',
): string {
  if (typeof value !== 'string') {
    throw new LocalNotificationsError(code, `${name} must be a string.`);
  }
  const text = trim ? value.trim() : value;
  if (text.length < min || text.length > max) {
    throw new LocalNotificationsError(code, `${name} must be ${min} to ${max} characters.`);
  }
  return text;
}

function optionalBool(
  value: unknown,
  fallback: boolean,
  name: string,
  code: LocalNotificationErrorCode = 'INVALID_NOTIFICATION',
): boolean {
  if (value == null) return fallback;
  if (typeof value === 'boolean') return value;
  throw new LocalNotificationsError(code, `${name} must be a boolean.`);
}

function requireInt(
  value: unknown,
  min: number,
  max: number,
  name: string,
  code: LocalNotificationErrorCode = 'INVALID_NOTIFICATION',
): number {
  if (typeof value !== 'number' || !Number.isInteger(value) || value < min || value > max) {
    throw new LocalNotificationsError(code, `${name} must be an integer from ${min} to ${max}.`);
  }
  return value;
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return false;
  const proto = Object.getPrototypeOf(value);
  return proto === Object.prototype || proto === null;
}
