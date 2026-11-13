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

| Prop          | Type                                                        | Platform      | Description                                                                 |
| ------------- | ----------------------------------------------------------- | ------------- | --------------------------------------------------------------------------- |
| **`display`** | <code><a href="#permissionstate">PermissionState</a></code> | iOS, Android  | `prompt` when the user has not been asked, otherwise `granted` or `denied`. |


#### ScheduleResult

| Prop                | Type                           | Platform     |
| ------------------- | ------------------------------ | ------------ |
| **`notifications`** | <code>{ id: number; }[]</code> | iOS, Android |


#### ScheduleOptions

| Prop                | Type                                   | Platform     |
| ------------------- | -------------------------------------- | ------------ |
| **`notifications`** | <code>LocalNotificationSchema[]</code> | iOS, Android |


#### LocalNotificationSchema

A local notification.
Platform-specific fields are ignored where the platform cannot apply them.

| Prop                    | Type                                                             | Platform     | Description                                                                                                                                                                                                                                                     |
| ----------------------- | ---------------------------------------------------------------- | ------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`id`**                | <code>number</code>                                              | iOS, Android | 32-bit integer id, shared by scheduling, cancel, and the notification shade. Scheduling the same id again replaces the pending notification.                                                                                                                    |
| **`title`**             | <code>string</code>                                              | iOS, Android | Shown as the notification title. Required, 1–200 characters after trimming.                                                                                                                                                                                     |
| **`body`**              | <code>string</code>                                              | iOS, Android | Shown under the title. Required string, may be empty, at most 2000 characters.                                                                                                                                                                                  |
| **`largeBody`**         | <code>string</code>                                              | Android      | Big-text style. Ignored when `inboxList` is set.                                                                                                                                                                                                                |
| **`summaryText`**       | <code>string</code>                                              | Android      | Summary line for big-text and inbox styles.                                                                                                                                                                                                                     |
| **`schedule`**          | <code><a href="#schedule">Schedule</a></code>                    | iOS, Android |                                                                                                                                                                                                                                                                 |
| **`sound`**             | <code>string</code>                                              | iOS, Android | iOS: a bundle file name, `default` for the system sound, or `''` for silence. Android 7: a `res/raw` file name, `default`, or `''`. Android 8+: ignored. The plugin config `sound` is used, or the system default.                                               |
| **`smallIcon`**         | <code>string</code>                                              | Android      | Drawable resource name. Overrides the config `smallIcon`.                                                                                                                                                                                                       |
| **`largeIcon`**         | <code>string</code>                                              | Android      | Large icon. Bitmap drawable resource name (`png` or `webp`, not a vector).                                                                                                                                                                                      |
| **`iconColor`**         | <code>string</code>                                              | Android      | Icon tint. `#RGB`, `#RRGGBB`, or `#AARRGGBB`.                                                                                                                                                                                                                   |
| **`extra`**             | <code><a href="#record">Record</a>&lt;string, unknown&gt;</code> | iOS, Android | JSON object stored with the notification and returned to JavaScript. Do not put secrets here. It is readable from the delivered notification payload by the app and, on Android, by notification listeners on the device. At most 4096 characters once encoded. |
| **`threadIdentifier`**  | <code>string</code>                                              | iOS          | `threadIdentifier`.                                                                                                                                                                                                                                             |
| **`summaryArgument`**   | <code>string</code>                                              | iOS          | Summary argument. Ignored on iOS 15 and newer.                                                                                                                                                                                                                  |
| **`relevanceScore`**    | <code>number</code>                                              | iOS          | Relevance score from 0 to 1.                                                                                                                                                                                                                                    |
| **`interruptionLevel`** | <code><a href="#interruptionlevel">InterruptionLevel</a></code>  | iOS          | Interruption level. `critical` and `timeSensitive` need Apple entitlements.                                                                                                                                                                                     |
| **`group`**             | <code>string</code>                                              | Android      | Notification group key.                                                                                                                                                                                                                                         |
| **`groupSummary`**      | <code>boolean</code>                                             | Android      | Group summary. Requires `group`.                                                                                                                                                                                                                                |
| **`ongoing`**           | <code>boolean</code>                                             | Android      | The notification cannot be swiped away. Forces `autoCancel` off.                                                                                                                                                                                                |
| **`autoCancel`**        | <code>boolean</code>                                             | Android      | Remove the notification when the user taps it. Defaults to true.                                                                                                                                                                                                |
| **`inboxList`**         | <code>string[]</code>                                            | Android      | Inbox lines. At most five.                                                                                                                                                                                                                                      |
| **`silent`**            | <code>boolean</code>                                             | iOS          | When true, a notification delivered in the foreground is not presented. The `localNotificationReceived` event still fires.                                                                                                                                      |
| **`badge`**             | <code>number</code>                                              | iOS          | Application icon badge.                                                                                                                                                                                                                                         |


#### Schedule

