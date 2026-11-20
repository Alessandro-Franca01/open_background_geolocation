import Flutter
import Foundation

/// Bridge from native components to Dart. Events emitted while no Dart listener is attached are
/// dropped: on iOS the app is relaunched in the background with its Dart isolate running, so there
/// is no separate headless engine.
final class EventBus {
    static let shared = EventBus()
    var sink: FlutterEventSink?

    private init() {}

    func emit(_ name: String, _ data: Any?) {
        DispatchQueue.main.async {
            self.sink?(["event": name, "data": data ?? NSNull()])
        }
    }
}
