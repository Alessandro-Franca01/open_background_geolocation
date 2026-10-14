import 'dart:async';

import 'package:flutter/services.dart';

import 'config.dart';
import 'events.dart';
import 'models.dart';

const MethodChannel _methods = MethodChannel('dev.openbg/methods');
const EventChannel _events = EventChannel('dev.openbg/events');

/// Entry point of the plugin. All members are static.
///
/// Events arrive from the native side as `{event: <name>, data: <payload>}` envelopes on a single
/// [EventChannel] and are fanned out to per-event broadcast streams.
class BackgroundGeolocation {
  BackgroundGeolocation._();

  static StreamSubscription<dynamic>? _nativeSub;
  static final Map<String, StreamController<dynamic>> _controllers = {};

  static Location _loc(Object? raw) => Location.fromMap(raw as Map);

  static void _ensureListening() {
    _nativeSub ??= _events.receiveBroadcastStream().listen((dynamic envelope) {
      if (envelope is! Map) return;
      final name = envelope['event'] as String?;
      if (name == null) return;
      _controllers[name]?.add(envelope['data']);
    });
  }

  static Subscription _subscribe<T>(
    String name,
    T Function(Object? raw) parse,
    void Function(T) onData, [
    void Function(LocationError)? onError,
  ]) {
    _ensureListening();
    final controller = _controllers.putIfAbsent(name, () => StreamController<dynamic>.broadcast());
    final sub = controller.stream.listen((raw) {
      if (name == 'location' && raw is Map && raw['error'] != null) {
        onError?.call(LocationError((raw['error'] as num).toInt()));
        return;
      }
      onData(parse(raw));
    });
    return Subscription(sub.cancel);
  }

  static Future<T> _invoke<T>(String method, [Object? args]) async {
    final result = await _methods.invokeMethod<T>(method, args);
    return result as T;
  }

  // ---------------------------------------------------------------- lifecycle

  static Future<State> ready(Config config) async =>
      State.fromMap(await _invoke<Map>('ready', config.toMap()));

  static Future<State> start() async => State.fromMap(await _invoke<Map>('start'));
  static Future<State> stop() async => State.fromMap(await _invoke<Map>('stop'));
  static Future<State> startGeofences() async =>
      State.fromMap(await _invoke<Map>('startGeofences'));
  static Future<State> changePace(bool isMoving) async =>
      State.fromMap(await _invoke<Map>('changePace', isMoving));
  static Future<State> setConfig(Config config) async =>
      State.fromMap(await _invoke<Map>('setConfig', config.toMap()));
  static Future<State> reset([Config? config]) async =>
      State.fromMap(await _invoke<Map>('reset', config?.toMap()));
  static Future<State> get state async => State.fromMap(await _invoke<Map>('getState'));
  static Future<State> startSchedule() async =>
      State.fromMap(await _invoke<Map>('startSchedule'));
  static Future<State> stopSchedule() async =>
      State.fromMap(await _invoke<Map>('stopSchedule'));

  // ----------------------------------------------------------------- location

  static Future<Location> getCurrentPosition({
    int? timeout,
    int? maximumAge,
    bool? persist,
    int? samples,
    double? desiredAccuracy,
    Map<String, dynamic>? extras,
  }) async {
    final args = <String, dynamic>{
      'timeout': timeout,
      'maximumAge': maximumAge,
      'persist': persist,
      'samples': samples,
      'desiredAccuracy': desiredAccuracy,
      'extras': extras,
    }..removeWhere((_, v) => v == null);
    return Location.fromMap(await _invoke<Map>('getCurrentPosition', args));
  }

  /// Starts a foreground-oriented position stream. Returns a watch id for [stopWatchPosition].
  static Future<int> watchPosition(
    void Function(Location) success, {
    void Function(LocationError)? failure,
    int? interval,
    double? desiredAccuracy,
    bool? persist,
    Map<String, dynamic>? extras,
    int? timeout,
  }) async {
    final args = <String, dynamic>{
      'interval': interval,
      'desiredAccuracy': desiredAccuracy,
      'persist': persist,
      'extras': extras,
      'timeout': timeout,
    }..removeWhere((_, v) => v == null);
    final id = await _invoke<int>('watchPosition', args);
    _subscribe<Location>('watchposition', _loc, success, failure);
    return id;
  }

  static Future<bool> stopWatchPosition(int watchId) =>
      _invoke<bool>('stopWatchPosition', watchId);

  static Future<double> get odometer async => (await _invoke<num>('getOdometer')).toDouble();
  static Future<Location> setOdometer(double value) async =>
      Location.fromMap(await _invoke<Map>('setOdometer', value));

  // ---------------------------------------------------------------- geofences

  static Future<bool> addGeofence(Geofence geofence) =>
      _invoke<bool>('addGeofence', geofence.toMap());
  static Future<bool> addGeofences(List<Geofence> geofences) =>
      _invoke<bool>('addGeofences', geofences.map((g) => g.toMap()).toList());
  static Future<bool> geofenceExists(String identifier) =>
      _invoke<bool>('geofenceExists', identifier);
  static Future<Geofence?> getGeofence(String identifier) async {
    final m = await _invoke<Map?>('getGeofence', identifier);
    return m == null ? null : Geofence.fromMap(m);
  }