| Prop                 | Type                                                    | Platform     | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| -------------------- | ------------------------------------------------------- | ------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **`at`**             | <code>string \| Date</code>                             | iOS, Android | Fire at this instant. A `Date` or an ISO-8601 string (`2026-11-05T18:00:00.000Z` or an offset form). Must be in the future and within 8 years. On output this plugin returns an ISO-8601 UTC string.                                                                                                                                                                                                                                                                                       |
| **`repeats`**        | <code>boolean</code>                                    | iOS, Android | With `at`, repeat forever using the fixed delay between the call and `at`. That delay must be at least 60 seconds. Not valid with `on` or `every`.                                                                                                                                                                                                                                                                                                                                         |
| **`allowWhileIdle`** | <code>boolean</code>                                    | Android      | Allow the alarm to fire in Doze maintenance windows. Exact Doze alarms are still limited by the system (roughly one every 9 minutes per app).                                                                                                                                                                                                                                                                                                                                              |
| **`on`**             | <code><a href="#scheduleon">ScheduleOn</a></code>       | iOS, Android | Fire on the next local time that matches these fields, then repeat. Month is 1–12. <a href="#weekday">Weekday</a> is 1 (Sunday) through 7 (Saturday). Omitted date fields match any value. Omitted `second` means 0. Omitted `minute` means 0 unless only `second` is set, in which case every minute matches. Omitted `hour` means 0 when a date field is set or when no time field is set; when `minute` or `second` is set and no date field is set, omitted `hour` matches every hour. |
| **`every`**          | <code><a href="#scheduleevery">ScheduleEvery</a></code> | iOS, Android | Repeat by a fixed unit. `count` is how many of those units make one interval (every 2 days is `{ every: 'day', count: 2 }`). The first fire is one interval after the call. The interval must be at least 60 seconds. `second`, `minute`, and `hour` are fixed durations. `day`, `week`, `two-weeks`, `month`, and `year` use local calendar addition on Android. On iOS every unit is the fixed duration measured when the notification is scheduled.                                     |
| **`count`**          | <code>number</code>                                     | iOS, Android | Multiplier for `every`. Defaults to 1. Maximum 1000.                                                                                                                                                                                                                                                                                                                                                                                                                                       |

#### ScheduleOn

| Prop          | Type                | Platform     | Description                                                  |
| ------------- | ------------------- | ------------ | ------------------------------------------------------------ |
| **`year`**    | <code>number</code> | iOS, Android | Full year, 1970 through 2100.                                |
| **`month`**   | <code>number</code> | iOS, Android | 1 (January) through 12.                                      |
| **`day`**     | <code>number</code> | iOS, Android | Day of the month, 1 through 31. Impossible dates never fire. |
| **`weekday`** | <code>number</code> | iOS, Android | 1 Sunday through 7 Saturday.                                 |
| **`hour`**    | <code>number</code> | iOS, Android | 0 through 23, device local time.                             |
| **`minute`**  | <code>number</code> | iOS, Android | 0 through 59.                                                |
| **`second`**  | <code>number</code> | iOS, Android | 0 through 59.                                                |


#### PendingResult

| Prop                | Type                                          | Platform     |
| ------------------- | --------------------------------------------- | ------------ |
| **`notifications`** | <code>PendingLocalNotificationSchema[]</code> | iOS, Android |


#### PendingLocalNotificationSchema

| Prop           | Type                                                             | Platform     | Description                                                                                                                                |
| -------------- | ---------------------------------------------------------------- | ------------ | ------------------------------------------------------------------------------------------------------------------------------------------ |
| **`id`**       | <code>number</code>                                              | iOS, Android |                                                                                                                                            |
| **`title`**    | <code>string</code>                                              | iOS, Android |                                                                                                                                            |
| **`body`**     | <code>string</code>                                              | iOS, Android |                                                                                                                                            |
| **`extra`**    | <code><a href="#record">Record</a>&lt;string, unknown&gt;</code> | iOS, Android |                                                                                                                                            |
| **`schedule`** | <code><a href="#schedule">Schedule</a></code>                    | iOS, Android | The schedule that was stored. `at`, when present, is an ISO-8601 UTC string even if a `Date` was passed to `schedule`.                     |


#### CancelOptions

| Prop                | Type                           | Platform     |
| ------------------- | ------------------------------ | ------------ |
| **`notifications`** | <code>{ id: number; }[]</code> | iOS, Android |


#### DeliveredNotifications

| Prop                | Type                                       | Platform     |
| ------------------- | ------------------------------------------ | ------------ |
| **`notifications`** | <code>DeliveredNotificationSchema[]</code> | iOS, Android |


#### DeliveredNotificationSchema

| Prop               | Type                                                             | Platform     | Description                                            |
| ------------------ | ---------------------------------------------------------------- | ------------ | ------------------------------------------------------ |
| **`id`**           | <code>number</code>                                              | iOS, Android |                                                        |
| **`title`**        | <code>string</code>                                              | iOS, Android |                                                        |
| **`body`**         | <code>string</code>                                              | iOS, Android |                                                        |
| **`tag`**          | <code>string</code>                                              | Android      | Notification tag when one was set. This plugin does not set tags. |
| **`group`**        | <code>string</code>                                              | Android      |                                                        |
| **`groupSummary`** | <code>boolean</code>                                             | Android      |                                                        |
| **`extra`**        | <code><a href="#record">Record</a>&lt;string, unknown&gt;</code> | iOS, Android |                                                        |
| **`schedule`**     | <code><a href="#schedule">Schedule</a></code>                    | iOS, Android |                                                        |


#### PluginListenerHandle

| Prop         | Type                                      | Platform     |
| ------------ | ----------------------------------------- | ------------ |
| **`remove`** | <code>() =&gt; Promise&lt;void&gt;</code> | iOS, Android |


#### ActionPerformed

| Prop               | Type                                                                        | Platform     | Description                                                                |
| ------------------ | --------------------------------------------------------------------------- | ------------ | -------------------------------------------------------------------------- |
| **`actionId`**     | <code>string</code>                                                         | iOS, Android | `tap` when the notification body is opened, `dismiss` when it is cleared. |
| **`notification`** | <code><a href="#localnotificationschema">LocalNotificationSchema</a></code> | iOS, Android |                                                                            |
