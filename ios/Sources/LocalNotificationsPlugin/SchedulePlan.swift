import Foundation

public struct NotificationFailure: Error, Equatable {
    public let code: String
    public let message: String
}

public enum EveryUnit: String, CaseIterable {
    case year
    case month
    case twoWeeks = "two-weeks"
    case week
    case day
    case hour
    case minute
    case second
}

public struct CalendarMatch: Equatable {
    public var year: Int?
    public var month: Int?
    public var day: Int?
    public var weekday: Int?
    public var hour: Int?
    public var minute: Int?
    public var second: Int?

    public init(year: Int? = nil, month: Int? = nil, day: Int? = nil, weekday: Int? = nil, hour: Int? = nil, minute: Int? = nil, second: Int? = nil) {
        self.year = year
        self.month = month
        self.day = day
        self.weekday = weekday
        self.hour = hour
        self.minute = minute
        self.second = second
    }
}

public enum FirePlan: Equatable {
    case immediate
    case once(at: Date)
    case fixedInterval(anchor: Date, interval: TimeInterval, next: Date)
    case interval(unit: EveryUnit, count: Int, anchor: Date, next: Date)
    case calendar(on: CalendarMatch, next: Date)

    public var next: Date? {
        switch self {
        case .immediate:
            return nil
        case .once(let at):
            return at
        case .fixedInterval(_, _, let next), .interval(_, _, _, let next), .calendar(_, let next):
            return next
        }
    }
}

public struct ScheduleEcho: Equatable {
    public var at: String?
    public var repeats: Bool = false
    public var allowWhileIdle: Bool = false
    public var every: String?
    public var count: Int?
    public var on: CalendarMatch?
}

public struct ValidatedNotification: Equatable {
    public var id: Int
    public var title: String
    public var body: String
    public var sound: String?
    public var extraJSON: String?
    public var threadIdentifier: String?
    public var summaryArgument: String?
    public var relevanceScore: Double?
    public var interruptionLevel: String?
    public var badge: Int?
    public var silent: Bool
    public var allowWhileIdle: Bool
    public var schedule: ScheduleEcho?
    public var plan: FirePlan

    public static func == (lhs: ValidatedNotification, rhs: ValidatedNotification) -> Bool {
        lhs.id == rhs.id && lhs.title == rhs.title && lhs.plan == rhs.plan
    }
}

public enum SchedulePlan {
    public static let minRepeat: TimeInterval = 60
    public static let maxLead: TimeInterval = 8 * 366 * 24 * 60 * 60

    public static func parseNotifications(_ items: [Any], now: Date, calendar: Calendar = .current) throws -> [ValidatedNotification] {
        if items.isEmpty {
            throw NotificationFailure(code: "INVALID_NOTIFICATION", message: "notifications must be a non-empty array.")
        }
        if items.count > 50 {
            throw NotificationFailure(code: "INVALID_NOTIFICATION", message: "At most 50 notifications can be scheduled in one call.")
        }
        let parsed = try items.map { item -> ValidatedNotification in
            guard let map = item as? [String: Any] else {
                throw NotificationFailure(code: "INVALID_NOTIFICATION", message: "Each notification must be an object.")
            }
            return try parseNotification(map, now: now, calendar: calendar)
        }
        var seen = Set<Int>()
        for notification in parsed where !seen.insert(notification.id).inserted {
            throw NotificationFailure(code: "INVALID_NOTIFICATION", message: "Duplicate notification id \(notification.id) in one schedule() call.")
        }
        return parsed
    }

    public static func parseNotification(_ value: [String: Any], now: Date, calendar: Calendar = .current) throws -> ValidatedNotification {
        let id = try requireInt(value["id"], -2147483648, 2147483647, "id", "INVALID_NOTIFICATION")
        let title = try requireText(value["title"], 1, 200, "title", "INVALID_NOTIFICATION")
        let body = try requireText(value["body"], 0, 2000, "body", "INVALID_NOTIFICATION")
        let scheduled = try parseSchedule(value["schedule"], now: now, calendar: calendar)
        let notification = ValidatedNotification(
            id: id,
            title: title,
            body: body,
            sound: try parseSound(value["sound"]),
            extraJSON: try parseExtra(value["extra"]),
            threadIdentifier: try optionalToken(value["threadIdentifier"], "^[A-Za-z0-9_.-]{1,128}$", "threadIdentifier", "INVALID_NOTIFICATION"),
            summaryArgument: try optionalText(value["summaryArgument"], 200, "summaryArgument", "INVALID_NOTIFICATION"),
            relevanceScore: try optionalScore(value["relevanceScore"]),
            interruptionLevel: try optionalLevel(value["interruptionLevel"]),
            badge: value["badge"] == nil ? nil : try requireInt(value["badge"], 0, 999_999, "badge", "INVALID_NOTIFICATION"),
            silent: try optionalBool(value["silent"], false, "silent", "INVALID_NOTIFICATION"),
            allowWhileIdle: scheduled.allowWhileIdle,
            schedule: scheduled.echo,
            plan: scheduled.plan
        )
        return notification
    }

