# @capnative/capacitor-local-notifications

Local notifications for Capacitor 8. iOS uses `UNUserNotificationCenter`, Android uses notification channels and `AlarmManager`, and the web implementation uses the browser Notification API while the page is open.

The plugin does not claim that every option works the same way on every platform. Limits are listed below.

## Compatibility

| Plugin | Capacitor |
| --- | --- |
| 8.x | 8.x |

| Platform | Minimum | Scheduling while the app is closed |
| --- | --- | --- |
| iOS | 15 | Yes, through UserNotifications |
| Android | API 24 (7.0) | Yes, through alarms restored after reboot |
| Web | A browser with the Notification API | No. Timers run only while the page is open |

Peer dependency: `@capacitor/core` `>= 8.0.0`.

## Install

```bash
npm install @capnative/capacitor-local-notifications
npx cap sync
```

The published package is not on npm yet. Until it is, install from this repository after the package has been built (`npm run build` produces `dist/`).

### iOS

Capacitor 8 can consume the plugin with Swift Package Manager or CocoaPods.

- SPM product: `CapnativeCapacitorLocalNotifications` in `Package.swift`
- CocoaPod: `CapnativeCapacitorLocalNotifications`

`npx cap sync` wires the package from `package.json`. The deployment target is iOS 15. No extra `Info.plist` usage description is required for local notifications. The system permission dialog is the runtime prompt from `requestPermissions()`.

`critical` interruption level needs Apple's Critical Alerts entitlement. `timeSensitive` needs the Time Sensitive Notifications capability, and a Focus mode can still hide it.

### Android

The plugin manifest merges these permissions:

- `POST_NOTIFICATIONS` (runtime prompt on Android 13+)
- `RECEIVE_BOOT_COMPLETED` (restore alarms after reboot)
- `SCHEDULE_EXACT_ALARM` (exact timing when the user allows it)
- `WAKE_LOCK`

It does **not** use `USE_EXACT_ALARM`. That permission is for alarm-clock and calendar apps and is restricted on Google Play. Exact alarms are requested only through `SCHEDULE_EXACT_ALARM`, which the user can deny. When it is denied, scheduling still succeeds and the alarm is inexact.

An app that does not want the exact-alarm permission can remove it in the host manifest:

```xml
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" tools:node="remove" />
```

Put a status-bar icon in `res/drawable`. It must be a white silhouette. The default icon is the plugin's `ic_stat_capnative_notification`. Large icons must be bitmap drawables (`png` or `webp`), not vectors.

Sounds live in `res/raw`. On Android 8 and newer the sound belongs to the channel, not the individual notification.

### Capacitor config

```typescript
import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  plugins: {
    LocalNotifications: {
      smallIcon: 'ic_stat_notify',
      iconColor: '#488AFF',
      sound: 'beep.wav',
      presentationOptions: ['banner', 'list', 'sound', 'badge'],
    },
  },
};

export default config;
```

`smallIcon`, `iconColor`, and `sound` apply to Android. `sound` is the default channel sound and does not change a channel Android has already created. `presentationOptions` applies to iOS while the app is in the foreground. Omit it to use banner, list, sound, and badge. An empty array presents nothing.

### Web

No native project is required. The page must be open. `localStorage` keeps pending notifications so a later visit can catch up, but a closed tab does not display them. Custom sound files are not played. Action buttons are not shown; a click is `tap`.

## Usage

```typescript
import { LocalNotifications, Weekday } from '@capnative/capacitor-local-notifications';

const permission = await LocalNotifications.requestPermissions();
if (permission.display !== 'granted') {
  throw new Error('Notifications are blocked.');
}

await LocalNotifications.registerActionTypes({
  types: [
    {
      id: 'reminder',
      actions: [
        { id: 'done', title: 'Done', foreground: true },
        { id: 'reply', title: 'Reply', input: true, inputPlaceholder: 'Note' },
      ],
    },
  ],
});

await LocalNotifications.createChannel({
  id: 'reminders',
  name: 'Reminders',
  importance: 4,
  sound: 'beep.wav',
});

await LocalNotifications.schedule({
  notifications: [
    { id: 1, title: 'Now', body: 'Shown immediately' },
    {
      id: 2,
      title: 'Later',
      body: 'One shot',
      schedule: { at: new Date(Date.now() + 60 * 60 * 1000) },
      extra: { path: '/inbox' },
    },
    {
      id: 3,
      title: 'Weekdays',
      body: '09:00 local time',
      schedule: { on: { weekday: Weekday.Monday, hour: 9, minute: 0 } },
      channelId: 'reminders',
      actionTypeId: 'reminder',
    },
  ],
});

await LocalNotifications.addListener('localNotificationReceived', (notification) => {
  console.log('posted', notification.id);
});

await LocalNotifications.addListener('localNotificationActionPerformed', (action) => {
  console.log(action.actionId, action.inputValue, action.notification.extra);
});
```

