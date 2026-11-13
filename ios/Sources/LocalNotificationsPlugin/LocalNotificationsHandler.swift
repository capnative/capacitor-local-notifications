import Capacitor
import UserNotifications

final class LocalNotificationsHandler: NSObject, NotificationHandlerProtocol {
    weak var plugin: LocalNotificationsPlugin?

    func willPresent(notification: UNNotification) -> UNNotificationPresentationOptions {
        guard isCapNative(notification.request.content.userInfo) else {
            return [.banner, .list, .sound, .badge]
        }
        plugin?.notifyListeners("localNotificationReceived", data: payload(notification.request), retainUntilConsumed: true)
        return plugin?.presentationOptions() ?? [.banner, .list, .sound, .badge]
    }

    func didReceive(response: UNNotificationResponse) {
        guard isCapNative(response.notification.request.content.userInfo) else { return }
        let actionId: String
        switch response.actionIdentifier {
        case UNNotificationDefaultActionIdentifier:
            actionId = "tap"
        case UNNotificationDismissActionIdentifier:
            actionId = "dismiss"
        default:
            return
        }
        plugin?.notifyListeners("localNotificationActionPerformed", data: [
            "actionId": actionId,
            "notification": payload(response.notification.request),
        ], retainUntilConsumed: true)
    }

    func payload(_ request: UNNotificationRequest) -> [String: Any] {
        var notification: [String: Any] = [
            "id": Int(request.identifier) ?? -1,
            "title": request.content.title,
            "body": request.content.body,
        ]
        if let extra = request.content.userInfo["extra"] as? String,
           let data = extra.data(using: .utf8),
           let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            notification["extra"] = object
        }
        if let schedule = request.content.userInfo["schedule"] as? String,
           let data = schedule.data(using: .utf8),
           let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            notification["schedule"] = object
        }
        return notification
    }

    private func isCapNative(_ userInfo: [AnyHashable: Any]) -> Bool {
        if let value = userInfo["capnative"] as? Bool { return value }
        if let number = userInfo["capnative"] as? NSNumber { return number.boolValue }
        return false
    }
}
