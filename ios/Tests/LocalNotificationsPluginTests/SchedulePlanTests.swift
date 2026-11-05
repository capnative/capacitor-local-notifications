import XCTest
@testable import LocalNotificationsPlugin

final class SchedulePlanTests: XCTestCase {
    private let now = ISO8601DateFormatter().date(from: "2026-11-05T15:30:45Z")!

    override func setUp() {
        super.setUp()
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        self.calendar = calendar
    }

    private var calendar = Calendar(identifier: .gregorian)

    func testDatedNotificationAndFixedRepeat() throws {
        let at = "2026-11-05T16:00:00.000Z"
        let once = try notification(["id": 1, "title": "Later", "body": "Body", "schedule": ["at": at]])
        XCTAssertEqual(once.schedule?.at, at)
        if case .once(let date) = once.plan {
            XCTAssertEqual(SchedulePlan.iso(date), at)
        } else {
            XCTFail("expected a one-shot plan")
        }

        let repeating = try notification([
            "id": 2,
            "title": "Repeat",
            "body": "Body",
            "schedule": ["at": "2026-11-05T15:32:45.000Z", "repeats": true],
        ])
        if case .fixedInterval(_, let interval, _) = repeating.plan {
            XCTAssertEqual(interval, 120, accuracy: 0.001)
        } else {
            XCTFail("expected a fixed interval")
        }
    }

    func testCalendarAndMonthOverflow() throws {
        let next = try SchedulePlan.nextCalendar(CalendarMatch(hour: 9), after: now, calendar: calendar)
        XCTAssertEqual(SchedulePlan.iso(next), "2026-11-06T09:00:00.000Z")
        let monday = try SchedulePlan.nextCalendar(CalendarMatch(weekday: 2, hour: 9, minute: 0), after: now, calendar: calendar)
        XCTAssertEqual(SchedulePlan.iso(monday), "2026-11-09T09:00:00.000Z")
        let start = ISO8601DateFormatter().date(from: "2026-01-31T00:00:00Z")!
        XCTAssertEqual(SchedulePlan.iso(SchedulePlan.addEvery(start, .month, 1, calendar: calendar)), "2026-03-03T00:00:00.000Z")
    }

    func testRejectsInvalidInput() {
        XCTAssertEqual(code(["id": 1, "title": " ", "body": "B"]), "INVALID_NOTIFICATION")
        XCTAssertEqual(code(["id": 1, "title": "A", "body": "B", "sound": "../beep.wav"]), "INVALID_NOTIFICATION")
        XCTAssertEqual(code(["id": 1, "title": "A", "body": "B", "schedule": ["on": [String: Any]()]]), "INVALID_SCHEDULE")
        XCTAssertEqual(code(["id": 1, "title": "A", "body": "B", "schedule": ["every": "second", "count": 30]]), "INVALID_SCHEDULE")
        XCTAssertEqual(
            code(["id": 1, "title": "A", "body": "B", "schedule": ["at": "2026-11-05T15:00:00.000Z"]]),
            "INVALID_SCHEDULE"
        )
    }

    private func notification(_ value: [String: Any]) throws -> ValidatedNotification {
        try SchedulePlan.parseNotifications([value], now: now, calendar: calendar)[0]
    }

    private func code(_ value: [String: Any]) -> String? {
        do {
            _ = try notification(value)
            return nil
        } catch let error as NotificationFailure {
            return error.code
        } catch {
            return nil
        }
    }
}