`schedule()` is atomic. If any item is invalid, or an `actionTypeId` was not registered, nothing new is scheduled. Using an id that is already pending replaces that notification.

`cancel()` removes the pending alarm and the delivered notification with that id. Unknown ids are ignored.

## API

| Method | iOS | Android | Web |
| --- | --- | --- | --- |
| `checkPermissions()` / `requestPermissions()` | Prompt and status | Android 13+ runtime prompt; older versions report the app setting | Notification API |
| `areEnabled()` | Same signal as the permission | Same | Same |
| `schedule()` | Yes | Yes | While the page is open |
| `getPending()` / `cancel()` | Yes | Yes | Yes |
| `getDeliveredNotifications()` | Notifications posted by this plugin | Notifications posted by this plugin | Notifications shown in this page |
| `removeDeliveredNotifications()` / `removeAllDeliveredNotifications()` | This plugin only | This plugin only | This page only |
| `registerActionTypes()` | Categories and buttons | Buttons, including text input | Stored for validation; buttons are not shown |
| `createChannel()` / `deleteChannel()` / `listChannels()` | No-op after id/name checks. `listChannels` is empty | Android 8+ channels. Older versions no-op | No-op after validation. `listChannels` is empty |
| `checkExactNotificationSetting()` | Resolves `granted` | Real setting on Android 12+. `granted` before that | Resolves `granted` |
| `changeExactNotificationSetting()` | Resolves `granted` and does not navigate | Opens the exact-alarm screen on Android 12+ | Resolves `granted` and does not navigate |

`granted` on iOS and web means there is no second exact-alarm switch. It does not mean a web timer can fire while the tab is closed.

### Notification fields

| Field | Notes |
| --- | --- |
| `id` | 32-bit integer. The same id replaces a pending notification. |
| `title`, `body` | Title is 1–200 characters. Body is 0–2000. Both are trimmed. |
| `schedule` | See below. Omit it to show immediately. |
| `extra` | JSON object, at most 4096 encoded characters. Not for secrets. Android notification listeners can read it. |
| `sound` | iOS bundle file, `default`, or `''` for silence. Android 8+ ignores it; set the channel sound. Web plays the browser sound for any non-empty value. |
| `badge` | iOS icon badge. Web calls `navigator.setAppBadge` when it exists. Android launcher badges are not set. |
| `attachments` | iOS only. A bundle file name or a `file://` URL inside the app sandbox, bundle, or temp directory. Remote URLs are rejected. Android accepts the field and does not display it. |
| `actionTypeId` | Must be registered first. `tap` and `dismiss` are reserved. At most 3 buttons. |
| `channelId` | Android 8+. The channel must already exist. Omitted notifications use the `default` channel. |
| `group`, `groupSummary`, `inboxList`, `largeBody`, `summaryText`, `ongoing`, `autoCancel`, `smallIcon`, `largeIcon`, `iconColor` | Android. `groupSummary` requires `group`. `ongoing` forces `autoCancel` off. |
| `threadIdentifier`, `relevanceScore`, `interruptionLevel`, `silent`, `summaryArgument` | iOS. `summaryArgument` is ignored on iOS 15+. `silent` hides a foreground presentation but still emits `localNotificationReceived`. |
| `allowWhileIdle` | Android Doze only. The system still limits exact idle alarms to roughly one every 9 minutes per app. |

### Events

`localNotificationReceived` fires when this plugin posts a notification and the JavaScript bridge is running.

- iOS does not emit it for a notification delivered while the app is suspended. The system still shows the notification.
- Android does not emit it when the process is dead at delivery. The notification is still posted.
- Web emits it only in the open page.

`localNotificationActionPerformed` carries `actionId`, optional `inputValue`, and `notification`.

- `tap` is the notification body.
- `dismiss` is a clear. iOS reports it because categories include a custom dismiss action. Android reports it only while the process is alive.
- Other ids are the buttons from `registerActionTypes`.

### Permissions result

```typescript
interface PermissionStatus {
  display: 'prompt' | 'prompt-with-rationale' | 'granted' | 'denied';
}
```

This plugin returns `prompt`, `granted`, or `denied`. On Android 12 and older, `requestPermissions()` does not show a dialog. If the user has disabled notifications, the result is `denied` and the app must send them to system settings.

```typescript
interface ExactAlarmStatus {
  exact_alarm: 'prompt' | 'prompt-with-rationale' | 'granted' | 'denied';
}
```

The key is `exact_alarm`, matching the familiar Capacitor local-notification shape.

## Scheduling

