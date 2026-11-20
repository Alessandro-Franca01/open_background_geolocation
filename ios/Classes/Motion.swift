import CoreMotion
import Foundation

/// Activity recognition through Core Motion, reporting the same names as the Android side.
final class Motion {
    private let manager = CMMotionActivityManager()
    private var active = false

    func start(_ handler: @escaping (String, Int) -> Void) {
        guard CMMotionActivityManager.isActivityAvailable(), !active else { return }
        active = true
        manager.startActivityUpdates(to: OperationQueue.main) { activity in
            guard let a = activity else { return }
            handler(Motion.name(a), Motion.confidence(a))
        }
    }

    func stop() {
        guard active else { return }
        manager.stopActivityUpdates()
        active = false
    }

    static func name(_ a: CMMotionActivity) -> String {
        if a.automotive { return "in_vehicle" }
        if a.cycling { return "on_bicycle" }
        if a.running { return "running" }
        if a.walking { return "walking" }
        if a.stationary { return "still" }
        return "unknown"
    }

    /// Core Motion reports low / medium / high; mapped so the default threshold of 75 accepts
    /// medium and high.
    static func confidence(_ a: CMMotionActivity) -> Int {
        switch a.confidence {
        case .low: return 25
        case .medium: return 75
        case .high: return 100
        @unknown default: return 0
        }
    }
}
