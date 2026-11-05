import { WebPlugin } from '@capacitor/core';

import type {
  ActionPerformed,
  CancelOptions,
  Channel,
  DeliveredNotifications,
  EnabledResult,
  ExactAlarmStatus,
  ListChannelsResult,
  LocalNotificationSchema,
  LocalNotificationsPlugin,
  PendingResult,
  PermissionStatus,
  RegisterActionTypesOptions,
  ScheduleOptions,
  ScheduleResult,
} from './definitions';
import { LocalNotificationsError } from './errors';
import {
  MAX_PENDING,
  nextPlanAfter,
  parseActionTypes,
  parseCancelIds,
  parseChannel,
  parseNotifications,
  planNextMs,
  toPublicNotification,
  type FirePlan,
  type ValidatedNotification,
} from './schedule';

const STORAGE_KEY = 'capnative.local-notifications.v1';
const MAX_TIMER_MS = 2_147_000_000;
const MAX_DELIVERED = 50;

export interface KeyValueStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

export interface ShownNotification {
  close(): void;
  onClick(handler: () => void): void;
  onClose(handler: () => void): void;
}

export interface WebEnvironment {
  now(): number;
  setTimer(callback: () => void, delayMs: number): number;
  clearTimer(id: number): void;
  storage: KeyValueStorage;
  notificationsSupported(): boolean;
  readPermission(): 'granted' | 'denied' | 'prompt';
  requestPermission(): Promise<'granted' | 'denied' | 'prompt'>;
  show(input: { title: string; body: string; tag: string; silent: boolean }): ShownNotification;
  setBadge?(count: number): void;
}

interface PersistedNotification {
  notification: LocalNotificationSchema;
  plan: FirePlan;
}

interface PersistedState {
  pending: PersistedNotification[];
  actions: string[];
}

interface DeliveredEntry {
  notification: LocalNotificationSchema;
  handle: ShownNotification;
}

/**
 * Web fallback. Immediate notifications use the browser Notification API.
 * Scheduled notifications are stored in localStorage and armed with timers.
 * Timers only run while this page is open; a later visit delivers at most one
 * catch-up notification and then resumes the next future occurrence.
 * Browser action buttons are not available. A click is reported as `tap`.
 */
export class LocalNotificationsWeb extends WebPlugin implements LocalNotificationsPlugin {
  private readonly env: WebEnvironment;
  private state: PersistedState;
  private readonly timers = new Map<number, number>();
  private readonly delivered: DeliveredEntry[] = [];
  private readonly actionTypes = new Set<string>();

  constructor(env?: WebEnvironment) {
    super();
    this.env = env ?? browserEnvironment();
    this.state = readState(this.env.storage);
    for (const id of this.state.actions) {
      this.actionTypes.add(id);
    }
    for (const item of this.state.pending) {
      this.arm(item.notification.id as number, planNextMs(item.plan));
    }
  }

  async checkPermissions(): Promise<PermissionStatus> {
    this.assertSupported();
    return { display: this.env.readPermission() };
  }

  async requestPermissions(): Promise<PermissionStatus> {
    this.assertSupported();
    return { display: await this.env.requestPermission() };
  }

  async areEnabled(): Promise<EnabledResult> {
    if (!this.env.notificationsSupported()) {
      return { value: false };
    }
    return { value: this.env.readPermission() === 'granted' };
  }

  async checkExactNotificationSetting(): Promise<ExactAlarmStatus> {
    return { exact_alarm: 'granted' };
  }

  async changeExactNotificationSetting(): Promise<ExactAlarmStatus> {
    return { exact_alarm: 'granted' };
  }