Use one of `at`, `on`, or `every`. Combining them is rejected. `repeats` is valid only with `at`. `count` is valid only with `every`.

`at` is an absolute instant: a `Date` or an ISO-8601 string such as `2026-11-05T18:00:00.000Z` or `2026-11-05T20:00:00+02:00`. It must be in the future and within 8 years. `getPending()` returns `at` as an ISO-8601 UTC string.

`at` plus `repeats: true` repeats forever by the fixed delay between the call and `at`. That delay must be at least 60 seconds. The phase stays anchored to the original `at`, including after a reboot.

`every` repeats by `count` units (default 1, maximum 1000). `{ every: 'day', count: 2 }` means every two days, not "fire twice". The first fire is one interval after the call, and the interval must be at least 60 seconds. `every: 'second'` therefore needs `count >= 60`.

- `second`, `minute`, and `hour` are fixed durations on every platform.
- `day`, `week`, `two-weeks`, `month`, and `year` use local calendar addition on Android and web.
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

Pending notifications are capped at 100. One `schedule()` call accepts at most 50. A one-shot that was due while the device was off is delivered once after boot (about 5 seconds later). Repeating notifications do not replay every missed occurrence; one catch-up is shown, then the schedule continues on its next future time. The web does the same when the page opens again.

## Platform limits

### Exact alarms

Android 12 and newer can block exact alarms. `checkExactNotificationSetting()` reports that. `schedule()` does not fail when exact alarms are denied; it uses an inexact alarm. Turning the setting on later re-arms stored alarms. Inexact alarms can be minutes late, and Doze can defer them further. `allowWhileIdle` uses `setExactAndAllowWhileIdle` or `setAndAllowWhileIdle`, which is still subject to the system's idle quota.

### Foreground

Android shows a notification whether or not the app is open. iOS uses `presentationOptions`, unless `silent` is true. Web browsers may group or suppress a notification for the focused tab; that is browser behavior.

### Reboot and process death

Android stores the pending payload in private app preferences and reschedules on `BOOT_COMPLETED`. Alarms are not direct-boot aware: they are restored after the user unlocks the device. iOS persists `UNNotificationRequest`s itself. Web has neither.

### Actions

iOS can combine `foreground`, `destructive`, and `requiresAuthentication`. Android has no destructive style and does not require authentication before a button. Text input uses `UNTextInputNotificationAction` on iOS and `RemoteInput` on Android. Web cannot show the buttons.

### Delivered notifications

`removeAllDeliveredNotifications()` removes notifications this plugin posted. It does not clear notifications posted by another plugin. iOS notification ids still share one center, so two plugins should not reuse the same id.

### Channels

Android freezes a channel's importance and sound after creation. Deleting a channel does not always let you recreate it with new settings until the app is reinstalled, if the user changed it. The `default` channel is created when the plugin loads and when a notification is posted without `channelId`.

## Migration from `@capacitor/local-notifications`

The method names, `exact_alarm`, and most fields match that plugin so existing calls are recognizable. These behaviors differ on purpose:

- `schedule.on.month` is 1–12 on Android as well as iOS. The other plugin passes the number straight to Android's 0-based `Calendar.MONTH`.
- `count` multiplies the `every` interval. That matches the other plugin's implementation. Its docs have also described `count` as a maximum number of deliveries; this plugin does not do that.
- Only one of `at`, `on`, and `every` may be set. `repeats` only belongs with `at`.
- Repeats shorter than 60 seconds are rejected, including `every: 'second'` with `count` below 60. iOS rejects a repeating time-interval trigger under 60 seconds.
- `at` in the past is rejected. Show it immediately by omitting `schedule`.
- `removeAllDeliveredNotifications()` is limited to notifications this plugin posted.
- iOS action options can be combined. The other plugin keeps only the first flag it sees.
- Android action buttons open the app, including when `foreground` is false.
- Per-notification `sound` is ignored on Android 8+. Configure the channel.
- Deprecated type aliases from the other package are not re-exported.
- `extra` is size-limited and should not contain secrets.

## Development

```bash
npm run typecheck
npm test
npm run build
npm run verify:android
```

`npm test` runs the TypeScript schedule and web tests in UTC. `verify:android` compiles the Android library and runs the JVM unit tests (`ScheduleCalculator` and `NotificationParser`). That is a real Android compile plus local unit tests. It does not install an app or run instrumented tests on a device or emulator.

iOS is Swift Package Manager and CocoaPods source. `xcodebuild` or `swift test` has to be run on macOS; this repository's Linux CI does not compile the iOS target. The Swift schedule tests live in `ios/Tests` for that macOS run.

```bash
cd android && ./gradlew test
```

needs JDK 21 and an Android SDK with platform 36. `ANDROID_HOME` must point at that SDK.

## License

MIT © Mario Shtika
