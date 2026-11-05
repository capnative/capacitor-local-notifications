import { registerPlugin } from '@capacitor/core';

import type { LocalNotificationsPlugin } from './definitions';

const LocalNotifications = registerPlugin<LocalNotificationsPlugin>('LocalNotifications', {
  web: () => import('./web').then((module) => new module.LocalNotificationsWeb()),
});

export * from './definitions';
export { LocalNotifications };
