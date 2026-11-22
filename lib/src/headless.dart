import 'dart:ui';

import 'package:flutter/foundation.dart' show debugPrint;
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart' show WidgetsFlutterBinding;

import 'events.dart';
import 'models.dart';

/// An event delivered to the headless task while the app has no UI.
///
/// [name] is the event name (`location`, `motionchange`, `geofence`, `http`, ...) and [event] is
/// the already parsed payload: a [Location] for `location` / `motionchange` / `heartbeat`-less
/// location events, [GeofenceEvent] for `geofence`, [HttpEvent] for `http`, a `bool` for
/// `enabledchange` / `powersavechange`, and so on. Unknown names carry the raw payload.
class HeadlessEvent {
  final String name;
  final Object? event;
  const HeadlessEvent(this.name, this.event);

  @override
  String toString() => 'HeadlessEvent($name, $event)';
}

/// Signature of the function registered with `BackgroundGeolocation.registerHeadlessTask`.
/// It must be a top-level or static function annotated with `@pragma('vm:entry-point')`.
typedef HeadlessTask = Future<void> Function(HeadlessEvent event);

bool _flag(Object? raw) => raw == true || (raw is Map && raw['enabled'] == true);

/// Converts a raw native payload into the same type the matching `on*` listener receives.
HeadlessEvent parseHeadlessEvent(String name, Object? data) {
  final Object? parsed;
  switch (name) {
    case 'location':
    case 'motionchange':
      parsed = Location.fromMap(data as Map);
    case 'activitychange':
      parsed = ActivityChangeEvent.fromMap(data);
    case 'providerchange':
      parsed = ProviderChangeEvent.fromMap(data);
    case 'connectivitychange':
      parsed = ConnectivityChangeEvent.fromMap(data);
    case 'geofence':
      parsed = GeofenceEvent.fromMap(data);
    case 'geofenceschange':
      parsed = GeofencesChangeEvent.fromMap(data);
    case 'heartbeat':
      parsed = HeartbeatEvent.fromMap(data);
    case 'http':
      parsed = HttpEvent.fromMap(data);
    case 'authorization':
      parsed = AuthorizationEvent.fromMap(data);
    case 'enabledchange':
    case 'powersavechange':
      parsed = _flag(data);
    case 'schedule':
      parsed = State.fromMap(data as Map);
    case 'trackingerror':
      parsed = TrackingErrorEvent.fromMap(data);
    case 'locationsilence':
      parsed = LocationSilenceEvent.fromMap(data);
    default:
      parsed = data;
  }
  return HeadlessEvent(name, parsed);
}

/// Entry point the native side runs in a background Flutter engine. It waits for `event` calls and
/// forwards each one to the user's registered task.
@pragma('vm:entry-point')
void openBgHeadlessDispatcher() {
  WidgetsFlutterBinding.ensureInitialized();
  DartPluginRegistrant.ensureInitialized();

  const channel = MethodChannel('dev.openbg/headless');
  channel.setMethodCallHandler((call) async {
    if (call.method != 'event') return;
    final args = call.arguments as Map;
    final handle = (args['handle'] as num).toInt();
    final callback = PluginUtilities.getCallbackFromHandle(CallbackHandle.fromRawHandle(handle));
    if (callback == null) return;
    final name = args['name'] as String;
    try {
      await (callback as dynamic)(parseHeadlessEvent(name, args['data']));
    } catch (e, st) {
      debugPrint('open_background_geolocation: headless task failed for "$name": $e\n$st');
    }
  });
  channel.invokeMethod<void>('initialized');
}
