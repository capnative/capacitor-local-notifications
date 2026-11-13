# @capnative/capacitor-local-notifications

Local notifications for Capacitor 8. iOS uses `UNUserNotificationCenter`. Android uses `AlarmManager`.

## Compatibility

| Plugin version | Capacitor compatibility | Maintained |
| -------------- | ----------------------- | ---------- |
| v8.\*.\*       | v8.\*.\*                | ✅         |

## Supported platforms

| Platform | Support | Minimum version            |
| -------- | ------- | -------------------------- |
| iOS      | ✅      | iOS 15                     |
| Android  | ✅      | API level 24 (Android 7.0) |

## Install

```bash
npm install @capnative/capacitor-local-notifications
npx cap sync
```

## Basic usage

```typescript
import { LocalNotifications, Weekday } from '@capnative/capacitor-local-notifications';

const permission = await LocalNotifications.requestPermissions();
if (permission.display !== 'granted') {
  throw new Error('Notifications are blocked.');
}

await LocalNotifications.schedule({
  notifications: [
    { id: 1, title: 'Now', body: 'Shown immediately' },
    {
      id: 2,
      title: 'Later',
      body: 'One shot',
      schedule: { at: new Date(Date.now() + 60 * 60 * 1000) },
    },
    {
      id: 3,
      title: 'Weekdays',
      body: '09:00 local time',
      schedule: { on: { weekday: Weekday.Monday, hour: 9, minute: 0 } },
    },
  ],
});

await LocalNotifications.addListener('localNotificationReceived', (notification) => {
  console.log(notification);
});

await LocalNotifications.addListener('localNotificationActionPerformed', (action) => {
  console.log(action);
});
```

## API

<docgen-index>

