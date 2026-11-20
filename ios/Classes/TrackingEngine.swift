import CoreLocation
import Foundation
import Network
import UIKit

/// One-shot position request. Reports once, either a fix or an error, and then goes quiet.
final class OneShotLocator: NSObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private var done = false
    private var timeoutItem: DispatchWorkItem?
    private let completion: (OneShotLocator, CLLocation?, String?) -> Void

    init(timeout: TimeInterval, accuracy: CLLocationAccuracy,
         completion: @escaping (OneShotLocator, CLLocation?, String?) -> Void) {
        self.completion = completion
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = accuracy
        let item = DispatchWorkItem { [weak self] in self?.finish(nil, "Location timeout") }
        timeoutItem = item
        DispatchQueue.main.asyncAfter(deadline: .now() + timeout, execute: item)
        manager.requestLocation()
    }

    private func finish(_ loc: CLLocation?, _ error: String?) {
        guard !done else { return }
        done = true
        timeoutItem?.cancel()
        manager.delegate = nil
        completion(self, loc, error)
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        if let last = locations.last { finish(last, nil) }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        finish(nil, error.localizedDescription)
    }
}

/// An independent position stream for `watchPosition`. It never touches the main tracking state.
final class Watcher: NSObject, CLLocationManagerDelegate {
    let id: Int
    private let manager = CLLocationManager()
    private let minInterval: TimeInterval
    private var lastDelivery: Date?
    private var timeoutItem: DispatchWorkItem?
    private let onLocation: (Int, CLLocation) -> Void
    private let onError: (Int, Int) -> Void

    init(id: Int, intervalMs: Int, accuracy: CLLocationAccuracy, timeoutSeconds: Int,
         onLocation: @escaping (Int, CLLocation) -> Void, onError: @escaping (Int, Int) -> Void) {
        self.id = id
        self.minInterval = TimeInterval(intervalMs) / 1000.0
        self.onLocation = onLocation
        self.onError = onError
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = accuracy
        manager.distanceFilter = kCLDistanceFilterNone
        if timeoutSeconds > 0 {
            let item = DispatchWorkItem { [weak self] in
                guard let self = self else { return }
                self.onError(self.id, 408) // no first fix within the timeout
            }
            timeoutItem = item
            DispatchQueue.main.asyncAfter(deadline: .now() + .seconds(timeoutSeconds), execute: item)
        }
        manager.startUpdatingLocation()
    }

    func stop() {
        timeoutItem?.cancel()
        manager.stopUpdatingLocation()
        manager.delegate = nil
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        timeoutItem?.cancel()
        for loc in locations {
            let now = Date()
            if let last = lastDelivery, now.timeIntervalSince(last) < minInterval { continue }
            lastDelivery = now
            onLocation(id, loc)
        }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        let code = (error as NSError).code
        if code != CLError.locationUnknown.rawValue { onError(id, code) }
    }
}

/// Core tracking logic.
///
/// State machine:
///  - MOVING: continuous location updates filtered by distance. Core Motion reporting "still" arms a
///    timer (`stopTimeout` minutes); when it fires the engine goes STATIONARY.
///  - STATIONARY: location updates are off. A monitored region around the last fix wakes the engine
///    (even from a terminated app) when the device leaves it.
final class TrackingEngine: NSObject, CLLocationManagerDelegate {
    static let shared = TrackingEngine()
    static let stationaryRegionId = "obg_stationary"

    /// Updated by the path monitor, read from the HTTP queue.
    static var isOnExpensiveNetwork = false

    let manager = CLLocationManager()
    private let tag = "engine"
    private let motion = Motion()

    private var running = false
    private var moving = false
    private var pendingMotionChange = false
    private(set) var lastKnown: CLLocation?
    private var lastActivity: (name: String, confidence: Int) = ("unknown", 0)
    private var elasticityFactor = 1.0

    private var stopWorkItem: DispatchWorkItem?
    private var stopAfterItem: DispatchWorkItem?
    private var heartbeatTimer: Timer?
    private var pathMonitor: NWPathMonitor?
    private var lastConnected: Bool?

    private var authStatusCallbacks: [(Int) -> Void] = []
    private var authTimeout: DispatchWorkItem?

