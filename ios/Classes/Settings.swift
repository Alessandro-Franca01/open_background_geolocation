import Foundation

/// Effective configuration (defaults + user overrides) and persisted tracking state.
final class Settings {
    static let shared = Settings()

    /// Keys whose value is replaced wholesale instead of deep-merged.
    private static let replaceKeys: Set<String> = [
        "headers", "params", "extras", "schedule", "triggerActivities", "refreshPayload", "refreshHeaders",
    ]

    private let store = UserDefaults.standard
    private let lock = NSLock()
    private var cfg: [String: Any]

    private init() {
        cfg = Settings.defaults()
        if let text = UserDefaults.standard.string(forKey: "obg.config"), let saved = Json.decode(text) {
            Settings.merge(&cfg, saved)
        }
    }

    private static func defaults() -> [String: Any] {
        let geolocation: [String: Any] = [
            "desiredAccuracy": "high",
            "distanceFilter": 10.0,
            "stationaryRadius": 150,
            "stopTimeout": 5,
            "locationUpdateInterval": 1000,
            "locationTimeout": 60,
            "stopAfterElapsedMinutes": 0,
            "disableElasticity": false,
            "elasticityMultiplier": 1.0,
            "useSignificantChangesOnly": false,
            "allowIdenticalLocations": false,
            "geofenceProximityRadius": 1000,
            "geofenceInitialTriggerEntry": true,
            "pausesLocationUpdatesAutomatically": false,
            "showsBackgroundLocationIndicator": false,
            "locationAuthorizationRequest": "Always",
            "filter": [
                "trackingAccuracyThreshold": 100.0,
                "odometerAccuracyThreshold": 100.0,
            ] as [String: Any],
        ]
        let activity: [String: Any] = [
            "minimumActivityRecognitionConfidence": 75,
            "motionTriggerDelay": 0,
            "disableStopDetection": false,
            "disableMotionActivityUpdates": false,
            "stopOnStationary": false,
        ]
        let http: [String: Any] = [
            "autoSync": true,
            "autoSyncThreshold": 0,
            "batchSync": false,
            "disableAutoSyncOnCellular": false,
            "maxBatchSize": -1,
            "method": "POST",
            "rootProperty": "location",
            "timeout": 60000,
        ]
        let persistence: [String: Any] = [
            "persistMode": "all",
            "maxDaysToPersist": 1,
            "maxRecordsToPersist": -1,
            "locationsOrderDirection": "ASC",
            "timestampFormat": "iso",
        ]
        let app: [String: Any] = [
            "stopOnTerminate": true,
            "startOnBoot": false,
            "enableHeadless": false,
            "heartbeatInterval": -1,
        ]
        let logger: [String: Any] = [
            "debug": false,
            "logLevel": "verbose",
            "logMaxDays": 3,
        ]
        return [
            "geolocation": geolocation,
            "activity": activity,
            "http": http,
            "persistence": persistence,
            "app": app,
            "authorization": [String: Any](),
            "logger": logger,
        ]
    }

    private static func merge(_ dst: inout [String: Any], _ src: [String: Any]) {
        for (key, value) in src {
            if let sub = value as? [String: Any], var existing = dst[key] as? [String: Any],
               !replaceKeys.contains(key) {
                merge(&existing, sub)
                dst[key] = existing
            } else {
                dst[key] = value
            }
        }
    }

    // MARK: configuration

    private func save() {
        if let text = Json.encode(cfg) { store.set(text, forKey: "obg.config") }
    }

    /// `reset == true` starts from defaults; otherwise the stored config is kept and merged.
    func ready(_ overrides: [String: Any], reset: Bool) {
        lock.lock(); defer { lock.unlock() }
        if reset { cfg = Settings.defaults() }
        var o = overrides
        o.removeValue(forKey: "reset")
        Settings.merge(&cfg, o)
        save()
    }

    func update(_ overrides: [String: Any]) {
        lock.lock(); defer { lock.unlock() }
        Settings.merge(&cfg, overrides)
        save()
    }

    func reset(_ overrides: [String: Any]?) {
        lock.lock(); defer { lock.unlock() }
        cfg = Settings.defaults()
        if var o = overrides {
            o.removeValue(forKey: "reset")
            Settings.merge(&cfg, o)
        }
        save()
    }

    // MARK: typed reads

    private func group(_ g: String) -> [String: Any] {
        lock.lock(); defer { lock.unlock() }
        return cfg[g] as? [String: Any] ?? [:]
    }

    func int(_ g: String, _ k: String, _ def: Int = 0) -> Int {
        return (group(g)[k] as? NSNumber)?.intValue ?? def
    }

    func long(_ g: String, _ k: String, _ def: Int64 = 0) -> Int64 {
        return (group(g)[k] as? NSNumber)?.int64Value ?? def
    }

    func double(_ g: String, _ k: String, _ def: Double = 0) -> Double {
        return (group(g)[k] as? NSNumber)?.doubleValue ?? def
    }

    func bool(_ g: String, _ k: String, _ def: Bool = false) -> Bool {
        return (group(g)[k] as? NSNumber)?.boolValue ?? def
    }

    func string(_ g: String, _ k: String, _ def: String = "") -> String {
        return group(g)[k] as? String ?? def
    }

    func dict(_ g: String, _ k: String) -> [String: Any]? {
        return group(g)[k] as? [String: Any]
    }

    func array(_ g: String, _ k: String) -> [Any]? {
        return group(g)[k] as? [Any]
    }

    /// A number nested one level deeper, e.g. `geolocation.filter.trackingAccuracyThreshold`.
    func nestedDouble(_ g: String, _ k: String, _ n: String, _ def: Double) -> Double {
        return (dict(g, k)?[n] as? NSNumber)?.doubleValue ?? def
    }

    // MARK: tracking state

    var enabled: Bool {
        get { return store.bool(forKey: "obg.enabled") }
        set { store.set(newValue, forKey: "obg.enabled") }
    }

    var isMoving: Bool {
        get { return store.bool(forKey: "obg.isMoving") }
        set { store.set(newValue, forKey: "obg.isMoving") }
    }

    /// 0 = geofences only, 1 = location + geofences.
    var trackingMode: Int {
        get { return store.object(forKey: "obg.trackingMode") == nil ? 1 : store.integer(forKey: "obg.trackingMode") }
        set { store.set(newValue, forKey: "obg.trackingMode") }
    }

    var odometer: Double {
        get { return store.double(forKey: "obg.odometer") }
        set { store.set(newValue, forKey: "obg.odometer") }
    }

    var schedulerEnabled: Bool {
        get { return store.bool(forKey: "obg.schedulerEnabled") }
        set { store.set(newValue, forKey: "obg.schedulerEnabled") }
    }

    /// True until the first call to `markBooted()` after installation.
    var isFirstBoot: Bool { return !store.bool(forKey: "obg.booted") }
    func markBooted() { store.set(true, forKey: "obg.booted") }

    func stateMap() -> [String: Any] {
        lock.lock()
        var m = cfg
        lock.unlock()
        m["enabled"] = enabled
        m["isMoving"] = isMoving
        m["trackingMode"] = trackingMode
        m["odometer"] = odometer
        m["schedulerEnabled"] = schedulerEnabled
        m["didDeviceReboot"] = false
        m["didLaunchInBackground"] = false
        m["isFirstBoot"] = isFirstBoot
        return m
    }
}
