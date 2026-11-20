import CoreLocation
import Foundation

/// Stores user geofences and keeps the OS region monitor in sync with them.
///
/// iOS allows at most 20 monitored regions per app and the tracking engine reserves one for
/// stationary detection, so at most `maxActive` geofences are monitored; when more are stored only
/// the nearest to the last known position are. Region monitoring reports enter and exit; dwell is
/// emulated with a timer and therefore only fires while the app is running.
final class GeofenceManager {
    static let shared = GeofenceManager()

    private let tag = "geofence"
    private let maxActive = 19
    private var active = Set<String>()
    private var pendingInitial = Set<String>()
    private var dwellTimers: [String: DispatchWorkItem] = [:]

    private init() {}

    private var manager: CLLocationManager { return TrackingEngine.shared.manager }

    // MARK: CRUD

    /// Validates and stores geofences, replacing any with the same identifier.
    func add(_ list: [[String: Any]], completion: (Bool, String?) -> Void) {
        for g in list {
            guard let id = g["identifier"] as? String, !id.isEmpty else {
                return completion(false, "Geofence identifier is required")
            }
            guard g["latitude"] is NSNumber, g["longitude"] is NSNumber, g["radius"] is NSNumber else {
                return completion(false, "Geofence '\(id)' requires latitude, longitude and radius")
            }
        }
        for var g in list {
            let id = g["identifier"] as! String
            // Replacing a geofence resets its runtime state.
            if Db.shared.geofenceGet(id) != nil { g["hits"] = 0 }
            Db.shared.geofencePut(id, g)
        }
        refresh(force: true)
        completion(true, nil)
    }

    func remove(_ ids: [String]?, completion: (Bool, String?) -> Void) {
        if let ids = ids { ids.forEach { Db.shared.geofenceDelete($0) } } else { Db.shared.geofenceDeleteAll() }
        refresh(force: true)
        completion(true, nil)
    }

    func get(_ id: String) -> [String: Any]? { return Db.shared.geofenceGet(id) }
    func all() -> [[String: Any]] { return Db.shared.geofenceAll() }

    // MARK: OS registration

    /// Registers stored geofences with the OS when tracking is enabled; no-op otherwise.
    func refresh(force: Bool = false) {
        guard Settings.shared.enabled, TrackingEngine.shared.hasAuthorization,
              CLLocationManager.isMonitoringAvailable(for: CLCircularRegion.self) else { return }

        let chosen = choose(Db.shared.geofenceAll())
        let chosenIds = Set(chosen.compactMap { $0["identifier"] as? String })
        if !force && chosenIds == active { return }

        let off = active.subtracting(chosenIds)
        let on = chosen.filter { !active.contains(($0["identifier"] as? String) ?? "") }

        // Stop every user region the OS still monitors (also those left from a previous launch).
        for region in manager.monitoredRegions where region.identifier != TrackingEngine.stationaryRegionId {
            manager.stopMonitoring(for: region)
        }
        active.removeAll()
        pendingInitial.removeAll()

        let wantsInitial = Settings.shared.bool("geolocation", "geofenceInitialTriggerEntry", true)
        for g in chosen {
            guard let region = makeRegion(g) else { continue }
            manager.startMonitoring(for: region)
            active.insert(region.identifier)
            if wantsInitial {
                pendingInitial.insert(region.identifier)
                manager.requestState(for: region)
            }
        }
        ObgLogger.i(tag, "monitoring \(active.count) of \(chosen.count) geofence(s)")
        emitChange(on: on, off: Array(off))
    }

    func unregisterAll() {
        for region in manager.monitoredRegions where region.identifier != TrackingEngine.stationaryRegionId {
            manager.stopMonitoring(for: region)
        }
        dwellTimers.values.forEach { $0.cancel() }
        dwellTimers.removeAll()
        let off = Array(active)
        active.removeAll()
        pendingInitial.removeAll()
        if !off.isEmpty { emitChange(on: [], off: off) }
    }