    private var oneShots: [ObjectIdentifier: OneShotLocator] = [:]
    private var watchers: [Int: Watcher] = [:]
    private var nextWatchId = 1
    private var backgroundTasks: [Int: UIBackgroundTaskIdentifier] = [:]

    private override init() {
        super.init()
        manager.delegate = self
    }

    // MARK: authorization

    /// 0 notDetermined, 1 restricted, 2 denied, 3 always, 4 whenInUse (CLAuthorizationStatus order).
    var authorizationStatus: Int {
        let raw: CLAuthorizationStatus
        if #available(iOS 14.0, *) { raw = manager.authorizationStatus } else { raw = CLLocationManager.authorizationStatus() }
        switch raw {
        case .notDetermined: return 0
        case .restricted: return 1
        case .denied: return 2
        case .authorizedAlways: return 3
        case .authorizedWhenInUse: return 4
        @unknown default: return 0
        }
    }

    var hasAuthorization: Bool {
        let s = authorizationStatus
        return s == 3 || s == 4
    }

    func providerState() -> [String: Any] {
        let services = CLLocationManager.locationServicesEnabled()
        var accuracy = 1
        if #available(iOS 14.0, *) { accuracy = manager.accuracyAuthorization == .fullAccuracy ? 1 : 0 }
        return [
            "enabled": services && hasAuthorization,
            "gps": services,
            "network": services,
            "status": authorizationStatus,
            "accuracyAuthorization": accuracy,
        ]
    }

    /// Asks for location authorization and reports the resulting status code.
    func requestPermission(_ completion: @escaping (Int) -> Void) {
        let wantsAlways = Settings.shared.string("geolocation", "locationAuthorizationRequest", "Always") != "WhenInUse"
        let status = authorizationStatus
        if status == 0 {
            authStatusCallbacks.append(completion)
            if wantsAlways { manager.requestAlwaysAuthorization() } else { manager.requestWhenInUseAuthorization() }
            armAuthTimeout(30)
        } else if status == 4 && wantsAlways {
            // Upgrade prompt; iOS may not call back if it decides not to show it, hence the timeout.
            authStatusCallbacks.append(completion)
            manager.requestAlwaysAuthorization()
            armAuthTimeout(10)
        } else {
            completion(status)
        }
    }

    private func armAuthTimeout(_ seconds: TimeInterval) {
        authTimeout?.cancel()
        let item = DispatchWorkItem { [weak self] in self?.finishAuth() }
        authTimeout = item
        DispatchQueue.main.asyncAfter(deadline: .now() + seconds, execute: item)
    }

    private func finishAuth() {
        authTimeout?.cancel()
        authTimeout = nil
        let callbacks = authStatusCallbacks
        authStatusCallbacks.removeAll()
        let status = authorizationStatus
        callbacks.forEach { $0(status) }
    }

    private func authorizationChanged() {
        let state = providerState()
        ObgLogger.i(tag, "authorization changed: status=\(state["status"] ?? "?") enabled=\(state["enabled"] ?? "?")")
        EventBus.shared.emit("providerchange", state)
        if authorizationStatus != 0 { finishAuth() }
        let s = Settings.shared
        if s.enabled && hasAuthorization {
            if s.trackingMode == 1 && !running { begin() }
            GeofenceManager.shared.refresh(force: true)
        }
    }

