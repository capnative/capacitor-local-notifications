import type { PermissionState, PluginListenerHandle } from '@capacitor/core';

export interface LocalNotificationsPlugin {
  /**
   * Ask the system whether notifications can be displayed.
   * Android 13+ reports the `POST_NOTIFICATIONS` runtime permission.
   * Older Android versions report the app notification setting.
   */
  checkPermissions(): Promise<PermissionStatus>;

  /**
   * Request permission to display notifications.
   * On Android 12 and older this does not show a dialog; it returns the
   * current app notification setting.
   */
  requestPermissions(): Promise<PermissionStatus>;

  /**
   * Add one or more notifications. The call is atomic: if any item is
   * invalid, nothing is scheduled. Scheduling an id that is already pending
   * replaces that notification.
   */
  schedule(options: ScheduleOptions): Promise<ScheduleResult>;

  /** Pending notifications that have not fired yet. */
  getPending(): Promise<PendingResult>;

  /**
   * Cancel pending notifications and remove delivered notifications with the
   * same ids. Unknown ids are ignored.
   */
  cancel(options: CancelOptions): Promise<void>;

  /**
   * Cancel every pending notification scheduled by this plugin.
   * Delivered notifications are left in place.
   */
  cancelAll(): Promise<void>;

  /** Remove specific delivered notifications posted by this plugin. */
  removeDeliveredNotifications(delivered: DeliveredNotifications): Promise<void>;

  /**
   * Remove delivered notifications posted by this plugin.
   * Notifications posted by other libraries are left in place.
   */
  removeAllDeliveredNotifications(): Promise<void>;

  /**
   * Fired when this plugin posts a notification while the app process can
   * deliver JavaScript events. iOS does not emit this for notifications
   * delivered while the app is suspended. Android does not emit it when the
   * process is dead at delivery time.
   */
  addListener(
    eventName: 'localNotificationReceived',
    listenerFunc: (notification: LocalNotificationSchema) => void,
  ): Promise<PluginListenerHandle>;

  /**
   * Fired when the user taps a notification or clears it.
   * `actionId` is `tap` for the notification body and `dismiss` when the
   * notification is cleared. Dismiss is delivered on Android only while the
   * process is alive.
   */
  addListener(
    eventName: 'localNotificationActionPerformed',
    listenerFunc: (action: ActionPerformed) => void,
  ): Promise<PluginListenerHandle>;

  /** Remove all listeners registered by this plugin. */
  removeAllListeners(): Promise<void>;
}

export interface PermissionStatus {
  /** `prompt` when the user has not been asked, otherwise `granted` or `denied`. */
  display: PermissionState;
}

export interface ScheduleOptions {
  notifications: LocalNotificationSchema[];
}

export interface ScheduleResult {
  notifications: { id: number }[];
}

export interface CancelOptions {
  notifications: { id: number }[];
}

export interface PendingResult {
  notifications: PendingLocalNotificationSchema[];
}

export interface PendingLocalNotificationSchema {
  id: number;
  title: string;
  body: string;
  extra?: Record<string, unknown>;
  /**
   * The schedule that was stored. `at`, when present, is an ISO-8601 UTC string
   * even if a `Date` was passed to `schedule`.
   */
  schedule?: Schedule;
}

export interface DeliveredNotifications {
  notifications: DeliveredNotificationSchema[];
}

export interface DeliveredNotificationSchema {
  id: number;
  title: string;
  body: string;
  extra?: Record<string, unknown>;
  schedule?: Schedule;
}

export interface ActionPerformed {
  actionId: string;
  notification: LocalNotificationSchema;
}

/** A local notification. */
export interface LocalNotificationSchema {
  /**
   * 32-bit integer id, shared by scheduling, cancel, and the notification shade.
   * Scheduling the same id again replaces the pending notification.
   */
  id: number;
  /** Shown as the notification title. Required, 1–200 characters after trimming. */
  title: string;
  /** Shown under the title. Required string, may be empty, at most 2000 characters. */
  body: string;
  schedule?: Schedule;
  /**
   * iOS: a bundle file name, `default` for the system sound, or `''` for silence.
   * Android 7: a `res/raw` file name, `default`, or `''`.
   * Android 8+: ignored. The plugin config `sound` is used, or the system default.
   */
  sound?: string;
  /**
   * JSON object stored with the notification and returned to JavaScript.
   * Do not put secrets here. It is readable from the delivered notification
   * payload by the app and, on Android, by notification listeners on the device.
   * At most 4096 characters once encoded.
   */
  extra?: Record<string, unknown>;
}

export interface Schedule {
  /**
   * Fire at this instant. A `Date` or an ISO-8601 string (`2026-11-05T18:00:00.000Z`
   * or an offset form). Must be in the future and within 8 years.
   * On output this plugin returns an ISO-8601 UTC string.
   */
  at?: Date | string;
  /**
   * With `at`, repeat forever using the fixed delay between the call and `at`.
   * That delay must be at least 60 seconds. Not valid with `on` or `every`.
   */
  repeats?: boolean;
  /**
   * Fire on the next local time that matches these fields, then repeat.
   * Month is 1–12. Weekday is 1 (Sunday) through 7 (Saturday).
   * Omitted date fields match any value. Omitted `second` means 0.
   * Omitted `minute` means 0 unless only `second` is set, in which case every
   * minute matches. Omitted `hour` means 0 when a date field is set or when no
   * time field is set; when `minute` or `second` is set and no date field is set,
   * omitted `hour` matches every hour.
   */
  on?: ScheduleOn;
  /**
   * Repeat by a fixed unit. `count` is how many of those units make one interval
   * (every 2 days is `{ every: 'day', count: 2 }`). The first fire is one interval
   * after the call. The interval must be at least 60 seconds.
   * `second`, `minute`, and `hour` are fixed durations.
   * `day`, `week`, `two-weeks`, `month`, and `year` use local calendar addition on
   * Android. On iOS every unit is the fixed duration measured when the
   * notification is scheduled.
   */
  every?: ScheduleEvery;
  /** Multiplier for `every`. Defaults to 1. Maximum 1000. */
  count?: number;
}

export interface ScheduleOn {
  /** Full year, 1970 through 2100. */
  year?: number;
  /** 1 (January) through 12. */
  month?: number;
  /** Day of the month, 1 through 31. Impossible dates never fire. */
  day?: number;
  /** 1 Sunday through 7 Saturday. */
  weekday?: Weekday | number;
  /** 0 through 23, device local time. */
  hour?: number;
  /** 0 through 59. */
  minute?: number;
  /** 0 through 59. */
  second?: number;
}

export type ScheduleEvery = 'year' | 'month' | 'two-weeks' | 'week' | 'day' | 'hour' | 'minute' | 'second';

export enum Weekday {
  Sunday = 1,
  Monday = 2,
  Tuesday = 3,
  Wednesday = 4,
  Thursday = 5,
  Friday = 6,
  Saturday = 7,
}
