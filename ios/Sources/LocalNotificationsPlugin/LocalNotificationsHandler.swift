import Capacitor
import UserNotifications

final class LocalNotificationsHandler: NSObject, NotificationHandlerProtocol {
    weak var plugin: LocalNotificationsPlugin?

    func willPresent(notification: UNNotification) -> UNNotificationPresentationOptions {
        guard isCapNative(notification.request.content.userInfo) else {
            return [.banner, .list, .sound, .badge]
        }
        plugin?.notifyListeners("localNotificationReceived", data: payload(notification.request), retainUntilConsumed: true)
        if (notification.request.content.userInfo["silent"] as? Bool) == true {
            return []
        }
        return plugin?.presentationOptions() ?? [.banner, .list, .sound, .badge]
    }

    func didReceive(response: UNNotificationResponse) {
        guard isCapNative(response.notification.request.content.userInfo) else { return }
        var data: [String: Any] = [:]
        switch response.actionIdentifier {
        case UNNotificationDefaultActionIdentifier:
            data["actionId"] = "tap"
        case UNNotificationDismissActionIdentifier:
            data["actionId"] = "dismiss"
        default:
            data["actionId"] = response.actionIdentifier
        }
        if let input = response as? UNTextInputNotificationResponse {
            data["inputValue"] = String(input.userText.prefix(500))
        }
        data["notification"] = payload(response.notification.request)
        plugin?.notifyListeners("localNotificationActionPerformed", data: data, retainUntilConsumed: true)
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
        if let actionTypeId = request.content.userInfo["actionTypeId"] as? String {
            notification["actionTypeId"] = actionTypeId
        }
        return notification
    }

    private func isCapNative(_ userInfo: [AnyHashable: Any]) -> Bool {
        if let value = userInfo["capnative"] as? Bool { return value }
        if let number = userInfo["capnative"] as? NSNumber { return number.boolValue }
        return false
    }
}