  static Future<List<Geofence>> get geofences async {
    final list = await _invoke<List>('getGeofences');
    return list.map((e) => Geofence.fromMap(e as Map)).toList();
  }

  static Future<bool> removeGeofence(String identifier) =>
      _invoke<bool>('removeGeofence', identifier);
  static Future<bool> removeGeofences([List<String>? identifiers]) =>
      _invoke<bool>('removeGeofences', identifiers);

  // -------------------------------------------------------------- persistence

  static Future<List<Location>> getLocations({int? limit, int? start, String? order}) async {
    final args = <String, dynamic>{'limit': limit, 'start': start, 'order': order}
      ..removeWhere((_, v) => v == null);
    final list = await _invoke<List>('getLocations', args);
    return list.map((e) => Location.fromMap(e as Map)).toList();
  }

  static Future<int> get count async => _invoke<int>('getCount');
  static Future<String> insertLocation(Map<String, dynamic> params) =>
      _invoke<String>('insertLocation', params);
  static Future<bool> destroyLocation(String uuid) => _invoke<bool>('destroyLocation', uuid);
  static Future<bool> destroyLocations() => _invoke<bool>('destroyLocations');

  /// Uploads queued locations to `http.url`. Returns the uploaded records.
  static Future<List<Location>> sync() async {
    final list = await _invoke<List>('sync');
    return list.map((e) => Location.fromMap(e as Map)).toList();
  }

  // ----------------------------------------------------------------- platform

  static Future<ProviderChangeEvent> get providerState async =>
      ProviderChangeEvent.fromMap(await _invoke<Map>('getProviderState'));

  /// Requests location authorization. Returns the resulting status code.
  static Future<int> requestPermission() => _invoke<int>('requestPermission');

  /// iOS 14+. No-op on Android.
  static Future<int> requestTemporaryFullAccuracy(String purpose) =>
      _invoke<int>('requestTemporaryFullAccuracy', purpose);

  static Future<int> startBackgroundTask() => _invoke<int>('startBackgroundTask');
  static Future<int> stopBackgroundTask(int taskId) =>
      _invoke<int>('stopBackgroundTask', taskId);

  static Future<void> removeListeners() async {
    for (final c in _controllers.values) {
      await c.close();
    }
    _controllers.clear();
    await _nativeSub?.cancel();
    _nativeSub = null;
  }

  // ------------------------------------------------------------------- events

  static Subscription onLocation(void Function(Location) success,
          [void Function(LocationError)? failure]) =>
      _subscribe<Location>('location', _loc, success, failure);

  static Subscription onMotionChange(void Function(Location) cb) =>
      _subscribe<Location>('motionchange', _loc, cb);

  static Subscription onActivityChange(void Function(ActivityChangeEvent) cb) =>
      _subscribe<ActivityChangeEvent>('activitychange', ActivityChangeEvent.fromMap, cb);

  static Subscription onProviderChange(void Function(ProviderChangeEvent) cb) =>
      _subscribe<ProviderChangeEvent>('providerchange', ProviderChangeEvent.fromMap, cb);

  static Subscription onConnectivityChange(void Function(ConnectivityChangeEvent) cb) =>
      _subscribe<ConnectivityChangeEvent>(
          'connectivitychange', ConnectivityChangeEvent.fromMap, cb);

  static Subscription onEnabledChange(void Function(bool) cb) => _subscribe<bool>(
      'enabledchange', (raw) => raw == true || (raw is Map && raw['enabled'] == true), cb);

  static Subscription onGeofence(void Function(GeofenceEvent) cb) =>
      _subscribe<GeofenceEvent>('geofence', GeofenceEvent.fromMap, cb);

  static Subscription onGeofencesChange(void Function(GeofencesChangeEvent) cb) =>
      _subscribe<GeofencesChangeEvent>('geofenceschange', GeofencesChangeEvent.fromMap, cb);

  static Subscription onHeartbeat(void Function(HeartbeatEvent) cb) =>
      _subscribe<HeartbeatEvent>('heartbeat', HeartbeatEvent.fromMap, cb);

  static Subscription onHttp(void Function(HttpEvent) cb) =>
      _subscribe<HttpEvent>('http', HttpEvent.fromMap, cb);

  static Subscription onPowerSaveChange(void Function(bool) cb) => _subscribe<bool>(
      'powersavechange', (raw) => raw == true || (raw is Map && raw['enabled'] == true), cb);

  static Subscription onSchedule(void Function(State) cb) =>
      _subscribe<State>('schedule', (raw) => State.fromMap(raw as Map), cb);

  /// Android only: button id of a tapped foreground-notification action.
  static Subscription onNotificationAction(void Function(String) cb) =>
      _subscribe<String>('notificationaction', (raw) => raw.toString(), cb);
}
