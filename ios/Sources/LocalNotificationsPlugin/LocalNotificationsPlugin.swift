import Capacitor
import Foundation
import UserNotifications

@objc(LocalNotificationsPlugin)
public class LocalNotificationsPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "LocalNotificationsPlugin"
    public let jsName = "LocalNotifications"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "schedule", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getPending", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "cancel", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getDeliveredNotifications", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "removeDeliveredNotifications", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "removeAllDeliveredNotifications", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "registerActionTypes", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "createChannel", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "deleteChannel", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "listChannels", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "checkPermissions", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "requestPermissions", returnType: CAPPluginReturnPromise),
    ]

    private let handler = LocalNotificationsHandler()
    private let defaultsKey = "capnative.local-notifications.actions"
    private let center = UNUserNotificationCenter.current()

    override public func load() {
        handler.plugin = self
        bridge?.notificationRouter.localNotificationHandler = handler
        restoreActionTypes()
    }

    func presentationOptions() -> UNNotificationPresentationOptions {
        guard let configured = getConfig().getArray("presentationOptions") else {
            return [.banner, .list, .sound, .badge]
        }
        var options: UNNotificationPresentationOptions = []
        for option in configured.compactMap({ $0 as? String }) {
            switch option {
            case "banner": options.insert(.banner)
            case "list": options.insert(.list)
            case "sound": options.insert(.sound)
            case "badge": options.insert(.badge)
            default: break
            }
        }
        return options
    }

    @objc func checkPermissions(_ call: CAPPluginCall) {
        center.getNotificationSettings { settings in
            call.resolve(["display": Self.permissionName(settings.authorizationStatus)])
        }
    }

    @objc func requestPermissions(_ call: CAPPluginCall) {
        center.requestAuthorization(options: [.alert, .badge, .sound]) { granted, error in
            if let error {
                call.reject(error.localizedDescription, "UNAVAILABLE")
                return
            }
            call.resolve(["display": granted ? "granted" : "denied"])
        }
    }

    @objc func schedule(_ call: CAPPluginCall) {
        guard let raw = call.getArray("notifications", JSObject.self) else {
            call.reject("notifications must be a non-empty array.", "INVALID_NOTIFICATION")
            return
        }
        let items: [Any] = raw.map { bridgeObject($0) }
        let now = Date()
        let notifications: [ValidatedNotification]
        do {
            notifications = try SchedulePlan.parseNotifications(items, now: now, calendar: .current)
        } catch let error as NotificationFailure {
            call.reject(error.message, error.code)
            return
        } catch {
            call.reject("The notification could not be scheduled.", "INVALID_NOTIFICATION")
            return
        }
        let registered = savedActionIds()
        for notification in notifications {
            if let actionTypeId = notification.actionTypeId, !registered.contains(actionTypeId) {
                call.reject("Action type \"\(actionTypeId)\" is not registered. Call registerActionTypes() first.", "INVALID_ACTION")
                return
            }
            if let sound = notification.sound, !sound.isEmpty, sound != "default", bundleSound(sound) == nil {
                call.reject("Sound \(sound) was not found in the app bundle.", "INVALID_NOTIFICATION")
                return
            }
        }
        center.getNotificationSettings { [weak self] settings in
            guard let self else { return }
            let allowed = settings.authorizationStatus == .authorized ||
                settings.authorizationStatus == .provisional ||
                settings.authorizationStatus == .ephemeral
            guard allowed else {
                call.reject("Notification permission is not granted.", "PERMISSION_DENIED")
                return
            }
            self.add(notifications, now: now, call: call)
        }
    }

    @objc func getPending(_ call: CAPPluginCall) {
        center.getPendingNotificationRequests { [weak self] requests in
            let notifications = requests.compactMap { request -> [String: Any]? in
                guard let self, self.handlerIsOurs(request) else { return nil }
                return self.handler.payload(request)
            }
            call.resolve(["notifications": notifications])
        }
    }

    @objc func cancel(_ call: CAPPluginCall) {
        do {
            let ids = try cancelIds(call)
            let stringIds = ids.map(String.init)
            center.removePendingNotificationRequests(withIdentifiers: stringIds)
            center.removeDeliveredNotifications(withIdentifiers: stringIds)
            call.resolve()
        } catch let error as NotificationFailure {
            call.reject(error.message, error.code)
        } catch {
            call.reject("The notifications could not be cancelled.", "INVALID_NOTIFICATION")
        }
    }

    @objc func getDeliveredNotifications(_ call: CAPPluginCall) {
        center.getDeliveredNotifications { [weak self] delivered in
            let notifications = delivered.compactMap { notification -> [String: Any]? in
                guard let self, self.handlerIsOurs(notification.request) else { return nil }
                return self.handler.payload(notification.request)
            }
            call.resolve(["notifications": notifications])
        }
    }

    @objc func removeDeliveredNotifications(_ call: CAPPluginCall) {
        do {
            let ids = try cancelIds(call).map(String.init)
            center.removeDeliveredNotifications(withIdentifiers: ids)
            call.resolve()
        } catch let error as NotificationFailure {
            call.reject(error.message, error.code)
        } catch {
            call.reject("The notifications could not be removed.", "INVALID_NOTIFICATION")
        }
    }

    @objc func removeAllDeliveredNotifications(_ call: CAPPluginCall) {
        center.getDeliveredNotifications { [weak self] delivered in
            let ids = delivered.compactMap { notification -> String? in
                guard let self, self.handlerIsOurs(notification.request) else { return nil }
                return notification.request.identifier
            }
            self?.center.removeDeliveredNotifications(withIdentifiers: ids)
            call.resolve()
        }
    }

    @objc func registerActionTypes(_ call: CAPPluginCall) {
        guard let types = call.getArray("types", JSObject.self) else {
            call.reject("types must be an array of action types.", "INVALID_ACTION")
            return
        }
        do {
            let normalized = try types.map { try normalizeActionType($0) }
            let data = try JSONSerialization.data(withJSONObject: normalized)
            UserDefaults.standard.set(data, forKey: defaultsKey)
            applyActionTypes(normalized)
            call.resolve()
        } catch let error as NotificationFailure {
            call.reject(error.message, error.code)
        } catch {
            call.reject("Action types could not be saved.", "INVALID_ACTION")
        }
    }

    @objc func createChannel(_ call: CAPPluginCall) {
        let name = call.getString("name")?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard validChannelId(call.getString("id")), !name.isEmpty, name.count <= 100 else {
            call.reject("A channel needs a valid id and a name.", "INVALID_CHANNEL")
            return
        }
        call.resolve()
    }

    @objc func deleteChannel(_ call: CAPPluginCall) {
        guard validChannelId(call.getString("id")) else {
            call.reject("id has an invalid format.", "INVALID_CHANNEL")
            return
        }
        call.resolve()
    }

    @objc func listChannels(_ call: CAPPluginCall) {
        call.resolve(["channels": []])
    }

    private func add(_ notifications: [ValidatedNotification], now: Date, call: CAPPluginCall) {
        let group = DispatchGroup()
        let lock = NSLock()
        var failure: NotificationFailure?
        var ids: [String] = []
        for notification in notifications {
            do {
                let request = try makeRequest(notification, now: now)
                ids.append(request.identifier)
                group.enter()
                center.add(request) { error in
                    if let error {
                        lock.lock()
                        if failure == nil {
                            failure = NotificationFailure(code: "UNAVAILABLE", message: error.localizedDescription)
                        }
                        lock.unlock()
                    }
                    group.leave()
                }
            } catch let error as NotificationFailure {
                call.reject(error.message, error.code)
                return
            } catch {
                call.reject("The notification could not be scheduled.", "INVALID_NOTIFICATION")
                return
            }
        }
        group.notify(queue: .main) { [weak self] in
            if let failure {
                self?.center.removePendingNotificationRequests(withIdentifiers: ids)
                call.reject(failure.message, failure.code)
                return
            }
            call.resolve(["notifications": notifications.map { ["id": $0.id] }])
        }
    }

    private func makeRequest(_ notification: ValidatedNotification, now: Date) throws -> UNNotificationRequest {
        let content = UNMutableNotificationContent()
        content.title = notification.title
        content.body = notification.body
        content.threadIdentifier = notification.threadIdentifier ?? ""
        if let badge = notification.badge {
            content.badge = NSNumber(value: badge)
        }
        if let level = notification.interruptionLevel {
            switch level {
            case "active": content.interruptionLevel = .active
            case "critical": content.interruptionLevel = .critical
            case "passive": content.interruptionLevel = .passive
            case "timeSensitive": content.interruptionLevel = .timeSensitive
            default: break
            }
        }
        if let score = notification.relevanceScore {
            content.relevanceScore = score
        }
        if let sound = notification.sound {
            if sound == "default" {
                content.sound = .default
            } else if !sound.isEmpty {
                content.sound = UNNotificationSound(named: UNNotificationSoundName(sound))
            }
        }
        if let actionTypeId = notification.actionTypeId {
            content.categoryIdentifier = actionTypeId
        }
        if !notification.attachments.isEmpty {
            content.attachments = try notification.attachments.map { try makeAttachment($0.id, $0.url) }
        }
        var info: [AnyHashable: Any] = ["capnative": true, "silent": notification.silent]
        if let extra = notification.extraJSON { info["extra"] = extra }
        if let schedule = notification.schedule, let encoded = scheduleJSON(schedule) { info["schedule"] = encoded }
        if let actionTypeId = notification.actionTypeId { info["actionTypeId"] = actionTypeId }
        content.userInfo = info
        let trigger = try makeTrigger(notification.plan, now: now)
        return UNNotificationRequest(identifier: String(notification.id), content: content, trigger: trigger)
    }

    private func makeTrigger(_ plan: FirePlan, now: Date) throws -> UNNotificationTrigger? {
        switch plan {
        case .immediate:
            return nil
        case .once(let at):
            return UNTimeIntervalNotificationTrigger(timeInterval: max(at.timeIntervalSince(now), 0.1), repeats: false)
        case .fixedInterval(_, let interval, _):
            return UNTimeIntervalNotificationTrigger(timeInterval: interval, repeats: true)
        case .interval(_, _, _, let next):
            return UNTimeIntervalNotificationTrigger(timeInterval: max(next.timeIntervalSince(now), SchedulePlan.minRepeat), repeats: true)
        case .calendar(let match, _):
            return UNCalendarNotificationTrigger(dateMatching: SchedulePlan.dateComponents(for: match, calendar: .current), repeats: true)
        }
    }

    private func makeAttachment(_ id: String, _ urlString: String) throws -> UNNotificationAttachment {
        let url = try resolveAttachment(urlString)
        return try UNNotificationAttachment(identifier: id, url: url, options: nil)
    }

    private func resolveAttachment(_ urlString: String) throws -> URL {
        if !urlString.contains("/") && !urlString.contains(":") {
            let name = urlString as NSString
            guard let url = Bundle.main.url(forResource: name.deletingPathExtension, withExtension: name.pathExtension) else {
                throw NotificationFailure(code: "INVALID_NOTIFICATION", message: "Attachment \(urlString) was not found in the app bundle.")
            }
            return url
        }
        let url: URL
        if urlString.hasPrefix("file://") {
            guard let parsed = URL(string: urlString) else {
                throw NotificationFailure(code: "INVALID_NOTIFICATION", message: "Attachment url is not a file URL.")
            }
            url = parsed
        } else {
            url = URL(fileURLWithPath: urlString)
        }
        let standardized = url.standardizedFileURL
        let path = standardized.path
        let roots = [NSHomeDirectory(), Bundle.main.bundlePath, NSTemporaryDirectory()]
        let allowed = roots.contains { root in
            let prefix = root.hasSuffix("/") ? root : root + "/"
            return path == root || path.hasPrefix(prefix)
        }
        guard allowed, FileManager.default.isReadableFile(atPath: path) else {
            throw NotificationFailure(code: "INVALID_NOTIFICATION", message: "Attachment path is outside the app sandbox or cannot be read.")
        }
        return standardized
    }

    private func bundleSound(_ name: String) -> URL? {
        let file = name as NSString
        return Bundle.main.url(forResource: file.deletingPathExtension, withExtension: file.pathExtension)
    }

    private func cancelIds(_ call: CAPPluginCall) throws -> [Int] {
        guard let raw = call.getArray("notifications", JSObject.self), !raw.isEmpty else {
            throw NotificationFailure(code: "INVALID_NOTIFICATION", message: "notifications must be a non-empty array of { id } objects.")
        }
        if raw.count > 50 {
            throw NotificationFailure(code: "INVALID_NOTIFICATION", message: "At most 50 notifications can be cancelled in one call.")
        }
        return try raw.map { entry in
            let map = bridgeObject(entry)
            if map["id"] is Bool || !(map["id"] is Int || map["id"] is NSNumber) {
                throw NotificationFailure(code: "INVALID_NOTIFICATION", message: "id must be an integer from -2147483648 to 2147483647.")
            }
            let number = (map["id"] as? NSNumber)?.intValue ?? (map["id"] as? Int)
            guard let number else {
                throw NotificationFailure(code: "INVALID_NOTIFICATION", message: "id must be an integer from -2147483648 to 2147483647.")
            }
            return number
        }
    }

    private func handlerIsOurs(_ request: UNNotificationRequest) -> Bool {
        if let value = request.content.userInfo["capnative"] as? Bool { return value }
        if let number = request.content.userInfo["capnative"] as? NSNumber { return number.boolValue }
        return false
    }

    private func scheduleJSON(_ schedule: ScheduleEcho) -> String? {
        var object: [String: Any] = [:]
        if let at = schedule.at { object["at"] = at }
        if schedule.repeats { object["repeats"] = true }
        if schedule.allowWhileIdle { object["allowWhileIdle"] = true }
        if let every = schedule.every { object["every"] = every }
        if let count = schedule.count { object["count"] = count }
        if let on = schedule.on {
            var fields: [String: Int] = [:]
            if let year = on.year { fields["year"] = year }
            if let month = on.month { fields["month"] = month }
            if let day = on.day { fields["day"] = day }
            if let weekday = on.weekday { fields["weekday"] = weekday }
            if let hour = on.hour { fields["hour"] = hour }
            if let minute = on.minute { fields["minute"] = minute }
            if let second = on.second { fields["second"] = second }
            object["on"] = fields
        }
        guard let data = try? JSONSerialization.data(withJSONObject: object),
              let text = String(data: data, encoding: .utf8) else { return nil }
        return text
    }

    private func savedActionIds() -> Set<String> {
        guard let data = UserDefaults.standard.data(forKey: defaultsKey),
              let types = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
            return []
        }
        return Set(types.compactMap { $0["id"] as? String })
    }

    private func restoreActionTypes() {
        guard let data = UserDefaults.standard.data(forKey: defaultsKey),
              let types = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
            return
        }
        applyActionTypes(types)
    }

    private func applyActionTypes(_ types: [[String: Any]]) {
        var categories: [UNNotificationCategory] = []
        for type in types {
            guard let id = type["id"] as? String else { continue }
            let actions = (type["actions"] as? [[String: Any]] ?? []).compactMap { actionObject -> UNNotificationAction? in
                guard let actionId = actionObject["id"] as? String, let title = actionObject["title"] as? String else { return nil }
                var options: UNNotificationActionOptions = []
                if (actionObject["foreground"] as? Bool) == true { options.insert(.foreground) }
                if (actionObject["destructive"] as? Bool) == true { options.insert(.destructive) }
                if (actionObject["requiresAuthentication"] as? Bool) == true { options.insert(.authenticationRequired) }
                if (actionObject["input"] as? Bool) == true {
                    let placeholder = (actionObject["inputPlaceholder"] as? String) ?? ""
                    if let button = actionObject["inputButtonTitle"] as? String, !button.isEmpty {
                        return UNTextInputNotificationAction(
                            identifier: actionId,
                            title: title,
                            options: options,
                            textInputButtonTitle: button,
                            textInputPlaceholder: placeholder
                        )
                    }
                    return UNTextInputNotificationAction(identifier: actionId, title: title, options: options)
                }
                return UNNotificationAction(identifier: actionId, title: title, options: options)
            }
            var categoryOptions: UNNotificationCategoryOptions = [.customDismissAction]
            if (type["iosCustomDismissAction"] as? Bool) == true { categoryOptions.insert(.customDismissAction) }
            if (type["iosAllowInCarPlay"] as? Bool) == true { categoryOptions.insert(.allowInCarPlay) }
            if (type["iosHiddenPreviewsShowTitle"] as? Bool) == true { categoryOptions.insert(.hiddenPreviewsShowTitle) }
            if (type["iosHiddenPreviewsShowSubtitle"] as? Bool) == true { categoryOptions.insert(.hiddenPreviewsShowSubtitle) }
            let placeholder = (type["iosHiddenPreviewsBodyPlaceholder"] as? String) ?? ""
            categories.append(UNNotificationCategory(
                identifier: id,
                actions: actions,
                intentIdentifiers: [],
                hiddenPreviewsBodyPlaceholder: placeholder,
                options: categoryOptions
            ))
        }
        center.setNotificationCategories(Set(categories))
    }

    private func normalizeActionType(_ value: JSObject) throws -> [String: Any] {
        guard let id = value["id"] as? String, id.range(of: #"^[A-Za-z0-9_-]{1,64}$"#, options: .regularExpression) != nil else {
            throw NotificationFailure(code: "INVALID_ACTION", message: "id has an invalid format.")
        }
        if id == "tap" || id == "dismiss" {
            throw NotificationFailure(code: "INVALID_ACTION", message: "Action type id cannot be tap or dismiss.")
        }
        let rawActions = value["actions"] as? [JSObject] ?? []
        if rawActions.count > 3 {
            throw NotificationFailure(code: "INVALID_ACTION", message: "An action type can have at most 3 actions.")
        }
        var seen = Set<String>()
        let actions: [[String: Any]] = try rawActions.map { action in
            guard let actionId = action["id"] as? String,
                  actionId.range(of: #"^[A-Za-z0-9_-]{1,64}$"#, options: .regularExpression) != nil,
                  let title = action["title"] as? String else {
                throw NotificationFailure(code: "INVALID_ACTION", message: "Each action needs an id and a title.")
            }
            if actionId == "tap" || actionId == "dismiss" || !seen.insert(actionId).inserted {
                throw NotificationFailure(code: "INVALID_ACTION", message: "Action id \(actionId) is reserved or duplicated.")
            }
            let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed.isEmpty || trimmed.count > 40 {
                throw NotificationFailure(code: "INVALID_ACTION", message: "title must be 1 to 40 characters.")
            }
            return [
                "id": actionId,
                "title": trimmed,
                "foreground": (action["foreground"] as? Bool) ?? false,
                "destructive": (action["destructive"] as? Bool) ?? false,
                "requiresAuthentication": (action["requiresAuthentication"] as? Bool) ?? false,
                "input": (action["input"] as? Bool) ?? false,
                "inputButtonTitle": action["inputButtonTitle"] as? String ?? "",
                "inputPlaceholder": action["inputPlaceholder"] as? String ?? "",
            ]
        }
        return [
            "id": id,
            "actions": actions,
            "iosHiddenPreviewsBodyPlaceholder": value["iosHiddenPreviewsBodyPlaceholder"] as? String ?? "",
            "iosCustomDismissAction": (value["iosCustomDismissAction"] as? Bool) ?? false,
            "iosAllowInCarPlay": (value["iosAllowInCarPlay"] as? Bool) ?? false,
            "iosHiddenPreviewsShowTitle": (value["iosHiddenPreviewsShowTitle"] as? Bool) ?? false,
            "iosHiddenPreviewsShowSubtitle": (value["iosHiddenPreviewsShowSubtitle"] as? Bool) ?? false,
        ]
    }

    private func validChannelId(_ id: String?) -> Bool {
        guard let id else { return false }
        return id.range(of: #"^[A-Za-z0-9_.-]{1,100}$"#, options: .regularExpression) != nil
    }

    private func bridgeObject(_ object: JSObject) -> [String: Any] {
        var result: [String: Any] = [:]
        for (key, value) in object {
            result[key] = bridgeValue(value)
        }
        return result
    }

    private func bridgeValue(_ value: JSValue) -> Any {
        if let object = value as? JSObject {
            return bridgeObject(object)
        }
        if let array = value as? JSArray {
            return array.map { bridgeValue($0) }
        }
        return value
    }

    private static func permissionName(_ status: UNAuthorizationStatus) -> String {
        switch status {
        case .authorized, .provisional, .ephemeral:
            return "granted"
        case .denied:
            return "denied"
        case .notDetermined:
            return "prompt"
        @unknown default:
            return "prompt"
        }
    }
}