  async schedule(options: ScheduleOptions): Promise<ScheduleResult> {
    const notifications = parseNotifications(options, this.env.now());
    for (const notification of notifications) {
      if (notification.actionTypeId && !this.actionTypes.has(notification.actionTypeId)) {
        throw new LocalNotificationsError(
          'INVALID_ACTION',
          `Action type "${notification.actionTypeId}" is not registered. Call registerActionTypes() first.`,
        );
      }
    }
    const replacing = new Set(notifications.map((notification) => notification.id));
    const kept = this.state.pending.filter((item) => !replacing.has(item.notification.id as number));
    const incomingPending = notifications.filter((notification) => notification.plan.kind !== 'immediate').length;
    if (kept.length + incomingPending > MAX_PENDING) {
      throw new LocalNotificationsError('INVALID_NOTIFICATION', `At most ${MAX_PENDING} notifications can be pending.`);
    }
    this.assertSupported();
    if (this.env.readPermission() !== 'granted') {
      throw new LocalNotificationsError('PERMISSION_DENIED', 'Notification permission is not granted.');
    }
    for (const id of replacing) {
      this.clearTimer(id);
      this.dismissDelivered(id);
    }
    const stored: PersistedNotification[] = notifications.map((notification) => ({
      notification: toPublicNotification(notification),
      plan: notification.plan,
    }));
    this.state.pending = kept.concat(stored.filter((item) => item.plan.kind !== 'immediate'));
    this.persist();
    for (const notification of notifications) {
      if (notification.plan.kind === 'immediate') {
        this.show(notification);
      } else {
        this.arm(notification.id, planNextMs(notification.plan));
      }
    }
    return { notifications: notifications.map((notification) => ({ id: notification.id })) };
  }

  async getPending(): Promise<PendingResult> {
    return {
      notifications: this.state.pending.map((item) => ({
        id: item.notification.id,
        title: item.notification.title,
        body: item.notification.body,
        extra: item.notification.extra,
        schedule: item.notification.schedule,
      })),
    };
  }

  async cancel(options: CancelOptions): Promise<void> {
    const ids = parseCancelIds(options);
    const idSet = new Set(ids);
    for (const id of ids) {
      this.clearTimer(id);
      this.dismissDelivered(id);
    }
    this.state.pending = this.state.pending.filter((item) => !idSet.has(item.notification.id as number));
    this.persist();
  }

  async getDeliveredNotifications(): Promise<DeliveredNotifications> {
    return {
      notifications: this.delivered.map((entry) => ({
        id: entry.notification.id,
        title: entry.notification.title,
        body: entry.notification.body,
        extra: entry.notification.extra,
        actionTypeId: entry.notification.actionTypeId,
        schedule: entry.notification.schedule,
        attachments: entry.notification.attachments,
      })),
    };
  }

  async removeDeliveredNotifications(delivered: DeliveredNotifications): Promise<void> {
    const ids = parseCancelIds(delivered);
    for (const id of ids) {
      this.dismissDelivered(id);
    }
  }

  async removeAllDeliveredNotifications(): Promise<void> {
    for (const entry of [...this.delivered]) {
      entry.handle.close();
    }
    this.delivered.length = 0;
  }

  async registerActionTypes(options: RegisterActionTypesOptions): Promise<void> {
    const types = parseActionTypes(options);
    this.actionTypes.clear();
    for (const type of types) {
      this.actionTypes.add(type.id);
    }
    this.state.actions = [...this.actionTypes];
    this.persist();
  }

  async createChannel(channel: Channel): Promise<void> {
    parseChannel(channel);
  }

  async deleteChannel(args: { id: string }): Promise<void> {
    parseChannel({ id: args?.id, name: 'unused' });
  }

  async listChannels(): Promise<ListChannelsResult> {
    return { channels: [] };
  }

  private assertSupported(): void {
    if (!this.env.notificationsSupported()) {
      throw new LocalNotificationsError('UNAVAILABLE', 'This browser does not support notifications.');
    }
  }

  private show(notification: ValidatedNotification | LocalNotificationSchema): void {
    const schema = 'plan' in notification ? toPublicNotification(notification) : notification;
    if (schema.badge != null) {
      this.env.setBadge?.(schema.badge);
    }
    const handle = this.env.show({
      title: schema.title,
      body: schema.body,
      tag: String(schema.id),
      silent: schema.sound === '',
    });
    const entry: DeliveredEntry = { notification: schema, handle };
    this.delivered.push(entry);
    while (this.delivered.length > MAX_DELIVERED) {
      this.delivered.shift()?.handle.close();
    }
    handle.onClick(() => {
      const action: ActionPerformed = { actionId: 'tap', notification: schema };
      this.notifyListeners('localNotificationActionPerformed', action);
    });
    handle.onClose(() => {
      const index = this.delivered.indexOf(entry);
      if (index >= 0) {
        this.delivered.splice(index, 1);
        this.notifyListeners('localNotificationActionPerformed', { actionId: 'dismiss', notification: schema });
      }
    });
    this.notifyListeners('localNotificationReceived', schema);
  }

