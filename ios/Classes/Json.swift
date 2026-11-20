import Foundation

/// Small JSON helpers shared by the database, HTTP and settings code.
enum Json {
    static func encode(_ object: Any) -> String? {
        guard JSONSerialization.isValidJSONObject(object),
              let data = try? JSONSerialization.data(withJSONObject: object),
              let text = String(data: data, encoding: .utf8) else { return nil }
        return text
    }

    static func decode(_ text: String) -> [String: Any]? {
        guard let data = text.data(using: .utf8) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }
}

private let isoFormatter: ISO8601DateFormatter = {
    let f = ISO8601DateFormatter()
    f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    return f
}()

/// `2026-01-01T00:00:00.000Z`, the timestamp format used in every payload.
func isoTimestamp(_ date: Date) -> String {
    return isoFormatter.string(from: date)
}

func isoTimestamp(millis: Int64) -> String {
    return isoTimestamp(Date(timeIntervalSince1970: TimeInterval(millis) / 1000.0))
}

func nowMillis() -> Int64 {
    return Int64(Date().timeIntervalSince1970 * 1000)
}
