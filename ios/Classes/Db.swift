import Foundation
import SQLite3

private let sqliteTransient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

/// Local store for recorded locations, geofences and log lines (SQLite via the C API).
/// Every operation runs on one serial queue, so it is safe to call from any thread.
final class Db {
    static let shared = Db()

    private var handle: OpaquePointer?
    private let queue = DispatchQueue(label: "dev.openbg.db")

    private init() {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let path = dir.appendingPathComponent("obg.db").path
        guard sqlite3_open(path, &handle) == SQLITE_OK else { return }
        _ = run("CREATE TABLE IF NOT EXISTS locations (id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT UNIQUE NOT NULL, ts INTEGER NOT NULL, json TEXT NOT NULL)")
        _ = run("CREATE TABLE IF NOT EXISTS geofences (identifier TEXT PRIMARY KEY NOT NULL, json TEXT NOT NULL)")
        _ = run("CREATE TABLE IF NOT EXISTS logs (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, level TEXT NOT NULL, tag TEXT NOT NULL, message TEXT NOT NULL)")
    }

    // MARK: low level

    private func bind(_ stmt: OpaquePointer?, _ values: [Any?]) {
        for (i, v) in values.enumerated() {
            let idx = Int32(i + 1)
            switch v {
            case let s as String: sqlite3_bind_text(stmt, idx, s, -1, sqliteTransient)
            case let n as Int64: sqlite3_bind_int64(stmt, idx, n)
            case let n as Int: sqlite3_bind_int64(stmt, idx, Int64(n))
            default: sqlite3_bind_null(stmt, idx)
            }
        }
    }

    /// Runs a statement that returns no rows. Returns the number of rows changed.
    /// Must be called on `queue` (or during init, before the object is shared).
    @discardableResult
    private func run(_ sql: String, _ binds: [Any?] = []) -> Int {
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else { return 0 }
        defer { sqlite3_finalize(stmt) }
        bind(stmt, binds)
        guard sqlite3_step(stmt) == SQLITE_DONE else { return 0 }
        return Int(sqlite3_changes(handle))
    }

    private func rows<T>(_ sql: String, _ binds: [Any?] = [], _ read: (OpaquePointer?) -> T?) -> [T] {
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else { return [] }
        defer { sqlite3_finalize(stmt) }
        bind(stmt, binds)
        var out: [T] = []
        while sqlite3_step(stmt) == SQLITE_ROW {
            if let item = read(stmt) { out.append(item) }
        }
        return out
    }

    private static func text(_ stmt: OpaquePointer?, _ col: Int32) -> String {
        guard let c = sqlite3_column_text(stmt, col) else { return "" }
        return String(cString: c)
    }

    // MARK: locations

    func insert(uuid: String, ts: Int64, json: [String: Any]) {
        guard let text = Json.encode(json) else { return }
        queue.sync {
            _ = run("INSERT OR REPLACE INTO locations (uuid, ts, json) VALUES (?, ?, ?)", [uuid, ts, text])
        }
    }

    func query(order: String, limit: Int?, offset: Int?) -> [[String: Any]] {
        let dir = order.uppercased() == "DESC" ? "DESC" : "ASC"
        var sql = "SELECT json FROM locations ORDER BY id \(dir)"
        if let l = limit, l > 0 { sql += " LIMIT \(l) OFFSET \(offset ?? 0)" }
        return queue.sync {
            rows(sql) { stmt in Json.decode(Db.text(stmt, 0)) }
        }
    }

    func count() -> Int {
        return queue.sync {
            rows("SELECT COUNT(*) FROM locations") { stmt in Int(sqlite3_column_int64(stmt, 0)) }.first ?? 0
        }
    }

    func delete(uuid: String) -> Bool {
        return queue.sync { run("DELETE FROM locations WHERE uuid = ?", [uuid]) > 0 }
    }

    func deleteAll() {
        queue.sync { _ = run("DELETE FROM locations") }
    }

    /// Applies `maxDaysToPersist` / `maxRecordsToPersist` retention.
    func purge(maxDays: Int, maxRecords: Int) {
        queue.sync {
            if maxDays > 0 {
                let cutoff = nowMillis() - Int64(maxDays) * 86_400_000
                _ = run("DELETE FROM locations WHERE ts < ?", [cutoff])
            }
            if maxRecords > 0 {
                _ = run("DELETE FROM locations WHERE id NOT IN (SELECT id FROM locations ORDER BY id DESC LIMIT \(maxRecords))")
            }
        }
    }

    // MARK: geofences

    func geofencePut(_ identifier: String, _ json: [String: Any]) {
        guard let text = Json.encode(json) else { return }
        queue.sync { _ = run("INSERT OR REPLACE INTO geofences (identifier, json) VALUES (?, ?)", [identifier, text]) }
    }

    func geofenceGet(_ identifier: String) -> [String: Any]? {
        return queue.sync {
            rows("SELECT json FROM geofences WHERE identifier = ?", [identifier]) { stmt in
                Json.decode(Db.text(stmt, 0))
            }.first
        }
    }

    func geofenceAll() -> [[String: Any]] {
        return queue.sync { rows("SELECT json FROM geofences") { stmt in Json.decode(Db.text(stmt, 0)) } }
    }

    func geofenceDelete(_ identifier: String) {
        queue.sync { _ = run("DELETE FROM geofences WHERE identifier = ?", [identifier]) }
    }

    func geofenceDeleteAll() {
        queue.sync { _ = run("DELETE FROM geofences") }
    }

    // MARK: logs

    func logInsert(ts: Int64, level: String, tag: String, message: String) {
        queue.sync {
            _ = run("INSERT INTO logs (ts, level, tag, message) VALUES (?, ?, ?, ?)", [ts, level, tag, message])
        }
    }

    func logQuery(order: String, limit: Int?, start: Int64?, end: Int64?) -> [LogRow] {
        let dir = order.uppercased() == "DESC" ? "DESC" : "ASC"
        var clauses: [String] = []
        var binds: [Any?] = []
        if let s = start { clauses.append("ts >= ?"); binds.append(s) }
        if let e = end { clauses.append("ts <= ?"); binds.append(e) }
        var sql = "SELECT ts, level, tag, message FROM logs"
        if !clauses.isEmpty { sql += " WHERE " + clauses.joined(separator: " AND ") }
        sql += " ORDER BY id \(dir)"
        if let l = limit, l > 0 { sql += " LIMIT \(l)" }
        return queue.sync {
            rows(sql, binds) { stmt in
                LogRow(
                    ts: sqlite3_column_int64(stmt, 0),
                    level: Db.text(stmt, 1),
                    tag: Db.text(stmt, 2),
                    message: Db.text(stmt, 3)
                )
            }
        }
    }

    func logPurge(maxDays: Int) {
        guard maxDays > 0 else { return }
        let cutoff = nowMillis() - Int64(maxDays) * 86_400_000
        queue.sync { _ = run("DELETE FROM logs WHERE ts < ?", [cutoff]) }
    }

    func logDeleteAll() {
        queue.sync { _ = run("DELETE FROM logs") }
    }
}