    public static func nextCalendar(_ match: CalendarMatch, after date: Date, calendar: Calendar) throws -> Date {
        try validateMatch(match)
        let mode = timeMode(match)
        if mode.minuteWild {
            return nextSecond(date, mode.second, calendar)
        }
        if mode.hourWild {
            return nextMinute(date, mode.minute, mode.second, calendar)
        }
        let limit = date.addingTimeInterval(maxLead)
        var day = calendar.startOfDay(for: date)
        for _ in 0..<(366 * 8 + 3) {
            if let candidate = atLocalTime(day, mode.hour, mode.minute, mode.second, calendar),
               candidate > date,
               dateFieldsMatch(candidate, match, calendar) {
                return candidate
            }
            guard let nextDay = calendar.date(byAdding: .day, value: 1, to: day) else { break }
            day = nextDay
            if day > limit { break }
        }
        throw NotificationFailure(code: "INVALID_SCHEDULE", message: "No matching local time in the next 8 years.")
    }

    public static func addEvery(_ date: Date, _ unit: EveryUnit, _ count: Int, calendar: Calendar) -> Date {
        switch unit {
        case .second:
            return date.addingTimeInterval(TimeInterval(count))
        case .minute:
            return date.addingTimeInterval(TimeInterval(count * 60))
        case .hour:
            return date.addingTimeInterval(TimeInterval(count * 3_600))
        case .day:
            return calendar.date(byAdding: .day, value: count, to: date) ?? date
        case .week:
            return calendar.date(byAdding: .day, value: 7 * count, to: date) ?? date
        case .twoWeeks:
            return calendar.date(byAdding: .day, value: 14 * count, to: date) ?? date
        case .month:
            return addMonthsOverflow(date, count, calendar)
        case .year:
            return addYearsOverflow(date, count, calendar)
        }
    }

    public static func dateComponents(for match: CalendarMatch, calendar: Calendar) -> DateComponents {
        let mode = timeMode(match)
        var components = DateComponents()
        components.calendar = calendar
        components.timeZone = calendar.timeZone
        components.year = match.year
        components.month = match.month
        components.day = match.day
        components.weekday = match.weekday
        if !mode.hourWild { components.hour = mode.hour }
        if !mode.minuteWild { components.minute = mode.minute }
        components.second = mode.second
        return components
    }