    @available(iOS 14.0, *)
    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        authorizationChanged()
    }

    func locationManager(_ manager: CLLocationManager, didChangeAuthorization status: CLAuthorizationStatus) {
        if #available(iOS 14.0, *) { return } // the iOS 14 callback above handles it
        authorizationChanged()
    }

    // MARK: public control

    /// Starts continuous tracking. `completion` runs once any permission prompt has been answered.
    func start(completion: (() -> Void)? = nil) {
        let s = Settings.shared
        let was = s.enabled
        s.enabled = true
        s.trackingMode = 1
        s.markBooted()
        ObgLogger.i(tag, "start() requested (wasEnabled=\(was))")
        if !was { EventBus.shared.emit("enabledchange", true) }
        if hasAuthorization {
            begin()
            GeofenceManager.shared.refresh(force: true)
            completion?()
        } else {
            requestPermission { [weak self] _ in
                guard let self = self else { return }
                if self.hasAuthorization && Settings.shared.enabled {
                    self.begin()
                    GeofenceManager.shared.refresh(force: true)
                } else {
                    ObgLogger.w(self.tag, "start(): location permission not granted")
                }
                completion?()
            }
        }
    }

    /// Geofence-only mode: no continuous location updates.
    func startGeofences() {
        let s = Settings.shared
        let was = s.enabled
        s.enabled = true
        s.trackingMode = 0
        s.markBooted()
        ObgLogger.i(tag, "startGeofences() requested (wasEnabled=\(was))")
        end()
        if hasAuthorization {
            GeofenceManager.shared.refresh(force: true)
        } else {
            requestPermission { _ in GeofenceManager.shared.refresh(force: true) }
        }
        if !was { EventBus.shared.emit("enabledchange", true) }
    }

    func stop() {
        let s = Settings.shared
        let was = s.enabled
        s.enabled = false
        ObgLogger.i(tag, "stop() requested (wasEnabled=\(was))")
        stopAllWatchers()
        end()
        GeofenceManager.shared.unregisterAll()
        if was { EventBus.shared.emit("enabledchange", false) }
    }

    func changePace(_ isMoving: Bool) {
        guard running else { return }
        ObgLogger.i(tag, "changePace(isMoving=\(isMoving)) requested by the app")
        if isMoving { enterMoving() } else { enterStationary() }
    }

    /// Resumes tracking after the app is relaunched by the system (location event or region exit).
    func resumeIfEnabled() {
        Scheduler.shared.evaluate()
        let s = Settings.shared
        guard s.enabled else { return }
        ObgLogger.i(tag, "resuming after launch (trackingMode=\(s.trackingMode))")
        if s.trackingMode == 1 {
            if hasAuthorization { begin() }
        } else {
            GeofenceManager.shared.refresh(force: true)
        }
    }

    /// Called when the app is about to be terminated.
    func applicationWillTerminate() {
        let stopOnTerminate = Settings.shared.bool("app", "stopOnTerminate", true)
        ObgLogger.i(tag, "applicationWillTerminate; stopOnTerminate=\(stopOnTerminate)")
        if stopOnTerminate && Settings.shared.enabled { stop() }
    }

    private var hasBackgroundMode: Bool {
        let modes = Bundle.main.object(forInfoDictionaryKey: "UIBackgroundModes") as? [String]
        return modes?.contains("location") ?? false
    }

    private func begin() {
        guard !running else { return }
        running = true
        ObgLogger.purge()
        let device = UIDevice.current
        ObgLogger.i(tag, "begin(): ios=\(device.systemVersion) model=\(device.model) " +
            "authorization=\(authorizationStatus) backgroundMode=\(hasBackgroundMode) " +
            "lowPowerMode=\(ProcessInfo.processInfo.isLowPowerModeEnabled)")
        if !hasBackgroundMode {
            ObgLogger.w(tag, "Info.plist has no UIBackgroundModes 'location'; tracking will stop in the background")
        }
        configureManager()
        startPathMonitor()
        NotificationCenter.default.addObserver(
            self, selector: #selector(powerStateChanged),
            name: .NSProcessInfoPowerStateDidChange, object: nil
        )
        if !Settings.shared.bool("activity", "disableMotionActivityUpdates") {
            motion.start { [weak self] name, confidence in self?.onActivity(name, confidence) }
        }
        enterMoving()
        scheduleHeartbeat()
        let stopAfter = Settings.shared.int("geolocation", "stopAfterElapsedMinutes", 0)
        if stopAfter > 0 {
            let item = DispatchWorkItem { [weak self] in self?.stop() }
            stopAfterItem = item
            DispatchQueue.main.asyncAfter(deadline: .now() + .seconds(stopAfter * 60), execute: item)
        }
    }

    private func end() {
        guard running else { return }
        running = false
        ObgLogger.i(tag, "end(): engine stopped")
        cancelStopTimer()
        heartbeatTimer?.invalidate()
        heartbeatTimer = nil
        stopAfterItem?.cancel()
        stopAfterItem = nil
        manager.stopUpdatingLocation()
        manager.stopMonitoringSignificantLocationChanges()
        removeStationaryRegion()
        motion.stop()
        pathMonitor?.cancel()
        pathMonitor = nil
        lastConnected = nil
        NotificationCenter.default.removeObserver(self, name: .NSProcessInfoPowerStateDidChange, object: nil)
        moving = false
        Settings.shared.isMoving = false
        lastKnown = nil
    }

    // MARK: state transitions

    private func enterMoving() {
        ObgLogger.i(tag, "-> MOVING (activity=\(lastActivity.name)/\(lastActivity.confidence))")
        cancelStopTimer()
        removeStationaryRegion()
        moving = true
        Settings.shared.isMoving = true
        pendingMotionChange = true
        elasticityFactor = 1.0
        applyLocationUpdates()
    }

    private func enterStationary() {
        ObgLogger.i(tag, "-> STATIONARY (activity=\(lastActivity.name)/\(lastActivity.confidence)); GPS updates off")
        cancelStopTimer()
        manager.stopUpdatingLocation()
        moving = false
        Settings.shared.isMoving = false
        pendingMotionChange = false
        guard let loc = lastKnown ?? manager.location else { return }
        lastKnown = loc
        registerStationaryRegion(loc)
        record(loc, event: "motionchange", sample: false)
    }

    private func onStopTimeout() {
        guard running, moving else { return }
        let stopOnStationary = Settings.shared.bool("activity", "stopOnStationary")
        ObgLogger.i(tag, "stopTimeout elapsed with the device still; stopOnStationary=\(stopOnStationary)")
        if stopOnStationary { stop() } else { enterStationary() }
    }

    func onStationaryExit() {
        ObgLogger.i(tag, "left the stationary region (running=\(running) moving=\(moving))")
        if running && !moving { enterMoving() }
    }

    // MARK: location updates

    private func accuracy(for name: String) -> CLLocationAccuracy {
        switch name.lowercased() {
        case "navigation": return kCLLocationAccuracyBestForNavigation
        case "high": return kCLLocationAccuracyBest
        case "medium": return kCLLocationAccuracyNearestTenMeters
        case "low": return kCLLocationAccuracyHundredMeters
        case "verylow": return kCLLocationAccuracyKilometer
        default: return kCLLocationAccuracyThreeKilometers
        }
    }

    private func configureManager() {
        let s = Settings.shared
        manager.desiredAccuracy = accuracy(for: s.string("geolocation", "desiredAccuracy", "high"))
        manager.pausesLocationUpdatesAutomatically = s.bool("geolocation", "pausesLocationUpdatesAutomatically", false)
        if hasBackgroundMode { manager.allowsBackgroundLocationUpdates = true }
        if #available(iOS 11.0, *) {
            manager.showsBackgroundLocationIndicator = s.bool("geolocation", "showsBackgroundLocationIndicator", false)
        }
        switch s.string("geolocation", "activityType", "other").lowercased() {
        case "automotivenavigation": manager.activityType = .automotiveNavigation
        case "fitness": manager.activityType = .fitness
        case "othernavigation": manager.activityType = .otherNavigation
        case "airborne": if #available(iOS 12.2, *) { manager.activityType = .airborne }
        default: manager.activityType = .other
        }
    }

    private func currentDistanceFilter() -> CLLocationDistance {
        let s = Settings.shared
        let base = s.double("geolocation", "distanceFilter", 10)
        let multiplier = s.double("geolocation", "elasticityMultiplier", 1)
        let elastic = !s.bool("geolocation", "disableElasticity") && multiplier > 0
        let distance = elastic ? base * elasticityFactor * multiplier : base
        return distance > 0 ? distance : kCLDistanceFilterNone
    }

    private func applyLocationUpdates() {
        guard hasAuthorization else {
            ObgLogger.w(tag, "location updates skipped: location permission not granted")
            return
        }
        let s = Settings.shared
        manager.stopUpdatingLocation()
        manager.stopMonitoringSignificantLocationChanges()
        if s.bool("geolocation", "useSignificantChangesOnly") {
            ObgLogger.d(tag, "significant-change monitoring only")
            manager.startMonitoringSignificantLocationChanges()
            return
        }
        manager.distanceFilter = currentDistanceFilter()
        ObgLogger.d(tag, "updates: accuracy=\(manager.desiredAccuracy) distanceFilter=\(manager.distanceFilter)m elasticityFactor=\(elasticityFactor)")
        manager.startUpdatingLocation()
        // Lets the system relaunch a terminated app when the device moves far.
        if !s.bool("app", "stopOnTerminate", true) { manager.startMonitoringSignificantLocationChanges() }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        for loc in locations { onLocation(loc) }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        let code = (error as NSError).code
        ObgLogger.e(tag, "location error \(code): \(error.localizedDescription)")
        if code == CLError.denied.rawValue { EventBus.shared.emit("location", ["error": code]) }
    }

    private func onLocation(_ loc: CLLocation) {
        guard running, moving else { return }
        // A negative horizontal accuracy means the fix is invalid.
        guard loc.horizontalAccuracy >= 0 else { return }
        let forced = pendingMotionChange
        let threshold = Settings.shared.nestedDouble("geolocation", "filter", "trackingAccuracyThreshold", 100)
        if !forced && loc.horizontalAccuracy > threshold {
            ObgLogger.v(tag, "fix dropped: accuracy \(loc.horizontalAccuracy)m > threshold \(threshold)m")
            return
        }
        // Core Location can replay a cached fix right after updates start; ignore stale ones.
        if !forced && abs(loc.timestamp.timeIntervalSinceNow) > 60 {
            ObgLogger.v(tag, "fix dropped: older than 60 s")
            return
        }
        if !forced, let prev = lastKnown, !Settings.shared.bool("geolocation", "allowIdenticalLocations"),
           prev.coordinate.latitude == loc.coordinate.latitude, prev.coordinate.longitude == loc.coordinate.longitude {
            ObgLogger.v(tag, "fix dropped: identical to the previous location")
            return
        }
        ObgLogger.v(tag, "fix accepted: acc=\(loc.horizontalAccuracy)m speed=\(loc.speed) forced=\(forced)")
        pendingMotionChange = false
        record(loc, event: forced ? "motionchange" : nil, sample: false)
        adjustElasticity(loc)
    }

    /// Elasticity: widen the distance filter as speed grows.
    private func adjustElasticity(_ loc: CLLocation) {
        if Settings.shared.bool("geolocation", "disableElasticity") { return }
        let kmh = max(loc.speed, 0) * 3.6
        let factor = min(10.0, 1.0 + Double(Int(kmh / 20.0)))
        if factor != elasticityFactor {
            elasticityFactor = factor
            manager.distanceFilter = currentDistanceFilter()
        }
    }

    // MARK: stationary region

    private func registerStationaryRegion(_ loc: CLLocation) {
        guard hasAuthorization, CLLocationManager.isMonitoringAvailable(for: CLCircularRegion.self) else { return }
        let configured = Double(Settings.shared.int("geolocation", "stationaryRadius", 150))
        let radius = min(max(configured, 100), manager.maximumRegionMonitoringDistance)
        let region = CLCircularRegion(center: loc.coordinate, radius: radius, identifier: TrackingEngine.stationaryRegionId)
        region.notifyOnEntry = false
        region.notifyOnExit = true
        manager.startMonitoring(for: region)
    }

    private func removeStationaryRegion() {
        for region in manager.monitoredRegions where region.identifier == TrackingEngine.stationaryRegionId {
            manager.stopMonitoring(for: region)
        }
    }

    func locationManager(_ manager: CLLocationManager, didExitRegion region: CLRegion) {
        if region.identifier == TrackingEngine.stationaryRegionId {
            onStationaryExit()
        } else {
            GeofenceManager.shared.onTransition(id: region.identifier, action: "EXIT")
        }
    }

    func locationManager(_ manager: CLLocationManager, didEnterRegion region: CLRegion) {
        if region.identifier != TrackingEngine.stationaryRegionId {
            GeofenceManager.shared.onTransition(id: region.identifier, action: "ENTER")
        }
    }

    func locationManager(_ manager: CLLocationManager, didDetermineState state: CLRegionState, for region: CLRegion) {
        if region.identifier != TrackingEngine.stationaryRegionId {
            GeofenceManager.shared.didDetermine(state: state, region: region)
        }
    }

    func locationManager(_ manager: CLLocationManager, monitoringDidFailFor region: CLRegion?, withError error: Error) {
        ObgLogger.e(tag, "region monitoring failed for \(region?.identifier ?? "?"): \(error.localizedDescription)")
    }

    // MARK: recording

    private func record(_ loc: CLLocation, event: String?, sample: Bool) {
        let s = Settings.shared
        if let prev = lastKnown, prev !== loc {
            let threshold = s.nestedDouble("geolocation", "filter", "odometerAccuracyThreshold", 100)
            if loc.horizontalAccuracy < 0 || loc.horizontalAccuracy <= threshold {
                s.odometer += prev.distance(from: loc)
            }
        }
        lastKnown = loc
        let json = buildLocation(loc, event: event, isMoving: moving, sample: sample)
        persist(json, loc.timestamp)
        EventBus.shared.emit("location", json)
        if event == "motionchange" { EventBus.shared.emit("motionchange", json) }
        HttpSync.autoSync()
    }

    /// Records and publishes a geofence transition. `fence` is the stored geofence document.
    func recordGeofence(_ loc: CLLocation, id: String, action: String, fence: [String: Any]) {
        let now = isoTimestamp(Date())
        var json = buildLocation(loc, event: "geofence", isMoving: Settings.shared.isMoving, sample: false)
        json["geofence"] = ["identifier": id, "action": action, "timestamp": now]
        if let extras = fence["extras"] { json["extras"] = extras }
        persist(json, loc.timestamp, geofence: true)
        EventBus.shared.emit("geofence", [
            "identifier": id,
            "action": action,
            "location": json,
            "geofence": fence,
            "extras": fence["extras"] ?? NSNull(),
            "timestamp": now,
        ])
        HttpSync.autoSync()
    }

    private func persist(_ json: [String: Any], _ date: Date, geofence: Bool = false) {
        let s = Settings.shared
        let mode = s.string("persistence", "persistMode", "all").lowercased()
        if mode == "none" { return }
        if geofence && mode == "location" { return }
        if !geofence && mode == "geofence" { return }
        guard let uuid = json["uuid"] as? String else { return }
        Db.shared.insert(uuid: uuid, ts: Int64(date.timeIntervalSince1970 * 1000), json: json)
        Db.shared.purge(
            maxDays: s.int("persistence", "maxDaysToPersist", 1),
            maxRecords: s.int("persistence", "maxRecordsToPersist", -1)
        )
    }

    /// Builds the location document; the schema matches the Android side.
    func buildLocation(_ loc: CLLocation, event: String?, isMoving: Bool, sample: Bool = false) -> [String: Any] {
        let s = Settings.shared
        let epoch = s.string("persistence", "timestampFormat", "iso") == "epoch"
        let nowDate = Date()
        let ts: Any
        let recorded: Any
        if epoch {
            ts = Int64(loc.timestamp.timeIntervalSince1970 * 1000)
            recorded = Int64(nowDate.timeIntervalSince1970 * 1000)
        } else {
            ts = isoTimestamp(loc.timestamp)
            recorded = isoTimestamp(nowDate)
        }

        var coords: [String: Any] = [
            "latitude": loc.coordinate.latitude,
            "longitude": loc.coordinate.longitude,
            "accuracy": loc.horizontalAccuracy >= 0 ? loc.horizontalAccuracy : -1.0,
            "speed": loc.speed >= 0 ? loc.speed : -1.0,
            "heading": loc.course >= 0 ? loc.course : -1.0,
            "altitude": loc.altitude,
            "altitude_accuracy": loc.verticalAccuracy,
        ]
        var mock = false
        if #available(iOS 15.0, *) {
            coords["ellipsoidal_altitude"] = loc.ellipsoidalAltitude
            mock = loc.sourceInformation?.isSimulatedBySoftware ?? false
        }

        var json: [String: Any] = [
            "timestamp": ts,
            "uuid": UUID().uuidString,
            "coords": coords,
            "activity": ["type": lastActivity.name, "confidence": lastActivity.confidence] as [String: Any],
            "battery": battery(),
            "odometer": s.odometer,
            "is_moving": isMoving,
            "sample": sample,
            "mock": mock,
            "age": max(0, -loc.timestamp.timeIntervalSinceNow),
            "recorded_at": recorded,
        ]
        if let event = event { json["event"] = event }
        if let extras = s.dict("persistence", "extras") { json["extras"] = extras }
        return json
    }

    private func battery() -> [String: Any] {
        let device = UIDevice.current
        device.isBatteryMonitoringEnabled = true
        let state = device.batteryState
        return [
            "level": Double(device.batteryLevel), // -1 when unknown
            "is_charging": state == .charging || state == .full,
        ]
    }

    // MARK: one-shot and manual

    private func watchAccuracy(_ arg: Any?) -> CLLocationAccuracy {
        if let name = arg as? String { return accuracy(for: name) }
        if let meters = (arg as? NSNumber)?.doubleValue {
            if meters <= 10 { return kCLLocationAccuracyBest }
            if meters <= 100 { return kCLLocationAccuracyHundredMeters }
            return kCLLocationAccuracyKilometer
        }
        return accuracy(for: Settings.shared.string("geolocation", "desiredAccuracy", "high"))
    }

    /// One-shot fix. `completion` receives (location document, error message).
    func currentPosition(_ args: [String: Any], completion: @escaping ([String: Any]?, String?) -> Void) {
        guard hasAuthorization else { return completion(nil, "Location permission not granted") }
        let s = Settings.shared
        let timeout = (args["timeout"] as? NSNumber)?.doubleValue ?? Double(s.int("geolocation", "locationTimeout", 60))
        let maxAgeMs = (args["maximumAge"] as? NSNumber)?.doubleValue ?? 0
        let persistIt = (args["persist"] as? NSNumber)?.boolValue ?? true
        let extras = args["extras"] as? [String: Any]

        let finish: (CLLocation) -> Void = { [weak self] loc in
            guard let self = self else { return }
            var json = self.buildLocation(loc, event: nil, isMoving: self.moving)
            if let e = extras { json["extras"] = e }
            if persistIt { self.persist(json, loc.timestamp) }
            completion(json, nil)
        }

        if maxAgeMs > 0, let known = manager.location, -known.timestamp.timeIntervalSinceNow * 1000 <= maxAgeMs {
            finish(known)
            return
        }
        let locator = OneShotLocator(timeout: timeout, accuracy: kCLLocationAccuracyBest) { [weak self] locator, loc, error in
            self?.oneShots.removeValue(forKey: ObjectIdentifier(locator))
            if let loc = loc { finish(loc) } else { completion(nil, error ?? "Location unavailable") }
        }
        oneShots[ObjectIdentifier(locator)] = locator
    }

    /// Starts an independent position stream; returns its id, or nil without location permission.
    func watchPosition(_ args: [String: Any]) -> Int? {
        guard hasAuthorization else { return nil }
        let id = nextWatchId
        nextWatchId += 1
        let intervalMs = max((args["interval"] as? NSNumber)?.intValue ?? 1000, 1000)
        let persistIt = (args["persist"] as? NSNumber)?.boolValue ?? false
        let extras = args["extras"] as? [String: Any]
        let timeout = (args["timeout"] as? NSNumber)?.intValue ?? 0

        let watcher = Watcher(
            id: id, intervalMs: intervalMs, accuracy: watchAccuracy(args["desiredAccuracy"]), timeoutSeconds: timeout,
            onLocation: { [weak self] id, loc in
                guard let self = self else { return }
                var json = self.buildLocation(loc, event: nil, isMoving: self.moving)
                if let e = extras { json["extras"] = e }
                if persistIt { self.persist(json, loc.timestamp) }
                EventBus.shared.emit("watchposition", ["id": id, "location": json])
            },
            onError: { id, code in
                ObgLogger.w("engine", "watchPosition id=\(id) error \(code)")
                EventBus.shared.emit("watchposition", ["id": id, "error": code])
            }
        )
        watchers[id] = watcher
        ObgLogger.i(tag, "watchPosition started id=\(id) interval=\(intervalMs)ms persist=\(persistIt) timeout=\(timeout)s")
        return id
    }

    func stopWatchPosition(_ id: Int) -> Bool {
        guard let w = watchers.removeValue(forKey: id) else { return false }
        w.stop()
        ObgLogger.i(tag, "watchPosition stopped id=\(id)")
        return true
    }

    func stopAllWatchers() {
        for id in Array(watchers.keys) { _ = stopWatchPosition(id) }
    }

    /// Inserts a caller-supplied record, bypassing persist mode. Returns its uuid.
    func insertLocation(_ params: [String: Any]) -> String {
        var json = params
        let given = json["uuid"] as? String ?? ""
        let uuid = given.isEmpty ? UUID().uuidString : given
        json["uuid"] = uuid
        if json["timestamp"] == nil { json["timestamp"] = isoTimestamp(Date()) }
        Db.shared.insert(uuid: uuid, ts: nowMillis(), json: json)
        return uuid
    }

    // MARK: background tasks

    func startBackgroundTask() -> Int {
        var id = UIBackgroundTaskIdentifier.invalid
        id = UIApplication.shared.beginBackgroundTask(withName: "obg-task") { [weak self] in
            if let self = self, let key = self.backgroundTasks.first(where: { $0.value == id })?.key {
                self.backgroundTasks.removeValue(forKey: key)
            }
            UIApplication.shared.endBackgroundTask(id)
        }
        backgroundTasks[id.rawValue] = id
        return id.rawValue
    }

    func stopBackgroundTask(_ rawId: Int) -> Int {
        if let id = backgroundTasks.removeValue(forKey: rawId) { UIApplication.shared.endBackgroundTask(id) }
        return 0
    }

    // MARK: activity recognition

    private func isTrigger(_ name: String) -> Bool {
        guard let list = Settings.shared.array("activity", "triggerActivities"), !list.isEmpty else { return true }
        return list.contains { ($0 as? String)?.lowercased() == name.lowercased() }
    }

    private func onActivity(_ name: String, _ confidence: Int) {
        guard running else { return }
        let s = Settings.shared
        guard confidence >= s.int("activity", "minimumActivityRecognitionConfidence", 75) else { return }
        let changed = name != lastActivity.name
        lastActivity = (name, confidence)
        if changed {
            ObgLogger.d(tag, "activity changed: \(name) (\(confidence)%)")
            EventBus.shared.emit("activitychange", ["activity": name, "confidence": confidence])
        }
        if s.bool("activity", "disableStopDetection") { return }

        if moving {
            if name == "still" {
                if stopWorkItem == nil {
                    let minutes = s.int("geolocation", "stopTimeout", 5)
                    ObgLogger.i(tag, "device still; stop timer armed for \(minutes) min")
                    let item = DispatchWorkItem { [weak self] in
                        self?.stopWorkItem = nil
                        self?.onStopTimeout()
                    }
                    stopWorkItem = item
                    DispatchQueue.main.asyncAfter(deadline: .now() + .seconds(minutes * 60), execute: item)
                }
            } else if name != "unknown" {
                cancelStopTimer()
            }
        } else if name != "still" && name != "unknown" && isTrigger(name) {
            let delayMs = s.int("activity", "motionTriggerDelay", 0)
            DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(max(delayMs, 0))) { [weak self] in
                guard let self = self, self.running, !self.moving, self.lastActivity.name != "still" else { return }
                self.enterMoving()
            }
        }
    }

    private func cancelStopTimer() {
        stopWorkItem?.cancel()
        stopWorkItem = nil
    }

    // MARK: connectivity, power, heartbeat

    private func startPathMonitor() {
        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { [weak self] path in
            let connected = path.status == .satisfied
            TrackingEngine.isOnExpensiveNetwork = path.isExpensive
            guard let self = self, self.lastConnected != connected else { return }
            self.lastConnected = connected
            ObgLogger.i("engine", connected ? "network available" : "network lost")
            EventBus.shared.emit("connectivitychange", ["connected": connected])
            if connected { HttpSync.autoSync() } // flush records queued while offline
        }
        monitor.start(queue: DispatchQueue(label: "dev.openbg.path"))
        pathMonitor = monitor
    }

    @objc private func powerStateChanged() {
        let on = ProcessInfo.processInfo.isLowPowerModeEnabled
        ObgLogger.i(tag, "low power mode changed: enabled=\(on)")
        EventBus.shared.emit("powersavechange", ["enabled": on])
    }

    private func scheduleHeartbeat() {
        heartbeatTimer?.invalidate()
        heartbeatTimer = nil
        let interval = Settings.shared.int("app", "heartbeatInterval", -1)
        guard interval > 0 else { return }
        heartbeatTimer = Timer.scheduledTimer(withTimeInterval: TimeInterval(interval), repeats: true) { [weak self] _ in
            guard let self = self, self.running else { return }
            ObgLogger.d(self.tag, "heartbeat moving=\(self.moving) lastFix=\(self.lastKnown != nil)")
            if let last = self.lastKnown {
                let json = self.buildLocation(last, event: "heartbeat", isMoving: self.moving)
                EventBus.shared.emit("heartbeat", ["location": json])
            }
            HttpSync.autoSync() // heartbeat retries queued uploads
        }
    }
}
