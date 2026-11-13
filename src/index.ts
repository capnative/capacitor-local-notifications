import { registerPlugin } from '@capacitor/core';

import type { LocalNotificationsPlugin } from './definitions';

const LocalNotifications = registerPlugin<LocalNotificationsPlugin>('LocalNotifications');

export * from './definitions';
export { LocalNotifications };