  private arm(id: number, atMs: number | null): void {
    this.clearTimer(id);
    if (atMs == null) return;
    const delay = Math.max(0, atMs - this.env.now());
    const chunk = Math.min(delay, MAX_TIMER_MS);
    const handle = this.env.setTimer(() => {
      this.timers.delete(id);
      if (this.env.now() + 25 < atMs) {
        this.arm(id, atMs);
        return;
      }
      this.fire(id);
    }, chunk);
    this.timers.set(id, handle);
  }

  private fire(id: number): void {
    const index = this.state.pending.findIndex((item) => item.notification.id === id);
    if (index < 0) return;
    const item = this.state.pending[index];
    if (!item) return;
    try {
      this.show(item.notification);
    } catch {
      // A browser can refuse to construct a Notification after permission changes.
      // Drop or advance the record below so the timer does not spin.
    }
    let next: FirePlan | null = null;
    try {
      next = nextPlanAfter(item.plan, this.env.now());
    } catch {
      next = null;
    }
    if (!next) {
      this.state.pending.splice(index, 1);
      this.persist();
      return;
    }
    item.plan = next;
    this.persist();
    this.arm(id, planNextMs(next));
  }

  private dismissDelivered(id: number): void {
    for (let index = this.delivered.length - 1; index >= 0; index -= 1) {
      const entry = this.delivered[index];
      if (entry && entry.notification.id === id) {
        this.delivered.splice(index, 1);
        entry.handle.close();
      }
    }
  }

  private clearTimer(id: number): void {
    const handle = this.timers.get(id);
    if (handle != null) {
      this.env.clearTimer(handle);
      this.timers.delete(id);
    }
  }

  private persist(): void {
    try {
      this.env.storage.setItem(STORAGE_KEY, JSON.stringify(this.state));
    } catch {
      throw new LocalNotificationsError('UNAVAILABLE', 'Scheduled notifications could not be saved in this browser.');
    }
  }
}

function readState(storage: KeyValueStorage): PersistedState {
  try {
    const raw = storage.getItem(STORAGE_KEY);
    if (!raw) return { pending: [], actions: [] };
    const parsed = JSON.parse(raw) as Partial<PersistedState>;
    const pending = Array.isArray(parsed.pending) ? parsed.pending.filter(isPersistedNotification) : [];
    const actions = Array.isArray(parsed.actions) ? parsed.actions.filter((id): id is string => typeof id === 'string') : [];
    return { pending, actions };
  } catch {
    return { pending: [], actions: [] };
  }
}

function isPersistedNotification(value: unknown): value is PersistedNotification {
  if (value === null || typeof value !== 'object') return false;
  const record = value as Partial<PersistedNotification>;
  return (
    record.notification != null &&
    typeof record.notification.id === 'number' &&
    typeof record.notification.title === 'string' &&
    record.plan != null &&
    typeof record.plan === 'object' &&
    typeof (record.plan as FirePlan).kind === 'string'
  );
}

function browserEnvironment(): WebEnvironment {
  const storage: KeyValueStorage = typeof localStorage === 'undefined' ? memoryStorage() : localStorage;
  return {
    now: () => Date.now(),
    setTimer: (callback, delayMs) => setTimeout(callback, delayMs) as unknown as number,
    clearTimer: (id) => clearTimeout(id),
    storage,
    notificationsSupported: () => typeof Notification !== 'undefined',
    readPermission: () => {
      if (typeof Notification === 'undefined') return 'denied';
      if (Notification.permission === 'granted') return 'granted';
      if (Notification.permission === 'denied') return 'denied';
      return 'prompt';
    },
    requestPermission: async () => {
      const result = await Notification.requestPermission();
      if (result === 'granted' || result === 'denied') return result;
      return 'prompt';
    },
    show: ({ title, body, tag, silent }) => {
      let closedByPlugin = false;
      const notification = new Notification(title, { body, tag, silent });
      return {
        close: () => {
          closedByPlugin = true;
          notification.close();
        },
        onClick: (handler) => {
          notification.onclick = () => handler();
        },
        onClose: (handler) => {
          notification.onclose = () => {
            if (!closedByPlugin) handler();
          };
        },
      };
    },
    setBadge: (count) => {
      const badgeNavigator = navigator as Navigator & { setAppBadge?: (value: number) => Promise<void> };
      if (typeof badgeNavigator.setAppBadge === 'function') {
        void badgeNavigator.setAppBadge(count).catch(() => undefined);
      }
    },
  };
}

function memoryStorage(): KeyValueStorage {
  const values = new Map<string, string>();
  return {
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => {
      values.set(key, value);
    },
    removeItem: (key) => {
      values.delete(key);
    },
  };
}
