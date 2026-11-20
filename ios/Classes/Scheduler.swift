import Foundation

/// Parsing and evaluation of `AppConfig.schedule` entries, mirroring the Android `ScheduleLogic`.
///
/// Entry format: `"<days> <HH:mm>-<HH:mm> [geofence]"`. Days are 1...7 with **1 = Sunday**, as a
/// range (`2-6`), a list (`1,7`) or a mix. Start is inclusive and end exclusive; `24:00` is a valid
/// end and an end before the start crosses midnight.
enum ScheduleLogic {
    static let off = 0
    static let location = 1
    static let geofence = 2

    private static let day = 1440
    private static let week = 7 * 1440

    struct Window {
        let days: Set<Int>
        let startMin: Int
        let endMin: Int
        let geofenceOnly: Bool
        var crossesMidnight: Bool { return endMin < startMin }
    }

    /// Valid entries only; malformed ones are skipped.
    static func parse(_ entries: [String]) -> [Window] {
        return entries.compactMap { parseEntry($0) }
    }

    static func parseEntry(_ entry: String) -> Window? {
        let parts = entry.split(whereSeparator: { $0 == " " || $0 == "\t" }).map(String.init)
        guard parts.count == 2 || parts.count == 3 else { return nil }
        guard let days = parseDays(parts[0]) else { return nil }
        let times = parts[1].components(separatedBy: "-")
        guard times.count == 2,
              let start = parseTime(times[0], allowTwentyFour: false),
              let end = parseTime(times[1], allowTwentyFour: true),
              start != end else { return nil }
        var geofenceOnly = false
        if parts.count == 3 {
            guard parts[2].lowercased() == "geofence" else { return nil }
            geofenceOnly = true
        }
        return Window(days: days, startMin: start, endMin: end, geofenceOnly: geofenceOnly)
    }

    static func parseDays(_ text: String) -> Set<Int>? {
        var out = Set<Int>()
        for token in text.components(separatedBy: ",") {
            let range = token.components(separatedBy: "-")
            guard range.count <= 2, let from = Int(range[0]) else { return nil }
            let to: Int
            if range.count == 1 {
                to = from
            } else {
                guard let t = Int(range[1]) else { return nil }
                to = t
            }
            guard (1...7).contains(from), (1...7).contains(to) else { return nil }
            if from <= to {
                (from...to).forEach { out.insert($0) }
            } else {
                (from...7).forEach { out.insert($0) }
                (1...to).forEach { out.insert($0) }
            }
        }
        return out
    }

    private static func parseTime(_ text: String, allowTwentyFour: Bool) -> Int? {
        let hm = text.components(separatedBy: ":")
        guard hm.count == 2, let h = Int(hm[0]), let m = Int(hm[1]), (0...59).contains(m) else { return nil }
        if h == 24 && m == 0 && allowTwentyFour { return day }
        guard (0...23).contains(h) else { return nil }
        return h * 60 + m
    }

    private static func previousDay(_ dow: Int) -> Int { return dow == 1 ? 7 : dow - 1 }

    private static func active(_ w: Window, _ dow: Int, _ minute: Int) -> Bool {
        if !w.crossesMidnight {
            return w.days.contains(dow) && minute >= w.startMin && minute < w.endMin
        }
        return (w.days.contains(dow) && minute >= w.startMin)
            || (w.days.contains(previousDay(dow)) && minute < w.endMin)
    }

    /// What the schedule wants at `dow` (1 = Sunday) and `minuteOfDay`: off, location or geofence.
    static func modeAt(_ windows: [Window], _ dow: Int, _ minuteOfDay: Int) -> Int {
        var mode = off
        for w in windows where active(w, dow, minuteOfDay) {
            if !w.geofenceOnly { return location }
            mode = geofence
        }
        return mode
    }

    /// Minutes from now until the desired mode next changes, or nil if it never does within a week.
    static func minutesUntilChange(_ windows: [Window], _ dow: Int, _ minuteOfDay: Int) -> Int? {
        if windows.isEmpty { return nil }
        let current = modeAt(windows, dow, minuteOfDay)
        var d = dow
        var m = minuteOfDay
        for step in 1...week {
            m += 1
            if m == day { m = 0; d = d == 7 ? 1 : d + 1 }
            if modeAt(windows, d, m) != current { return step }
        }
        return nil
    }
}

/// Turns tracking on and off following `AppConfig.schedule`.
///
/// iOS cannot wake a suspended app on a timer, so the next boundary is armed with a `Timer` that
/// only fires while the app is running (tracking keeps it alive in the background). The schedule
/// is also re-evaluated whenever the app becomes active or is relaunched by a location event.
final class Scheduler {
    static let shared = Scheduler()
    private let tag = "schedule"
    private var timer: Timer?

    private init() {}

    private func windows() -> [ScheduleLogic.Window] {
        let raw = (Settings.shared.array("app", "schedule") ?? []).compactMap { $0 as? String }
        let parsed = ScheduleLogic.parse(raw)
        if parsed.count != raw.count {
            ObgLogger.w(tag, "\(raw.count - parsed.count) malformed schedule entr(ies) ignored")
        }
        return parsed
    }

    /// Enables the scheduler. Returns false (and stays off) when no valid schedule is configured.
    @discardableResult
    func start() -> Bool {
        guard !windows().isEmpty else {
            ObgLogger.w(tag, "startSchedule ignored: app.schedule has no valid entries")
            return false
        }
        Settings.shared.schedulerEnabled = true
        ObgLogger.i(tag, "scheduler started")
        evaluate()
        return true
    }

    /// Stops scheduling. Tracking that is already running is left alone.
    func stop() {
        Settings.shared.schedulerEnabled = false
        timer?.invalidate()
        timer = nil
        ObgLogger.i(tag, "scheduler stopped")
    }

    /// Applies the current window's mode and arms a timer for the next change.
    func evaluate() {
        guard Settings.shared.schedulerEnabled else { return }
        let list = windows()
        let cal = Calendar.current
        let now = Date()
        let dow = cal.component(.weekday, from: now) // 1 = Sunday, like the schedule format
        let minute = cal.component(.hour, from: now) * 60 + cal.component(.minute, from: now)
        let mode = ScheduleLogic.modeAt(list, dow, minute)
        let settings = Settings.shared
        ObgLogger.i(tag, "evaluate: day=\(dow) minute=\(minute) mode=\(mode) (enabled=\(settings.enabled))")

        switch mode {
        case ScheduleLogic.location:
            if !settings.enabled || settings.trackingMode != 1 { TrackingEngine.shared.start() }
        case ScheduleLogic.geofence:
            if !settings.enabled || settings.trackingMode != 0 { TrackingEngine.shared.startGeofences() }
        default:
            if settings.enabled { TrackingEngine.shared.stop() }
        }
        EventBus.shared.emit("schedule", settings.stateMap())

        timer?.invalidate()
        timer = nil
        guard let minutes = ScheduleLogic.minutesUntilChange(list, dow, minute) else { return }
        // Boundaries sit on whole minutes: aim for the start of the target minute.
        let seconds = Double(minutes) * 60.0 - Double(cal.component(.second, from: now))
        timer = Timer.scheduledTimer(withTimeInterval: max(seconds, 1), repeats: false) { [weak self] _ in
            self?.evaluate()
        }
        ObgLogger.d(tag, "next change in \(minutes) min")
    }
}
