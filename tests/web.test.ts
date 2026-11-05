import { describe, expect, it, vi } from 'vitest';

import type { LocalNotificationsPlugin } from '../src/definitions';
import { LocalNotificationsError } from '../src/errors';
import { LocalNotificationsWeb, type KeyValueStorage, type ShownNotification, type WebEnvironment } from '../src/web';

interface FakeShown extends ShownNotification {
  click(): void;
  dismiss(): void;
}

class MemoryStorage implements KeyValueStorage {
  readonly values = new Map<string, string>();
  getItem(key: string): string | null {
    return this.values.get(key) ?? null;
  }
  setItem(key: string, value: string): void {
    this.values.set(key, value);
  }
  removeItem(key: string): void {
    this.values.delete(key);
  }
}

class FakeClock {
  now = Date.parse('2026-11-05T15:30:45.000Z');
  private nextId = 1;
  private timers: Array<{ id: number; at: number; fn: () => void }> = [];

  setTimer(fn: () => void, ms: number): number {
    const id = this.nextId;
    this.nextId += 1;
    this.timers.push({ id, at: this.now + ms, fn });
    return id;
  }

  clearTimer(id: number): void {
    this.timers = this.timers.filter((timer) => timer.id !== id);
  }

  advance(ms: number): void {
    const target = this.now + ms;
    for (;;) {
      const next = this.timers
        .filter((timer) => timer.at <= target)
        .sort((left, right) => left.at - right.at || left.id - right.id)[0];
      if (!next) break;
      this.timers = this.timers.filter((timer) => timer.id !== next.id);
      this.now = next.at;
      next.fn();
    }
    this.now = target;
  }
}

function createHarness(permission: 'granted' | 'denied' | 'prompt' = 'granted') {
  const clock = new FakeClock();
  const storage = new MemoryStorage();
  const shown: Array<{ title: string; body: string; tag: string; silent: boolean; handle: FakeShown }> = [];
  const env: WebEnvironment = {
    now: () => clock.now,
    setTimer: (fn, ms) => clock.setTimer(fn, ms),
    clearTimer: (id) => clock.clearTimer(id),
    storage,
    notificationsSupported: () => true,
    readPermission: () => permission,
    requestPermission: async () => permission,
    show: (input) => {
      let click: (() => void) | null = null;
      let close: (() => void) | null = null;
      let closed = false;
      const handle = {
        close: () => {
          closed = true;
        },
        onClick: (handler: () => void) => {
          click = handler;
        },
        onClose: (handler: () => void) => {
          close = handler;
        },
        click: () => click?.(),
        dismiss: () => {
          if (!closed) close?.();
        },
      };
      shown.push({ ...input, handle });
      return handle;
    },
  };
  const plugin = new LocalNotificationsWeb(env);
  const received: unknown[] = [];
  const actions: unknown[] = [];
  void plugin.addListener('localNotificationReceived', (event) => {
    received.push(event);
  });
  void plugin.addListener('localNotificationActionPerformed', (event) => {
    actions.push(event);
  });
  return {
    clock,
    storage,
    shown,
    received,
    actions,
    plugin: plugin as LocalNotificationsPlugin,
    click(index = 0) {
      shown[index]?.handle.click();
    },
    dismiss(index = 0) {
      shown[index]?.handle.dismiss();
    },
  };
}

