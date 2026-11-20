import Foundation

struct LogRow {
    let ts: Int64
    let level: String
    let tag: String
    let message: String
}

/// Level filtering and line formatting, mirroring the Android `LogLevels`.
enum LogLevels {
    private static let order = ["off", "error", "warning", "info", "debug", "verbose"]

    static func rank(_ level: String) -> Int {
        return order.firstIndex(of: level.lowercased()) ?? (order.count - 1)
    }

    /// True when a message at `level` should be recorded under the `configured` threshold.
    static func enabled(_ level: String, _ configured: String) -> Bool {
        let threshold = rank(configured)
        if threshold == 0 { return false }
        let r = rank(level)
        return r >= 1 && r <= threshold
    }

    static func format(_ row: LogRow) -> String {
        return "\(isoTimestamp(millis: row.ts)) \(row.level.uppercased()) [\(row.tag)] \(row.message)"
    }
}

/// Persistent diagnostic log filtered by `logger.logLevel`. Writes happen on a background queue.
enum ObgLogger {
    private static let queue = DispatchQueue(label: "dev.openbg.logger")
    private static var sinceLastPurge = 0

    static func e(_ tag: String, _ msg: String) { log("error", tag, msg) }
    static func w(_ tag: String, _ msg: String) { log("warning", tag, msg) }
    static func i(_ tag: String, _ msg: String) { log("info", tag, msg) }
    static func d(_ tag: String, _ msg: String) { log("debug", tag, msg) }
    static func v(_ tag: String, _ msg: String) { log("verbose", tag, msg) }

    static func log(_ level: String, _ tag: String, _ msg: String) {
        let configured = Settings.shared.string("logger", "logLevel", "verbose")
        guard LogLevels.enabled(level, configured) else { return }
        if Settings.shared.bool("logger", "debug") { NSLog("[OBG/%@] %@", tag, msg) }
        let ts = nowMillis()
        queue.async {
            Db.shared.logInsert(ts: ts, level: level.lowercased(), tag: tag, message: msg)
            sinceLastPurge += 1
            if sinceLastPurge >= 500 {
                sinceLastPurge = 0
                purge()
            }
        }
    }

    /// Drops lines older than `logger.logMaxDays`.
    static func purge() {
        Db.shared.logPurge(maxDays: Settings.shared.int("logger", "logMaxDays", 3))
    }

    static func get(order: String, limit: Int?, start: Int64?, end: Int64?) -> String {
        return Db.shared.logQuery(order: order, limit: limit, start: start, end: end)
            .map { LogLevels.format($0) }
            .joined(separator: "\n")
    }

    static func clear() {
        Db.shared.logDeleteAll()
    }
}
