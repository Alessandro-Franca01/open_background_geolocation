import Foundation

/// Pure token helpers, mirroring the Android `AuthLogic`.
enum AuthLogic {
    /// Refresh this many seconds before the token actually expires.
    static let expirySkewSeconds: Int64 = 60
    static let refreshPlaceholder = "{refreshToken}"

    struct Tokens {
        let accessToken: String
        let refreshToken: String?
        let expires: Int64
    }

    static func needsRefresh(expires: Int64, nowSec: Int64) -> Bool {
        return expires > 0 && nowSec + expirySkewSeconds >= expires
    }

    /// Accepts `accessToken` / `access_token`, `refreshToken` / `refresh_token` and either an
    /// absolute `expires` (epoch seconds or millis) or a relative `expires_in` / `expires`.
    static func parseTokenResponse(_ body: String, nowSec: Int64) -> Tokens? {
        guard let json = Json.decode(body) else { return nil }
        guard let access = firstString(json, ["accessToken", "access_token"]) else { return nil }
        let refresh = firstString(json, ["refreshToken", "refresh_token"])
        var expires: Int64 = -1
        if let rel = (json["expires_in"] as? NSNumber)?.int64Value {
            expires = nowSec + rel
        } else if let abs = (json["expires"] as? NSNumber)?.int64Value {
            expires = absoluteSeconds(abs, nowSec: nowSec)
        }
        return Tokens(accessToken: access, refreshToken: refresh, expires: expires)
    }

    private static func absoluteSeconds(_ value: Int64, nowSec: Int64) -> Int64 {
        if value > 100_000_000_000 { return value / 1000 }
        if value > 1_000_000_000 { return value }
        if value > 0 { return nowSec + value }
        return -1
    }

    private static func firstString(_ json: [String: Any], _ keys: [String]) -> String? {
        for k in keys {
            if let s = json[k] as? String, !s.isEmpty { return s }
        }
        return nil
    }

    static func substitute(_ template: [String: Any], refreshToken: String) -> [String: String] {
        var out: [String: String] = [:]
        for (k, v) in template {
            out[k] = "\(v)".replacingOccurrences(of: refreshPlaceholder, with: refreshToken)
        }
        return out
    }

    static func formEncode(_ fields: [String: String]) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        func enc(_ s: String) -> String { return s.addingPercentEncoding(withAllowedCharacters: allowed) ?? s }
        return fields.map { "\(enc($0.key))=\(enc($0.value))" }.joined(separator: "&")
    }

    static func bearer(strategy: String, accessToken: String) -> String? {
        return strategy.uppercased() == "JWT" && !accessToken.isEmpty ? "Bearer \(accessToken)" : nil
    }
}

/// Bearer-token authorization for HTTP sync. A refreshed pair is written back to the
/// `authorization` config group and announced with an `authorization` event.
enum Auth {
    private static let tag = "auth"
    private static let lock = NSLock()

    private static func now() -> Int64 { return Int64(Date().timeIntervalSince1970) }

    static func header() -> String? {
        let s = Settings.shared
        return AuthLogic.bearer(strategy: s.string("authorization", "strategy"),
                                accessToken: s.string("authorization", "accessToken"))
    }

    static func canRefresh() -> Bool {
        let s = Settings.shared
        return header() != nil
            && !s.string("authorization", "refreshUrl").isEmpty
            && !s.string("authorization", "refreshToken").isEmpty
    }

    /// Refreshes ahead of expiry. Returns false only when a needed refresh failed.
    static func refreshIfNeeded() -> Bool {
        guard canRefresh() else { return true }
        let expires = Settings.shared.long("authorization", "expires", -1)
        guard AuthLogic.needsRefresh(expires: expires, nowSec: now()) else { return true }
        ObgLogger.i(tag, "access token expired or about to; refreshing")
        return refresh()
    }

    /// Exchanges the refresh token for a new pair. Blocking: call from a background queue.
    static func refresh() -> Bool {
        lock.lock(); defer { lock.unlock() }
        let s = Settings.shared
        let urlText = s.string("authorization", "refreshUrl")
        let refreshToken = s.string("authorization", "refreshToken")
        guard !urlText.isEmpty, !refreshToken.isEmpty, let url = URL(string: urlText) else { return false }
        let template = s.dict("authorization", "refreshPayload") ?? ["refresh_token": AuthLogic.refreshPlaceholder]

        var request = URLRequest(url: url, timeoutInterval: TimeInterval(s.int("http", "timeout", 60000)) / 1000.0)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        s.dict("authorization", "refreshHeaders")?.forEach { request.setValue("\($0.value)", forHTTPHeaderField: $0.key) }
        request.httpBody = AuthLogic.formEncode(AuthLogic.substitute(template, refreshToken: refreshToken)).data(using: .utf8)

        let (status, text, error) = HttpClient.perform(request)
        guard error == nil else {
            ObgLogger.e(tag, "refresh error: \(error ?? "")")
            announce(false, error, text)
            return false
        }
        guard (200...299).contains(status), let tokens = AuthLogic.parseTokenResponse(text, nowSec: now()) else {
            ObgLogger.w(tag, "refresh failed: HTTP \(status)")
            announce(false, "Refresh failed (HTTP \(status))", text)
            return false
        }
        var update: [String: Any] = ["accessToken": tokens.accessToken, "expires": tokens.expires]
        // Servers that rotate refresh tokens return a new one; otherwise keep the current.
        if let r = tokens.refreshToken { update["refreshToken"] = r }
        s.update(["authorization": update])
        ObgLogger.i(tag, "token refreshed; expires=\(tokens.expires)")
        announce(true, nil, text)
        return true
    }

    private static func announce(_ success: Bool, _ error: String?, _ text: String) {
        EventBus.shared.emit("authorization", [
            "success": success,
            "error": error ?? NSNull(),
            "response": Json.decode(text) ?? [String: Any](),
        ])
    }
}
