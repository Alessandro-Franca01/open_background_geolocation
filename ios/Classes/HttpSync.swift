import Foundation
import UIKit

/// Synchronous HTTP helper for use on background queues.
enum HttpClient {
    /// Returns (status, body, error). `error` is non-nil only for transport failures.
    static func perform(_ request: URLRequest) -> (Int, String, String?) {
        var status = 0
        var body = ""
        var failure: String?
        let semaphore = DispatchSemaphore(value: 0)
        let task = URLSession.shared.dataTask(with: request) { data, response, error in
            if let error = error {
                failure = error.localizedDescription
            } else {
                status = (response as? HTTPURLResponse)?.statusCode ?? 0
                if let d = data { body = String(data: d, encoding: .utf8) ?? "" }
            }
            semaphore.signal()
        }
        task.resume()
        semaphore.wait()
        return (status, body, failure)
    }
}

/// Uploads queued locations to `http.url`. A 2xx response deletes the uploaded records; anything
/// else leaves them queued for the next attempt. Runs on a single serial queue so uploads never
/// overlap.
enum HttpSync {
    private static let tag = "http"
    private static let queue = DispatchQueue(label: "dev.openbg.http")
    private static let lock = NSLock()
    private static var queued = false

    /// Uploads if `autoSync` is on, `url` is set and the threshold / network constraints allow.
    static func autoSync() {
        let s = Settings.shared
        guard s.bool("http", "autoSync", true) else { return }
        guard !s.string("http", "url").isEmpty else { return }
        guard Db.shared.count() >= s.int("http", "autoSyncThreshold", 0) else { return }
        if s.bool("http", "disableAutoSyncOnCellular") && TrackingEngine.isOnExpensiveNetwork { return }
        lock.lock()
        let already = queued
        queued = true
        lock.unlock()
        if already { return } // a run is already waiting; it will drain everything
        queue.async { _ = run() }
    }

    /// Manual sync. The completion runs on the main queue with the uploaded records and an error.
    static func sync(_ completion: @escaping ([[String: Any]], String?) -> Void) {
        if Settings.shared.string("http", "url").isEmpty {
            completion([], "http.url is not configured")
            return
        }
        queue.async {
            let (uploaded, error) = run()
            DispatchQueue.main.async { completion(uploaded, error) }
        }
    }

    private static func run() -> ([[String: Any]], String?) {
        lock.lock(); queued = false; lock.unlock()
        // Ask for extra time so an upload that began in the foreground can finish after suspension.
        var bg = UIBackgroundTaskIdentifier.invalid
        DispatchQueue.main.sync {
            bg = UIApplication.shared.beginBackgroundTask(withName: "obg-sync") {}
        }
        defer {
            DispatchQueue.main.async { UIApplication.shared.endBackgroundTask(bg) }
        }

        let s = Settings.shared
        let urlText = s.string("http", "url")
        let batch = s.bool("http", "batchSync")
        let maxBatch = s.int("http", "maxBatchSize", -1)
        let order = s.string("persistence", "locationsOrderDirection", "ASC")
        let limit: Int? = batch ? (maxBatch > 0 ? maxBatch : nil) : 100

        var uploaded: [[String: Any]] = []
        var error: String?
        outer: while true {
            let rows = Db.shared.query(order: order, limit: limit, offset: nil)
            if rows.isEmpty { break }
            let groups: [[[String: Any]]] = batch ? [rows] : rows.map { [$0] }
            for group in groups {
                if !post(urlText, group, batch) {
                    error = "Upload failed"
                    break outer
                }
                for r in group { if let id = r["uuid"] as? String { _ = Db.shared.delete(uuid: id) } }
                uploaded.append(contentsOf: group)
            }
        }
        ObgLogger.i(tag, "sync finished: uploaded=\(uploaded.count) error=\(error ?? "none") pending=\(Db.shared.count())")
        return (uploaded, error)
    }

    /// Builds the JSON body: `{rootProperty: record | [records], ...params}`.
    static func buildBody(_ records: [[String: Any]], batch: Bool) -> [String: Any] {
        let s = Settings.shared
        let root = s.string("http", "rootProperty", "location")
        var body: [String: Any] = [:]
        if root == "." {
            if batch { body["location"] = records } else if let first = records.first { body = first }
        } else {
            body[root] = batch ? records as Any : (records.first as Any)
        }
        s.dict("http", "params")?.forEach { body[$0.key] = $0.value }
        return body
    }

    /// Refreshes an expiring token first; on a 401 refreshes once and retries.
    private static func post(_ urlText: String, _ records: [[String: Any]], _ batch: Bool) -> Bool {
        _ = Auth.refreshIfNeeded()
        var status = send(urlText, records, batch)
        if status == 401 && Auth.canRefresh() {
            ObgLogger.i(tag, "HTTP 401; refreshing the access token and retrying once")
            if Auth.refresh() { status = send(urlText, records, batch) }
        }
        return (200...299).contains(status)
    }

    private static func send(_ urlText: String, _ records: [[String: Any]], _ batch: Bool) -> Int {
        guard let url = URL(string: urlText) else {
            EventBus.shared.emit("http", ["success": false, "status": 0, "responseText": "Invalid http.url"])
            return 0
        }
        let s = Settings.shared
        var request = URLRequest(url: url, timeoutInterval: TimeInterval(s.int("http", "timeout", 60000)) / 1000.0)
        request.httpMethod = s.string("http", "method", "POST").uppercased()
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        // Set before the user's headers so an explicit `Authorization` header still wins.
        if let auth = Auth.header() { request.setValue(auth, forHTTPHeaderField: "Authorization") }
        s.dict("http", "headers")?.forEach { request.setValue("\($0.value)", forHTTPHeaderField: $0.key) }
        request.httpBody = Json.encode(buildBody(records, batch: batch))?.data(using: .utf8)

        let (status, text, failure) = HttpClient.perform(request)
        if let failure = failure {
            ObgLogger.e(tag, "upload error: \(failure)")
            EventBus.shared.emit("http", ["success": false, "status": 0, "responseText": failure])
            return 0
        }
        ObgLogger.i(tag, "POST \(records.count) record(s) -> HTTP \(status)")
        EventBus.shared.emit("http", ["success": (200...299).contains(status), "status": status, "responseText": text])
        return status
    }
}