describe('LocalNotificationsWeb', () => {
  it('implements the plugin interface', () => {
    const { plugin } = createHarness();
    const typed: LocalNotificationsPlugin = plugin;
    expect(typed).toBe(plugin);
  });

  it('shows an immediate notification and reports tap and dismiss', async () => {
    const harness = createHarness();
    await harness.plugin.schedule({
      notifications: [{ id: 4, title: 'Ping', body: 'Hello', extra: { path: '/inbox' } }],
    });
    expect(harness.shown.map((item) => item.title)).toEqual(['Ping']);
    expect(harness.received).toEqual([
      expect.objectContaining({ id: 4, title: 'Ping', extra: { path: '/inbox' } }),
    ]);
    expect(await harness.plugin.getPending()).toEqual({ notifications: [] });
    const delivered = await harness.plugin.getDeliveredNotifications();
    expect(delivered.notifications).toHaveLength(1);

    harness.click();
    expect(harness.actions[0]).toMatchObject({ actionId: 'tap', notification: { id: 4 } });
    harness.dismiss();
    expect(harness.actions[1]).toMatchObject({ actionId: 'dismiss' });
    expect((await harness.plugin.getDeliveredNotifications()).notifications).toHaveLength(0);
  });

  it('fires a dated notification when the timer elapses and keeps repeats on phase', async () => {
    const harness = createHarness();
    const at = new Date(harness.clock.now + 90_000);
    await harness.plugin.schedule({
      notifications: [{ id: 8, title: 'Soon', body: 'Body', schedule: { at, repeats: true } }],
    });
    expect(harness.shown).toHaveLength(0);
    const pending = await harness.plugin.getPending();
    expect(pending.notifications[0]?.schedule?.at).toBe(at.toISOString());

    harness.clock.advance(90_000);
    expect(harness.shown).toHaveLength(1);
    harness.clock.advance(90_000);
    expect(harness.shown).toHaveLength(2);
    expect(harness.received).toHaveLength(2);
  });

  it('cancels a pending timer and the delivered notification with that id', async () => {
    const harness = createHarness();
    await harness.plugin.schedule({
      notifications: [
        { id: 1, title: 'Now', body: 'Body' },
        { id: 2, title: 'Later', body: 'Body', schedule: { at: new Date(harness.clock.now + 60_000) } },
      ],
    });
    await harness.plugin.cancel({ notifications: [{ id: 1 }, { id: 2 }, { id: 99 }] });
    harness.clock.advance(120_000);
    expect(harness.shown).toHaveLength(1);
    expect((await harness.plugin.getDeliveredNotifications()).notifications).toHaveLength(0);
    expect((await harness.plugin.getPending()).notifications).toHaveLength(0);
  });

  it('rejects scheduling when permission is not granted and does not persist it', async () => {
    const harness = createHarness('prompt');
    await expect(
      harness.plugin.schedule({ notifications: [{ id: 1, title: 'A', body: 'B' }] }),
    ).rejects.toMatchObject({ code: 'PERMISSION_DENIED' });
    expect(harness.storage.values.size).toBe(0);
  });

  it('requires a registered action type', async () => {
    const harness = createHarness();
    await expect(
      harness.plugin.schedule({
        notifications: [{ id: 1, title: 'A', body: 'B', actionTypeId: 'mail' }],
      }),
    ).rejects.toBeInstanceOf(LocalNotificationsError);
    await harness.plugin.registerActionTypes({
      types: [{ id: 'mail', actions: [{ id: 'read', title: 'Read', foreground: true }] }],
    });
    await harness.plugin.schedule({
      notifications: [{ id: 1, title: 'A', body: 'B', actionTypeId: 'mail' }],
    });
    expect(harness.shown).toHaveLength(1);
  });

  it('restores a future notification from storage and catch-up fires once', async () => {
    const first = createHarness();
    const at = first.clock.now + 5 * 60_000;
    await first.plugin.schedule({
      notifications: [{ id: 3, title: 'Saved', body: 'Body', schedule: { every: 'minute', count: 5 } }],
    });
    const saved = first.storage.values.get('capnative.local-notifications.v1');
    expect(saved).toContain('"Saved"');

    const secondClock = new FakeClock();
    secondClock.now = at + 60_000;
    const storage = first.storage;
    const shown: string[] = [];
    const env: WebEnvironment = {
      now: () => secondClock.now,
      setTimer: (fn, ms) => secondClock.setTimer(fn, ms),
      clearTimer: (id) => secondClock.clearTimer(id),
      storage,
      notificationsSupported: () => true,
      readPermission: () => 'granted',
      requestPermission: async () => 'granted',
      show: (input) => {
        shown.push(input.title);
        return { close: () => undefined, onClick: () => undefined, onClose: () => undefined };
      },
    };
    new LocalNotificationsWeb(env);
    secondClock.advance(0);
    expect(shown).toEqual(['Saved']);
    secondClock.advance(10 * 60_000);
    expect(shown.length).toBeGreaterThan(1);
    expect(shown.length).toBeLessThan(5);
  });

  it('keeps channel and exact-alarm calls from pretending to change the browser', async () => {
    const harness = createHarness();
    await harness.plugin.createChannel({ id: 'mail', name: 'Mail', importance: 4 });
    await harness.plugin.deleteChannel({ id: 'mail' });
    expect(await harness.plugin.listChannels()).toEqual({ channels: [] });
    expect(await harness.plugin.checkExactNotificationSetting()).toEqual({ exact_alarm: 'granted' });
    expect(await harness.plugin.changeExactNotificationSetting()).toEqual({ exact_alarm: 'granted' });
    expect(await harness.plugin.checkPermissions()).toEqual({ display: 'granted' });
  });

  it('reports that notifications are unavailable when the browser has no Notification API', async () => {
    const clock = new FakeClock();
    const plugin = new LocalNotificationsWeb({
      now: () => clock.now,
      setTimer: (fn, ms) => clock.setTimer(fn, ms),
      clearTimer: (id) => clock.clearTimer(id),
      storage: new MemoryStorage(),
      notificationsSupported: () => false,
      readPermission: () => 'denied',
      requestPermission: async () => 'denied',
      show: () => {
        throw new Error('should not show');
      },
    });
    await expect(plugin.checkPermissions()).rejects.toMatchObject({ code: 'UNAVAILABLE' });
    expect(await plugin.areEnabled()).toEqual({ value: false });
  });

  it('does not emit dismiss when a delivered notification is removed by the app', async () => {
    const harness = createHarness();
    const listener = vi.fn();
    await harness.plugin.addListener('localNotificationActionPerformed', listener);
    await harness.plugin.schedule({ notifications: [{ id: 1, title: 'A', body: 'B' }] });
    await harness.plugin.removeDeliveredNotifications({ notifications: [{ id: 1, title: 'A', body: 'B' }] });
    expect(listener).not.toHaveBeenCalled();
  });
});
