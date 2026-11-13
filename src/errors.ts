export const LocalNotificationErrorCodes = {
  INVALID_NOTIFICATION: 'INVALID_NOTIFICATION',
  INVALID_SCHEDULE: 'INVALID_SCHEDULE',
  INVALID_ACTION: 'INVALID_ACTION',
  PERMISSION_DENIED: 'PERMISSION_DENIED',
  UNAVAILABLE: 'UNAVAILABLE',
} as const;

export type LocalNotificationErrorCode =
  (typeof LocalNotificationErrorCodes)[keyof typeof LocalNotificationErrorCodes];

export class LocalNotificationsError extends Error {
  readonly code: LocalNotificationErrorCode;

  constructor(code: LocalNotificationErrorCode, message: string) {
    super(message);
    this.name = 'LocalNotificationsError';
    this.code = code;
  }
}

export function isLocalNotificationsError(error: unknown): error is LocalNotificationsError {
  return error instanceof LocalNotificationsError;
}