    private func choose(_ all: [[String: Any]]) -> [[String: Any]] {
        if all.count <= maxActive { return all }
        guard let ref = TrackingEngine.shared.lastKnown else { return Array(all.prefix(maxActive)) }
        func distance(_ g: [String: Any]) -> CLLocationDistance {
            let lat = (g["latitude"] as? NSNumber)?.doubleValue ?? 0
            let lon = (g["longitude"] as? NSNumber)?.doubleValue ?? 0
            return ref.distance(from: CLLocation(latitude: lat, longitude: lon))
        }
        return Array(all.sorted { distance($0) < distance($1) }.prefix(maxActive))
    }

    private func makeRegion(_ g: [String: Any]) -> CLCircularRegion? {
        guard let id = g["identifier"] as? String,
              let lat = (g["latitude"] as? NSNumber)?.doubleValue,
              let lon = (g["longitude"] as? NSNumber)?.doubleValue,
              let radius = (g["radius"] as? NSNumber)?.doubleValue else { return nil }
        let region = CLCircularRegion(
            center: CLLocationCoordinate2D(latitude: lat, longitude: lon),
            radius: min(radius, manager.maximumRegionMonitoringDistance),
            identifier: id
        )
        // Entry and exit notifications are on unless explicitly disabled; dwell is opt-in.
        let dwell = (g["notifyOnDwell"] as? NSNumber)?.boolValue ?? false
        region.notifyOnEntry = ((g["notifyOnEntry"] as? NSNumber)?.boolValue ?? true) || dwell
        region.notifyOnExit = (g["notifyOnExit"] as? NSNumber)?.boolValue ?? true
        return region
    }

    private func emitChange(on: [[String: Any]], off: [String]) {
        EventBus.shared.emit("geofenceschange", ["on": on, "off": off])
    }

    // MARK: transitions

    /// Called for the initial state request issued when a region starts being monitored.
    func didDetermine(state: CLRegionState, region: CLRegion) {
        guard pendingInitial.remove(region.identifier) != nil, state == .inside,
              let stored = Db.shared.geofenceGet(region.identifier),
              (stored["notifyOnEntry"] as? NSNumber)?.boolValue ?? true else { return }
        onTransition(id: region.identifier, action: "ENTER")
    }

    func onTransition(id: String, action: String) {
        guard let stored = Db.shared.geofenceGet(id) else { return }
        let notifyEntry = (stored["notifyOnEntry"] as? NSNumber)?.boolValue ?? true
        let notifyExit = (stored["notifyOnExit"] as? NSNumber)?.boolValue ?? true
        let dwell = (stored["notifyOnDwell"] as? NSNumber)?.boolValue ?? false

        dwellTimers[id]?.cancel()
        dwellTimers[id] = nil
        if action == "ENTER" && dwell {
            let delayMs = (stored["loiteringDelay"] as? NSNumber)?.intValue ?? 0
            let item = DispatchWorkItem { [weak self] in
                self?.dwellTimers[id] = nil
                self?.fire(id: id, action: "DWELL")
            }
            dwellTimers[id] = item
            DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(max(delayMs, 0)), execute: item)
        }
        if action == "ENTER" && !notifyEntry { return }
        if action == "EXIT" && !notifyExit { return }
        fire(id: id, action: action)
    }

    private func fire(id: String, action: String) {
        ObgLogger.i(tag, "transition \(action) on '\(id)'")
        guard var stored = Db.shared.geofenceGet(id) else { return }
        // The OS may omit the triggering fix; fall back to the last known one, then the center.
        let loc = TrackingEngine.shared.manager.location ?? TrackingEngine.shared.lastKnown
            ?? CLLocation(
                latitude: (stored["latitude"] as? NSNumber)?.doubleValue ?? 0,
                longitude: (stored["longitude"] as? NSNumber)?.doubleValue ?? 0
            )
        stored["hits"] = ((stored["hits"] as? NSNumber)?.intValue ?? 0) + 1
        stored["entryState"] = action == "EXIT" ? 0 : 1
        stored["stateUpdatedAt"] = Date().timeIntervalSince1970
        Db.shared.geofencePut(id, stored)
        TrackingEngine.shared.recordGeofence(loc, id: id, action: action, fence: stored)
    }
}