* [`checkPermissions()`](#checkpermissions)
* [`requestPermissions()`](#requestpermissions)
* [`schedule(...)`](#schedule)
* [`getPending()`](#getpending)
* [`cancel(...)`](#cancel)
* [`cancelAll()`](#cancelall)
* [`removeDeliveredNotifications(...)`](#removedeliverednotifications)
* [`removeAllDeliveredNotifications()`](#removealldeliverednotifications)
* [`addListener('localNotificationReceived', ...)`](#addlistenerlocalnotificationreceived-)
* [`addListener('localNotificationActionPerformed', ...)`](#addlistenerlocalnotificationactionperformed-)
* [`removeAllListeners()`](#removealllisteners)
* [Interfaces](#interfaces)
* [Type Aliases](#type-aliases)
* [Enums](#enums)

</docgen-index>

<docgen-api>
<!--Update the source file JSDoc comments and rerun docgen to update the docs below-->

### checkPermissions()

```typescript
checkPermissions() => Promise<PermissionStatus>
```

Ask the system whether notifications can be displayed.
Android 13+ reports the `POST_NOTIFICATIONS` runtime permission.
Older Android versions report the app notification setting.

**Returns:** <code>Promise&lt;<a href="#permissionstatus">PermissionStatus</a>&gt;</code>

--------------------


### requestPermissions()

```typescript
requestPermissions() => Promise<PermissionStatus>
```

Request permission to display notifications.
On Android 12 and older this does not show a dialog; it returns the
current app notification setting.

**Returns:** <code>Promise&lt;<a href="#permissionstatus">PermissionStatus</a>&gt;</code>

--------------------


### schedule(...)

```typescript
schedule(options: ScheduleOptions) => Promise<ScheduleResult>
```

Add one or more notifications. The call is atomic: if any item is
invalid, nothing is scheduled. Scheduling an id that is already pending
replaces that notification.

| Param         | Type                                                        |
| ------------- | ----------------------------------------------------------- |
| **`options`** | <code><a href="#scheduleoptions">ScheduleOptions</a></code> |

**Returns:** <code>Promise&lt;<a href="#scheduleresult">ScheduleResult</a>&gt;</code>

--------------------


### getPending()

```typescript
getPending() => Promise<PendingResult>
```

Pending notifications that have not fired yet.

**Returns:** <code>Promise&lt;<a href="#pendingresult">PendingResult</a>&gt;</code>

--------------------


### cancel(...)

```typescript
cancel(options: CancelOptions) => Promise<void>
```

Cancel pending notifications and remove delivered notifications with the
same ids. Unknown ids are ignored.

| Param         | Type                                                    |
| ------------- | ------------------------------------------------------- |
| **`options`** | <code><a href="#canceloptions">CancelOptions</a></code> |

--------------------


### cancelAll()

```typescript
cancelAll() => Promise<void>
```

Cancel every pending notification scheduled by this plugin.
Delivered notifications are left in place.

--------------------


### removeDeliveredNotifications(...)

```typescript
removeDeliveredNotifications(delivered: DeliveredNotifications) => Promise<void>
```

Remove specific delivered notifications posted by this plugin.

| Param           | Type                                                                      |
| --------------- | ------------------------------------------------------------------------- |
| **`delivered`** | <code><a href="#deliverednotifications">DeliveredNotifications</a></code> |

--------------------


### removeAllDeliveredNotifications()

```typescript
removeAllDeliveredNotifications() => Promise<void>
```

Remove delivered notifications posted by this plugin.
Notifications posted by other libraries are left in place.

--------------------


### addListener('localNotificationReceived', ...)

```typescript
addListener(eventName: 'localNotificationReceived', listenerFunc: (notification: LocalNotificationSchema) => void) => Promise<PluginListenerHandle>
```

Fired when this plugin posts a notification while the app process can
deliver JavaScript events. iOS does not emit this for notifications
delivered while the app is suspended. Android does not emit it when the
process is dead at delivery time.

| Param              | Type                                                                                                   |
| ------------------ | ------------------------------------------------------------------------------------------------------ |
| **`eventName`**    | <code>'localNotificationReceived'</code>                                                               |
| **`listenerFunc`** | <code>(notification: <a href="#localnotificationschema">LocalNotificationSchema</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('localNotificationActionPerformed', ...)

```typescript
addListener(eventName: 'localNotificationActionPerformed', listenerFunc: (action: ActionPerformed) => void) => Promise<PluginListenerHandle>
```

Fired when the user taps a notification or clears it.
`actionId` is `tap` for the notification body and `dismiss` when the
notification is cleared. Dismiss is delivered on Android only while the
process is alive.

| Param              | Type                                                                             |
| ------------------ | -------------------------------------------------------------------------------- |
| **`eventName`**    | <code>'localNotificationActionPerformed'</code>                                  |
| **`listenerFunc`** | <code>(action: <a href="#actionperformed">ActionPerformed</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### removeAllListeners()

```typescript
removeAllListeners() => Promise<void>
```

Remove all listeners registered by this plugin.

--------------------


### Interfaces


#### PermissionStatus

| Prop          | Type                                                        | Description                                                                 |
| ------------- | ----------------------------------------------------------- | --------------------------------------------------------------------------- |
| **`display`** | <code><a href="#permissionstate">PermissionState</a></code> | `prompt` when the user has not been asked, otherwise `granted` or `denied`. |


#### ScheduleResult

| Prop                | Type                           |
| ------------------- | ------------------------------ |
| **`notifications`** | <code>{ id: number; }[]</code> |


#### ScheduleOptions

| Prop                | Type                                   |
| ------------------- | -------------------------------------- |
| **`notifications`** | <code>LocalNotificationSchema[]</code> |


#### LocalNotificationSchema

A local notification.
Platform-specific fields are ignored where the platform cannot apply them.

| Prop                    | Type                                                             | Description                                                                                                                                                                                                                                                     |
| ----------------------- | ---------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`id`**                | <code>number</code>                                              | 32-bit integer id, shared by scheduling, cancel, and the notification shade. Scheduling the same id again replaces the pending notification.                                                                                                                    |
| **`title`**             | <code>string</code>                                              | Shown as the notification title. Required, 1–200 characters after trimming.                                                                                                                                                                                     |
| **`body`**              | <code>string</code>                                              | Shown under the title. Required string, may be empty, at most 2000 characters.                                                                                                                                                                                  |
| **`largeBody`**         | <code>string</code>                                              | Android big-text style. Ignored when `inboxList` is set.                                                                                                                                                                                                        |
| **`summaryText`**       | <code>string</code>                                              | Android summary line for big-text and inbox styles.                                                                                                                                                                                                             |
| **`schedule`**          | <code><a href="#schedule">Schedule</a></code>                    |                                                                                                                                                                                                                                                                 |
| **`sound`**             | <code>string</code>                                              | iOS: a bundle file name, `default` for the system sound, or `''` for silence. Android 7: a `res/raw` file name, `default`, or `''`. Android 8+: ignored. The plugin config `sound` is used, or the system default. |
| **`smallIcon`**         | <code>string</code>                                              | Android drawable resource name. Overrides the config `smallIcon`.                                                                                                                                                                                               |
| **`largeIcon`**         | <code>string</code>                                              | Android large icon. Bitmap drawable resource name (`png` or `webp`, not a vector).                                                                                                                                                                              |
| **`iconColor`**         | <code>string</code>                                              | Android icon tint. `#RGB`, `#RRGGBB`, or `#AARRGGBB`.                                                                                                                                                                                                           |
| **`attachments`**       | <code>Attachment[]</code>                                        | iOS attachments. See <a href="#attachment">`Attachment`</a>.                                                                                                                                                                                                    |
| **`extra`**             | <code><a href="#record">Record</a>&lt;string, unknown&gt;</code> | JSON object stored with the notification and returned to JavaScript. Do not put secrets here. It is readable from the delivered notification payload by the app and, on Android, by notification listeners on the device. At most 4096 characters once encoded. |
| **`threadIdentifier`**  | <code>string</code>                                              | iOS `threadIdentifier`.                                                                                                                                                                                                                                         |
| **`summaryArgument`**   | <code>string</code>                                              | iOS summary argument. Ignored on iOS 15 and newer.                                                                                                                                                                                                              |
| **`relevanceScore`**    | <code>number</code>                                              | iOS relevance score from 0 to 1.                                                                                                                                                                                                                                |
| **`interruptionLevel`** | <code><a href="#interruptionlevel">InterruptionLevel</a></code>  | iOS interruption level. `critical` and `timeSensitive` need Apple entitlements.                                                                                                                                                                                 |
| **`group`**             | <code>string</code>                                              | Android notification group key.                                                                                                                                                                                                                                 |
| **`groupSummary`**      | <code>boolean</code>                                             | Android group summary. Requires `group`.                                                                                                                                                                                                                        |
| **`ongoing`**           | <code>boolean</code>                                             | Android: the notification cannot be swiped away. Forces `autoCancel` off.                                                                                                                                                                                       |
| **`autoCancel`**        | <code>boolean</code>                                             | Android: remove the notification when the user taps it. Defaults to true.                                                                                                                                                                                       |
| **`inboxList`**         | <code>string[]</code>                                            | Android inbox lines. At most five.                                                                                                                                                                                                                              |
| **`silent`**            | <code>boolean</code>                                             | iOS: when true, a notification delivered in the foreground is not presented. The `localNotificationReceived` event still fires. Other platforms ignore this.                                                                                                    |
| **`badge`**             | <code>number</code>                                              | iOS application icon badge. Android launcher badges are not set.                                                                                                                                                                                                |


#### Schedule

| Prop                 | Type                                                    | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| -------------------- | ------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **`at`**             | <code>string \| Date</code>         | Fire at this instant. A `Date` or an ISO-8601 string (`2026-11-05T18:00:00.000Z` or an offset form). Must be in the future and within 8 years. On output this plugin returns an ISO-8601 UTC string.                                                                                                                                                                                                                                                                   |
| **`repeats`**        | <code>boolean</code>                                    | With `at`, repeat forever using the fixed delay between the call and `at`. That delay must be at least 60 seconds. Not valid with `on` or `every`.                                                                                                                                                                                                                                                                                                                                         |
| **`allowWhileIdle`** | <code>boolean</code>                                    | Android: allow the alarm to fire in Doze maintenance windows. Exact Doze alarms are still limited by the system (roughly one every 9 minutes per app). Other platforms ignore this flag.                                                                                                                                                                                                                                                                                                   |
| **`on`**             | <code><a href="#scheduleon">ScheduleOn</a></code>       | Fire on the next local time that matches these fields, then repeat. Month is 1–12. <a href="#weekday">Weekday</a> is 1 (Sunday) through 7 (Saturday). Omitted date fields match any value. Omitted `second` means 0. Omitted `minute` means 0 unless only `second` is set, in which case every minute matches. Omitted `hour` means 0 when a date field is set or when no time field is set; when `minute` or `second` is set and no date field is set, omitted `hour` matches every hour. |
| **`every`**          | <code><a href="#scheduleevery">ScheduleEvery</a></code> | Repeat by a fixed unit. `count` is how many of those units make one interval (every 2 days is `{ every: 'day', count: 2 }`). The first fire is one interval after the call. The interval must be at least 60 seconds. `second`, `minute`, and `hour` are fixed durations. `day`, `week`, `two-weeks`, `month`, and `year` use local calendar addition on Android. On iOS every unit is the fixed duration measured when the notification is scheduled.                                     |
| **`count`**          | <code>number</code>                                     | Multiplier for `every`. Defaults to 1. Maximum 1000.                                                                                                                                                                                                                                                                                                                                                                                                                                       |

#### ScheduleOn

| Prop          | Type                | Description                                                  |
| ------------- | ------------------- | ------------------------------------------------------------ |
| **`year`**    | <code>number</code> | Full year, 1970 through 2100.                                |
| **`month`**   | <code>number</code> | 1 (January) through 12.                                      |
| **`day`**     | <code>number</code> | Day of the month, 1 through 31. Impossible dates never fire. |
| **`weekday`** | <code>number</code> | 1 Sunday through 7 Saturday.                                 |
| **`hour`**    | <code>number</code> | 0 through 23, device local time.                             |
| **`minute`**  | <code>number</code> | 0 through 59.                                                |
| **`second`**  | <code>number</code> | 0 through 59.                                                |


#### Attachment

| Prop          | Type                                                            | Description                                                                                                                                                                                     |
| ------------- | --------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`id`**      | <code>string</code>                                             |                                                                                                                                                                                                 |
| **`url`**     | <code>string</code>                                             | iOS only. A bundle file name (`photo.png`) or a `file://` URL inside the app container, bundle, or temp directory. Remote URLs are rejected. Android accepts the field and does not display it. |
| **`options`** | <code><a href="#attachmentoptions">AttachmentOptions</a></code> |                                                                                                                                                                                                 |


#### AttachmentOptions

| Prop                                                             | Type                                                      | Description                                                                                           |
| ---------------------------------------------------------------- | --------------------------------------------------------- | ----------------------------------------------------------------------------------------------------- |
| **`iosUNNotificationAttachmentOptionsTypeHintKey`**              | <code>string</code>                                       | iOS `UNNotificationAttachmentOptionsTypeHintKey`. A UTI such as `public.png`.                         |
| **`iosUNNotificationAttachmentOptionsThumbnailHiddenKey`**       | <code>boolean</code>                                      | iOS `UNNotificationAttachmentOptionsThumbnailHiddenKey`.                                              |
| **`iosUNNotificationAttachmentOptionsThumbnailClippingRectKey`** | <code><a href="#attachmentrect">AttachmentRect</a></code> | iOS `UNNotificationAttachmentOptionsThumbnailClippingRectKey`, as `{ x, y, width, height }` in 0...1. |
| **`iosUNNotificationAttachmentOptionsThumbnailTimeKey`**         | <code>number</code>                                       | iOS `UNNotificationAttachmentOptionsThumbnailTimeKey`, in seconds.                                    |


#### AttachmentRect

| Prop         | Type                |
| ------------ | ------------------- |
| **`x`**      | <code>number</code> |
| **`y`**      | <code>number</code> |
| **`width`**  | <code>number</code> |
| **`height`** | <code>number</code> |


#### PendingResult

| Prop                | Type                                          |
| ------------------- | --------------------------------------------- |
| **`notifications`** | <code>PendingLocalNotificationSchema[]</code> |


#### PendingLocalNotificationSchema

| Prop           | Type                                                             | Description                                                                                                                                |
| -------------- | ---------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ |
| **`id`**       | <code>number</code>                                              |                                                                                                                                            |
| **`title`**    | <code>string</code>                                              |                                                                                                                                            |
| **`body`**     | <code>string</code>                                              |                                                                                                                                            |
| **`extra`**    | <code><a href="#record">Record</a>&lt;string, unknown&gt;</code> |                                                                                                                                            |
| **`schedule`** | <code><a href="#schedule">Schedule</a></code>                    | The schedule that was stored. `at`, when present, is an ISO-8601 UTC string even if a `Date` was passed to `schedule`. |


#### CancelOptions

| Prop                | Type                           |
| ------------------- | ------------------------------ |
| **`notifications`** | <code>{ id: number; }[]</code> |


#### DeliveredNotifications

| Prop                | Type                                       |
| ------------------- | ------------------------------------------ |
| **`notifications`** | <code>DeliveredNotificationSchema[]</code> |


#### DeliveredNotificationSchema

| Prop               | Type                                                             | Description                                                               |
| ------------------ | ---------------------------------------------------------------- | ------------------------------------------------------------------------- |
| **`id`**           | <code>number</code>                                              |                                                                           |
| **`title`**        | <code>string</code>                                              |                                                                           |
| **`body`**         | <code>string</code>                                              |                                                                           |
| **`tag`**          | <code>string</code>                                              | Android notification tag when one was set. This plugin does not set tags. |
| **`group`**        | <code>string</code>                                              |                                                                           |
| **`groupSummary`** | <code>boolean</code>                                             |                                                                           |
| **`extra`**        | <code><a href="#record">Record</a>&lt;string, unknown&gt;</code> |                                                                           |
| **`schedule`**     | <code><a href="#schedule">Schedule</a></code>                    |                                                                           |
| **`attachments`**  | <code>Attachment[]</code>                                        |                                                                           |


#### PluginListenerHandle

| Prop         | Type                                      |
| ------------ | ----------------------------------------- |
| **`remove`** | <code>() =&gt; Promise&lt;void&gt;</code> |


#### ActionPerformed

| Prop               | Type                                                                        | Description                                                            |
| ------------------ | --------------------------------------------------------------------------- | ---------------------------------------------------------------------- |
| **`actionId`**     | <code>string</code>                                                         | `tap` when the notification body is opened, `dismiss` when it is cleared. |
| **`notification`** | <code><a href="#localnotificationschema">LocalNotificationSchema</a></code> |                                                                            |


### Type Aliases


#### PermissionState

<code>'prompt' | 'prompt-with-rationale' | 'granted' | 'denied'</code>


#### ScheduleEvery

<code>'year' | 'month' | 'two-weeks' | 'week' | 'day' | 'hour' | 'minute' | 'second'</code>


#### Record

Construct a type with a set of properties K of type T

<code>{
 [P in K]: T;
 }</code>


#### InterruptionLevel

iOS interruption level.
`critical` requires the Critical Alerts entitlement.
`timeSensitive` requires the Time Sensitive Notifications capability and can
still be blocked by a Focus mode.

<code>'active' | 'critical' | 'passive' | 'timeSensitive'</code>


### Enums


#### Weekday

| Members         | Value          |
| --------------- | -------------- |
| **`Sunday`**    | <code>1</code> |
| **`Monday`**    | <code>2</code> |
| **`Tuesday`**   | <code>3</code> |
| **`Wednesday`** | <code>4</code> |
| **`Thursday`**  | <code>5</code> |
| **`Friday`**    | <code>6</code> |
| **`Saturday`**  | <code>7</code> |

</docgen-api>

## Scheduling

Use one of `at`, `on`, or `every`. Combining them is rejected. `repeats` is valid only with `at`. `count` is valid only with `every`.

`at` is an absolute instant: a `Date` or an ISO-8601 string such as `2026-11-05T18:00:00.000Z` or `2026-11-05T20:00:00+02:00`. It must be in the future and within 8 years. `getPending()` returns `at` as an ISO-8601 UTC string.

`at` plus `repeats: true` repeats forever by the fixed delay between the call and `at`. That delay must be at least 60 seconds. The phase stays anchored to the original `at`, including after a reboot.

`every` repeats by `count` units (default 1, maximum 1000). `{ every: 'day', count: 2 }` means every two days, not "fire twice". The first fire is one interval after the call, and the interval must be at least 60 seconds. `every: 'second'` therefore needs `count >= 60`.

- `second`, `minute`, and `hour` are fixed durations on every platform.
- `day`, `week`, `two-weeks`, `month`, and `year` use local calendar addition on Android.
- On iOS every `every` unit is the fixed duration measured when the notification is scheduled. A later daylight-saving change does not reshape it. Month and year repeats on iOS do not track calendar month lengths after the first interval.

Adding a month follows JavaScript `Date` overflow: 31 January plus one month is early March in a non-leap year, not 28 February. iOS measures that first gap and then keeps it.

`on` is a local-time match that repeats until cancelled. Month is **1–12**. Weekday is 1 (Sunday) through 7 (Saturday), also exported as `Weekday`.

Omitted date fields match any value. Omitted `second` means 0. Omitted `minute` means 0 unless only `second` is set, in which case every minute matches. Omitted `hour` means 0 when a date field is set or when no time field is set. When `minute` or `second` is set and no date field is set, an omitted hour matches every hour.

Examples:

| `on` | Meaning |
| --- | --- |
| `{ hour: 9 }` | Every day at 09:00:00 local |
| `{ minute: 15 }` | Every hour at :15:00 |
| `{ second: 30 }` | Every minute at second 30 |
| `{ weekday: Weekday.Monday, hour: 9, minute: 0 }` | Mondays at 09:00 |
| `{ day: 31, hour: 10 }` | 10:00 on each month that has a 31st |
| `{ year: 2027, month: 2, day: 31 }` | Rejected. No such local time in the next 8 years |

A local time that does not exist because of daylight saving is skipped. The next real match is used.

Pending notifications are capped at 100. One `schedule()` call accepts at most 50. A one-shot that was due while the device was off is delivered once after boot (about 5 seconds later). Repeating notifications do not replay every missed occurrence; one catch-up is shown, then the schedule continues on its next future time.

## Platform limits

### Exact alarms

Android 12 and newer can block exact alarms. `schedule()` does not fail when exact alarms are denied; it uses an inexact alarm. Turning the setting on later re-arms stored alarms. Inexact alarms can be minutes late, and Doze can defer them further. `allowWhileIdle` uses `setExactAndAllowWhileIdle` or `setAndAllowWhileIdle`, which is still subject to the system's idle quota.

### Foreground

Android shows a notification whether or not the app is open. iOS uses `presentationOptions`, unless `silent` is true.

### Reboot and process death

Android stores the pending payload in private app preferences and reschedules on `BOOT_COMPLETED`. Alarms are not direct-boot aware: they are restored after the user unlocks the device. iOS persists `UNNotificationRequest`s itself.

### Delivered notifications

`removeAllDeliveredNotifications()` removes notifications this plugin posted. It does not clear notifications posted by another plugin. iOS notification ids still share one center, so two plugins should not reuse the same id.

## Migration from `@capacitor/local-notifications`

The method names and most fields match that plugin so existing calls are recognizable. These behaviors differ on purpose:

- `schedule.on.month` is 1–12 on Android as well as iOS. The other plugin passes the number straight to Android's 0-based `Calendar.MONTH`.
- `count` multiplies the `every` interval. That matches the other plugin's implementation. Its docs have also described `count` as a maximum number of deliveries; this plugin does not do that.
- Only one of `at`, `on`, and `every` may be set. `repeats` only belongs with `at`.
- Repeats shorter than 60 seconds are rejected, including `every: 'second'` with `count` below 60. iOS rejects a repeating time-interval trigger under 60 seconds.
- `at` in the past is rejected. Show it immediately by omitting `schedule`.
- `getDeliveredNotifications()` is not included. `removeDeliveredNotifications()` and `removeAllDeliveredNotifications()` still remove notifications this plugin posted.
- `removeAllDeliveredNotifications()` is limited to notifications this plugin posted.
- `registerActionTypes()`, `actionTypeId`, and notification action buttons are not included. `localNotificationActionPerformed` still reports `tap` and `dismiss`.
- `createChannel()`, `deleteChannel()`, `listChannels()`, and `channelId` are not included.
- Per-notification `sound` is ignored on Android 8+. Set `sound` in the plugin config, or the system default is used.
- `areEnabled()` is not included. Use `checkPermissions()` and treat `display === 'granted'` as enabled.
- `checkExactNotificationSetting()` and `changeExactNotificationSetting()` are not included. When Android denies exact alarms, `schedule()` uses an inexact alarm.
- Deprecated type aliases from the other package are not re-exported.
- `extra` is size-limited and should not contain secrets.

## Development

```bash
npm run typecheck
npm test
npm run build
npm run verify:android
```

`npm test` runs the TypeScript schedule tests in UTC. `verify:android` compiles the Android library and runs the JVM unit tests (`ScheduleCalculator` and `NotificationParser`). That is a real Android compile plus local unit tests. It does not install an app or run instrumented tests on a device or emulator.

iOS is Swift Package Manager and CocoaPods source. `xcodebuild` or `swift test` has to be run on macOS; this repository's Linux CI does not compile the iOS target. The Swift schedule tests live in `ios/Tests` for that macOS run.

```bash
cd android && ./gradlew test
```

needs JDK 21 and an Android SDK with platform 36. `ANDROID_HOME` must point at that SDK.

## License

MIT © Mario Shtika
