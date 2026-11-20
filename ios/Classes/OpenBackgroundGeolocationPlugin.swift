import Flutter
import UIKit

/// Registers the method and event channels shared with the Dart side and routes every call to the
/// native components. Method and event names match the Android implementation.
public class OpenBackgroundGeolocationPlugin: NSObject, FlutterPlugin, FlutterStreamHandler {
    public static func register(with registrar: FlutterPluginRegistrar) {
        let instance = OpenBackgroundGeolocationPlugin()
        let methods = FlutterMethodChannel(name: "dev.openbg/methods", binaryMessenger: registrar.messenger())
        registrar.addMethodCallDelegate(instance, channel: methods)
        let events = FlutterEventChannel(name: "dev.openbg/events", binaryMessenger: registrar.messenger())
        events.setStreamHandler(instance)
        registrar.addApplicationDelegate(instance)
        // The system relaunches a terminated app on location events; pick tracking back up.
        TrackingEngine.shared.resumeIfEnabled()
    }

    // MARK: events

    public func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
        EventBus.shared.sink = events
        return nil
    }

    public func onCancel(withArguments arguments: Any?) -> FlutterError? {
        EventBus.shared.sink = nil
        return nil
    }

    // MARK: application lifecycle

    public func applicationWillTerminate(_ application: UIApplication) {
        TrackingEngine.shared.applicationWillTerminate()
    }

    public func applicationDidBecomeActive(_ application: UIApplication) {
        Scheduler.shared.evaluate()
    }

    // MARK: methods

    private func failure(_ code: String, _ message: String?) -> FlutterError {
        return FlutterError(code: code, message: message, details: nil)
    }

    private func respond(_ result: FlutterResult, ok: Bool, code: String, error: String?) {
        if ok { result(true) } else { result(failure(code, error)) }
    }

    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        let args = call.arguments
        let map = (args as? [String: Any]) ?? [:]
        let engine = TrackingEngine.shared
        let settings = Settings.shared

        switch call.method {
        case "ready":
            let reset = (map["reset"] as? NSNumber)?.boolValue ?? true
            settings.ready(map, reset: reset)
            result(settings.stateMap())
        case "getState":
            result(settings.stateMap())
        case "setConfig":
            settings.update(map)
            // A changed schedule takes effect immediately while the scheduler is running.
            if settings.schedulerEnabled, let app = map["app"] as? [String: Any], app["schedule"] != nil {
                Scheduler.shared.evaluate()
            }
            result(settings.stateMap())
        case "reset":
            settings.reset(args as? [String: Any])
            result(settings.stateMap())
        case "start":
            engine.start { result(settings.stateMap()) }
        case "startGeofences":
            engine.startGeofences()
            result(settings.stateMap())
        case "stop":
            engine.stop()
            result(settings.stateMap())
        case "changePace":
            engine.changePace((args as? NSNumber)?.boolValue ?? false)
            result(settings.stateMap())
        case "startSchedule":
            Scheduler.shared.start()
            result(settings.stateMap())
        case "stopSchedule":
            Scheduler.shared.stop()
            result(settings.stateMap())

        case "getCurrentPosition":
            engine.currentPosition(map) { loc, error in
                if let loc = loc { result(loc) } else { result(self.failure("LOCATION_ERROR", error)) }
            }
        case "watchPosition":
            if let id = engine.watchPosition(map) {
                result(id)
            } else {
                result(failure("OBG_ERROR", "Location permission not granted"))
            }
        case "stopWatchPosition":
            result(engine.stopWatchPosition((args as? NSNumber)?.intValue ?? -1))
        case "getOdometer":
            result(settings.odometer)
        case "setOdometer":
            settings.odometer = (args as? NSNumber)?.doubleValue ?? 0
            engine.currentPosition(["persist": false]) { loc, error in
                if let loc = loc { result(loc) } else { result(self.failure("LOCATION_ERROR", error)) }
            }

        case "getLocations":
            let order = (map["order"] as? String) ?? settings.string("persistence", "locationsOrderDirection", "ASC")
            let limit = (map["limit"] as? NSNumber)?.intValue
            let start = (map["start"] as? NSNumber)?.intValue
            result(Db.shared.query(order: order, limit: limit, offset: start))
        case "getCount":
            result(Db.shared.count())
        case "insertLocation":
            result(engine.insertLocation(map))
        case "destroyLocation":
            result(Db.shared.delete(uuid: (args as? String) ?? ""))
        case "destroyLocations":
            Db.shared.deleteAll()
            result(true)
        case "sync":
            HttpSync.sync { records, error in
                if error == nil { result(records) } else { result(self.failure("HTTP_ERROR", error)) }
            }

        case "addGeofence":
            GeofenceManager.shared.add([map]) { ok, error in
                self.respond(result, ok: ok, code: "GEOFENCE_ERROR", error: error)
            }
        case "addGeofences":
            let list = (args as? [Any])?.compactMap { $0 as? [String: Any] } ?? []
            GeofenceManager.shared.add(list) { ok, error in
                self.respond(result, ok: ok, code: "GEOFENCE_ERROR", error: error)
            }
        case "geofenceExists":
            result(GeofenceManager.shared.get((args as? String) ?? "") != nil)
        case "getGeofence":
            result(GeofenceManager.shared.get((args as? String) ?? ""))
        case "getGeofences":
            result(GeofenceManager.shared.all())
        case "removeGeofence":
            GeofenceManager.shared.remove([(args as? String) ?? ""]) { ok, error in
                self.respond(result, ok: ok, code: "GEOFENCE_ERROR", error: error)
            }
        case "removeGeofences":
            GeofenceManager.shared.remove(args as? [String]) { ok, error in
                self.respond(result, ok: ok, code: "GEOFENCE_ERROR", error: error)
            }

        case "getProviderState":
            result(engine.providerState())
        case "requestPermission":
            engine.requestPermission { status in result(status) }
        case "requestTemporaryFullAccuracy":
            if #available(iOS 14.0, *) {
                let purpose = (args as? String) ?? ""
                engine.manager.requestTemporaryFullAccuracyAuthorization(withPurposeKey: purpose) { _ in
                    result(engine.providerState()["accuracyAuthorization"] ?? 1)
                }
            } else {
                result(1)
            }
        case "startBackgroundTask":
            result(engine.startBackgroundTask())
        case "stopBackgroundTask":
            result(engine.stopBackgroundTask((args as? NSNumber)?.intValue ?? 0))

        case "getLog":
            let order = (map["order"] as? String) ?? "ASC"
            let limit = (map["limit"] as? NSNumber)?.intValue
            let start = (map["start"] as? NSNumber)?.int64Value
            let end = (map["end"] as? NSNumber)?.int64Value
            result(ObgLogger.get(order: order, limit: limit, start: start, end: end))
        case "destroyLog":
            ObgLogger.clear()
            result(true)
        case "log":
            ObgLogger.log((map["level"] as? String) ?? "info", "app", (map["message"] as? String) ?? "")
            result(true)

        // Android only: iOS relaunches the app with its Dart isolate running, so there is no
        // separate headless engine. Accepted so shared Dart code does not fail.
        case "registerHeadlessTask":
            result(true)

        default:
            result(FlutterMethodNotImplemented)
        }
    }
}