    public static func iso(_ date: Date) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        formatter.timeZone = TimeZone(identifier: "UTC")
        return formatter.string(from: date)
    }

    private struct ParsedSchedule {
        var echo: ScheduleEcho?
        var plan: FirePlan
        var allowWhileIdle: Bool
    }

    private static func parseSchedule(_ value: Any?, now: Date, calendar: Calendar) throws -> ParsedSchedule {
        if value == nil { return ParsedSchedule(echo: nil, plan: .immediate, allowWhileIdle: false) }
        guard let map = value as? [String: Any] else {
            throw NotificationFailure(code: "INVALID_SCHEDULE", message: "schedule must be an object.")
        }
        let allowWhileIdle = try optionalBool(map["allowWhileIdle"], false, "allowWhileIdle", "INVALID_SCHEDULE")
        let hasAt = map["at"] != nil
        let hasOn = map["on"] != nil
        let hasEvery = map["every"] != nil
        if [hasAt, hasOn, hasEvery].filter({ $0 }).count > 1 {
            throw NotificationFailure(code: "INVALID_SCHEDULE", message: "Use only one of schedule.at, schedule.on, or schedule.every.")
        }
        if map["count"] != nil && !hasEvery {
            throw NotificationFailure(code: "INVALID_SCHEDULE", message: "schedule.count is only valid together with schedule.every.")
        }
        if map["repeats"] != nil && !hasAt {
            throw NotificationFailure(code: "INVALID_SCHEDULE", message: "schedule.repeats is only valid together with schedule.at.")
        }
        if hasEvery {
            guard let wire = map["every"] as? String, let unit = EveryUnit(rawValue: wire) else {
                throw NotificationFailure(code: "INVALID_SCHEDULE", message: "schedule.every must be year, month, two-weeks, week, day, hour, minute, or second.")
            }
            let count = map["count"] == nil ? 1 : try requireInt(map["count"], 1, 1000, "count", "INVALID_SCHEDULE")
            let next = addEvery(now, unit, count, calendar: calendar)
            let interval = next.timeIntervalSince(now)
            if interval < minRepeat {
                throw NotificationFailure(code: "INVALID_SCHEDULE", message: "Repeating notifications must be at least 60 seconds apart.")
            }
            if interval > maxLead {
                throw NotificationFailure(code: "INVALID_SCHEDULE", message: "The first fire time must be within 8 years.")
            }
            return ParsedSchedule(
                echo: ScheduleEcho(every: wire, count: count, allowWhileIdle: allowWhileIdle),
                plan: .interval(unit: unit, count: count, anchor: now, next: next),
                allowWhileIdle: allowWhileIdle
            )
        }
        if hasOn {
            let on = try parseCalendar(map["on"])
            let next = try nextCalendar(on, after: now, calendar: calendar)
            return ParsedSchedule(
                echo: ScheduleEcho(allowWhileIdle: allowWhileIdle, on: on),
                plan: .calendar(on: on, next: next),
                allowWhileIdle: allowWhileIdle
            )
        }
        if hasAt {
            let at = try parseAt(map["at"])
            let repeats = try optionalBool(map["repeats"], false, "repeats", "INVALID_SCHEDULE")
            let interval = at.timeIntervalSince(now)
            if interval <= 0 {
                throw NotificationFailure(code: "INVALID_SCHEDULE", message: "schedule.at must be in the future.")
            }
            if interval > maxLead {
                throw NotificationFailure(code: "INVALID_SCHEDULE", message: "schedule.at must be within 8 years.")
            }
            if repeats && interval < minRepeat {
                throw NotificationFailure(code: "INVALID_SCHEDULE", message: "A repeating schedule.at must be at least 60 seconds in the future.")
            }
            if repeats {
                return ParsedSchedule(
                    echo: ScheduleEcho(at: iso(at), repeats: true, allowWhileIdle: allowWhileIdle),
                    plan: .fixedInterval(anchor: at, interval: interval, next: at),
                    allowWhileIdle: allowWhileIdle
                )
            }
            return ParsedSchedule(
                echo: ScheduleEcho(at: iso(at), allowWhileIdle: allowWhileIdle),
                plan: .once(at: at),
                allowWhileIdle: allowWhileIdle
            )
        }
        return ParsedSchedule(
            echo: allowWhileIdle ? ScheduleEcho(allowWhileIdle: true) : nil,
            plan: .immediate,
            allowWhileIdle: allowWhileIdle
        )
    }

    private static func parseCalendar(_ value: Any?) throws -> CalendarMatch {
        guard let map = value as? [String: Any] else {
            throw NotificationFailure(code: "INVALID_SCHEDULE", message: "schedule.on must be an object.")
        }
        let match = CalendarMatch(
            year: try optionalField(map["year"], 1970, 2100, "year"),
            month: try optionalField(map["month"], 1, 12, "month"),
            day: try optionalField(map["day"], 1, 31, "day"),
            weekday: try optionalField(map["weekday"], 1, 7, "weekday"),
            hour: try optionalField(map["hour"], 0, 23, "hour"),
            minute: try optionalField(map["minute"], 0, 59, "minute"),
            second: try optionalField(map["second"], 0, 59, "second")
        )
        try validateMatch(match)
        return match
    }

    private static func optionalField(_ value: Any?, _ min: Int, _ max: Int, _ name: String) throws -> Int? {
        if value == nil { return nil }
        return try requireInt(value, min, max, name, "INVALID_SCHEDULE")
    }

    private static func validateMatch(_ match: CalendarMatch) throws {
        if match.year == nil && match.month == nil && match.day == nil && match.weekday == nil &&
            match.hour == nil && match.minute == nil && match.second == nil {
            throw NotificationFailure(code: "INVALID_SCHEDULE", message: "schedule.on must include at least one field.")
        }
    }

    private struct TimeMode {
        var minuteWild: Bool
        var hourWild: Bool
        var hour: Int
        var minute: Int
        var second: Int
    }

    private static func timeMode(_ match: CalendarMatch) -> TimeMode {
        let anyDate = match.year != nil || match.month != nil || match.day != nil || match.weekday != nil
        let minuteWild = match.minute == nil && match.hour == nil && match.second != nil && !anyDate
        let hourWild = match.hour == nil && (match.minute != nil || match.second != nil) && !anyDate
        return TimeMode(
            minuteWild: minuteWild,
            hourWild: hourWild,
            hour: match.hour ?? 0,
            minute: match.minute ?? 0,
            second: match.second ?? 0
        )
    }

    private static func nextSecond(_ date: Date, _ second: Int, _ calendar: Calendar) -> Date {
        var components = calendar.dateComponents([.year, .month, .day, .hour, .minute], from: date)
        components.second = second
        components.nanosecond = 0
        var candidate = calendar.date(from: components) ?? date
        if candidate <= date || calendar.component(.second, from: candidate) != second {
            candidate = calendar.date(byAdding: .minute, value: 1, to: candidate) ?? candidate.addingTimeInterval(60)
            var shifted = calendar.dateComponents([.year, .month, .day, .hour, .minute], from: candidate)
            shifted.second = second
            shifted.nanosecond = 0
            candidate = calendar.date(from: shifted) ?? candidate
        }
        return candidate
    }

    private static func nextMinute(_ date: Date, _ minute: Int, _ second: Int, _ calendar: Calendar) -> Date {
        var components = calendar.dateComponents([.year, .month, .day, .hour], from: date)
        components.minute = minute
        components.second = second
        components.nanosecond = 0
        var candidate = calendar.date(from: components) ?? date
        if candidate <= date || calendar.component(.minute, from: candidate) != minute {
            candidate = calendar.date(byAdding: .hour, value: 1, to: candidate) ?? candidate.addingTimeInterval(3600)
            var shifted = calendar.dateComponents([.year, .month, .day, .hour], from: candidate)
            shifted.minute = minute
            shifted.second = second
            shifted.nanosecond = 0
            candidate = calendar.date(from: shifted) ?? candidate
        }
        return candidate
    }

    private static func atLocalTime(_ day: Date, _ hour: Int, _ minute: Int, _ second: Int, _ calendar: Calendar) -> Date? {
        var components = calendar.dateComponents([.year, .month, .day], from: day)
        components.hour = hour
        components.minute = minute
        components.second = second
        components.nanosecond = 0
        guard let date = calendar.date(from: components) else { return nil }
        let roundTrip = calendar.dateComponents([.year, .month, .day, .hour, .minute, .second], from: date)
        guard roundTrip.year == components.year, roundTrip.month == components.month, roundTrip.day == components.day,
              roundTrip.hour == hour, roundTrip.minute == minute, roundTrip.second == second else {
            return nil
        }
        return date
    }

    private static func dateFieldsMatch(_ date: Date, _ match: CalendarMatch, _ calendar: Calendar) -> Bool {
        let parts = calendar.dateComponents([.year, .month, .day, .weekday], from: date)
        if let year = match.year, parts.year != year { return false }
        if let month = match.month, parts.month != month { return false }
        if let day = match.day, parts.day != day { return false }
        if let weekday = match.weekday, parts.weekday != weekday { return false }
        return true
    }

    private static func addMonthsOverflow(_ date: Date, _ count: Int, _ calendar: Calendar) -> Date {
        let day = calendar.component(.day, from: date)
        var parts = calendar.dateComponents([.year, .month, .hour, .minute, .second], from: date)
        parts.day = 1
        parts.nanosecond = 0
        guard let first = calendar.date(from: parts),
              let shifted = calendar.date(byAdding: .month, value: count, to: first) else {
            return date
        }
        return calendar.date(byAdding: .day, value: day - 1, to: shifted) ?? shifted
    }

    private static func addYearsOverflow(_ date: Date, _ count: Int, _ calendar: Calendar) -> Date {
        let day = calendar.component(.day, from: date)
        let month = calendar.component(.month, from: date)
        var parts = calendar.dateComponents([.year, .hour, .minute, .second], from: date)
        parts.month = 1
        parts.day = 1
        parts.nanosecond = 0
        guard let first = calendar.date(from: parts),
              let shiftedYear = calendar.date(byAdding: .year, value: count, to: first) else {
            return date
        }
        var monthParts = calendar.dateComponents([.year, .hour, .minute, .second], from: shiftedYear)
        monthParts.month = month
        monthParts.day = 1
        guard let monthDate = calendar.date(from: monthParts) else { return shiftedYear }
        return calendar.date(byAdding: .day, value: day - 1, to: monthDate) ?? monthDate
    }

    public static func parseAt(_ value: Any?) throws -> Date {
        if let date = value as? Date { return date }
        if let string = value as? String {
            guard string.range(of: #"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?(?:Z|[+-]\d{2}:\d{2})$"#, options: .regularExpression) != nil else {
                throw NotificationFailure(code: "INVALID_SCHEDULE", message: "schedule.at must be an ISO-8601 timestamp such as 2026-11-05T18:00:00.000Z.")
            }
            let fractional = ISO8601DateFormatter()
            fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            if let date = fractional.date(from: normalizeIso(string)) { return date }
            let plain = ISO8601DateFormatter()
            plain.formatOptions = [.withInternetDateTime]
            if let date = plain.date(from: string) { return date }
            throw NotificationFailure(code: "INVALID_SCHEDULE", message: "schedule.at is not a valid instant.")
        }
        if let number = value as? NSNumber, !(value is Bool) {
            return Date(timeIntervalSince1970: number.doubleValue / 1000)
        }
        throw NotificationFailure(code: "INVALID_SCHEDULE", message: "schedule.at must be an ISO-8601 timestamp such as 2026-11-05T18:00:00.000Z.")
    }

    private static func normalizeIso(_ value: String) -> String {
        guard let match = value.range(of: #"^(.*T\d{2}:\d{2}:\d{2})(?:\.(\d{1,3}))?(Z|[+-]\d{2}:\d{2})$"#, options: .regularExpression) else {
            return value
        }
        let text = String(value[match])
        if text.contains(".") { return padFraction(text) }
        if let zone = text.range(of: #"(Z|[+-]\d{2}:\d{2})$"#, options: .regularExpression) {
            let head = text[..<zone.lowerBound]
            return head + ".000" + text[zone]
        }
        return text
    }

    private static func padFraction(_ value: String) -> String {
        guard let dot = value.firstIndex(of: ".") else { return value }
        let zoneIndex = value[value.index(after: dot)...].firstIndex(where: { $0 == "Z" || $0 == "+" || $0 == "-" }) ?? value.endIndex
        let fraction = value[value.index(after: dot)..<zoneIndex]
        let padded = fraction.padding(toLength: 3, withPad: "0", startingAt: 0)
        return String(value[..<dot]) + "." + padded + value[zoneIndex...]
    }

    private static func parseExtra(_ value: Any?) throws -> String? {
        if value == nil { return nil }
        guard let map = value as? [String: Any] else {
            throw NotificationFailure(code: "INVALID_NOTIFICATION", message: "extra must be a plain object.")
        }
        let sanitized = try sanitize(map, 0, Counter())
        guard JSONSerialization.isValidJSONObject(sanitized),
              let data = try? JSONSerialization.data(withJSONObject: sanitized),
              let text = String(data: data, encoding: .utf8) else {
            throw NotificationFailure(code: "INVALID_NOTIFICATION", message: "extra only supports JSON values.")
        }
        if text.count > 4096 {
            throw NotificationFailure(code: "INVALID_NOTIFICATION", message: "extra must be at most 4096 characters once encoded.")
        }
        return text
    }

    private final class Counter { var count = 0 }

    private static func sanitize(_ value: Any, _ depth: Int, _ counter: Counter) throws -> Any {
        if let text = value as? String {
            if text.count > 500 {
                throw NotificationFailure(code: "INVALID_NOTIFICATION", message: "Strings inside extra must be at most 500 characters.")
            }
            return text
        }
        if value is Bool || value is Int || value is Double { return value }
        if let number = value as? NSNumber {
            let raw = number.doubleValue
            if raw.isNaN || raw.isInfinite {
                throw NotificationFailure(code: "INVALID_NOTIFICATION", message: "extra cannot contain NaN or Infinity.")
            }
            return number
        }
        if let list = value as? [Any] {
            if depth >= 5 || list.count > 20 {
                throw NotificationFailure(code: "INVALID_NOTIFICATION", message: "extra is too deep or has a list longer than 20 items.")
            }
            return try list.map { try sanitize($0, depth + 1, counter) }
        }
        if let map = value as? [String: Any] {
            if depth >= 5 {
                throw NotificationFailure(code: "INVALID_NOTIFICATION", message: "extra is nested more than 5 levels.")
            }
            var output: [String: Any] = [:]
            for (key, child) in map {
                if key.isEmpty || key.count > 64 || key == "__proto__" || key == "prototype" || key == "constructor" {
                    throw NotificationFailure(code: "INVALID_NOTIFICATION", message: "extra contains a key that is not allowed.")
                }
                counter.count += 1
                if counter.count > 50 {
                    throw NotificationFailure(code: "INVALID_NOTIFICATION", message: "extra can contain at most 50 keys.")
                }
                output[key] = try sanitize(child, depth + 1, counter)
            }
            return output
        }
        throw NotificationFailure(code: "INVALID_NOTIFICATION", message: "extra only supports JSON values.")
    }

    private static func parseSound(_ value: Any?) throws -> String? {
        if value == nil { return nil }
        guard let text = value as? String else {
            throw NotificationFailure(code: "INVALID_NOTIFICATION", message: "sound must be a string.")
        }
        if text.isEmpty || text == "default" { return text }
        if text.range(of: #"^[A-Za-z0-9_-]{1,100}\.[A-Za-z0-9]{1,8}$"#, options: .regularExpression) == nil {
            throw NotificationFailure(code: "INVALID_NOTIFICATION", message: "sound must be \"default\", an empty string, or a file name such as beep.wav.")
        }
        return text
    }

    private static func optionalScore(_ value: Any?) throws -> Double? {
        if value == nil { return nil }
        let number = (value as? NSNumber)?.doubleValue
        guard let number, number.isFinite, number >= 0, number <= 1 else {
            throw NotificationFailure(code: "INVALID_NOTIFICATION", message: "relevanceScore must be a number from 0 to 1.")
        }
        return number
    }

    private static func optionalLevel(_ value: Any?) throws -> String? {
        if value == nil { return nil }
        guard let text = value as? String, ["active", "critical", "passive", "timeSensitive"].contains(text) else {
            throw NotificationFailure(code: "INVALID_NOTIFICATION", message: "interruptionLevel is not a supported value.")
        }
        return text
    }

    private static func optionalText(_ value: Any?, _ max: Int, _ name: String, _ code: String) throws -> String? {
        if value == nil { return nil }
        return try requireText(value, 1, max, name, code)
    }

    private static func requireText(_ value: Any?, _ min: Int, _ max: Int, _ name: String, _ code: String) throws -> String {
        guard let raw = value as? String else {
            throw NotificationFailure(code: code, message: "\(name) must be a string.")
        }
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.count < min || text.count > max {
            throw NotificationFailure(code: code, message: "\(name) must be \(min) to \(max) characters.")
        }
        return text
    }

    private static func optionalToken(_ value: Any?, _ pattern: String, _ name: String, _ code: String) throws -> String? {
        if value == nil { return nil }
        return try requireToken(value, pattern, name, code)
    }

    private static func requireToken(_ value: Any?, _ pattern: String, _ name: String, _ code: String) throws -> String {
        guard let text = value as? String, text.range(of: pattern, options: .regularExpression) != nil else {
            throw NotificationFailure(code: code, message: "\(name) has an invalid format.")
        }
        return text
    }

    private static func optionalBool(_ value: Any?, _ fallback: Bool, _ name: String, _ code: String) throws -> Bool {
        if value == nil { return fallback }
        guard let bool = value as? Bool else {
            throw NotificationFailure(code: code, message: "\(name) must be a boolean.")
        }
        return bool
    }

    private static func requireInt(_ value: Any?, _ min: Int, _ max: Int, _ name: String, _ code: String) throws -> Int {
        if value is Bool {
            throw NotificationFailure(code: code, message: "\(name) must be an integer from \(min) to \(max).")
        }
        let number: Int?
        if let int = value as? Int {
            number = int
        } else if let raw = value as? NSNumber {
            let double = raw.doubleValue
            number = double.rounded() == double ? raw.intValue : nil
        } else {
            number = nil
        }
        guard let number, number >= min, number <= max else {
            throw NotificationFailure(code: code, message: "\(name) must be an integer from \(min) to \(max).")
        }
        return number
    }
}
