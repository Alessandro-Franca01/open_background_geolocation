import 'models.dart';

Map<String, dynamic> _m(Object? v) =>
    v is Map ? Map<String, dynamic>.from(v) : <String, dynamic>{};

class GeofenceEvent {
  final String identifier;

  /// ENTER | EXIT | DWELL
  final String action;
  final Location location;
  final Map<String, dynamic>? extras;

  const GeofenceEvent({
    required this.identifier,
    required this.action,
    required this.location,
    this.extras,
  });

  factory GeofenceEvent.fromMap(Object? raw) {
    final m = _m(raw);
    return GeofenceEvent(
      identifier: (m['identifier'] as String?) ?? '',
      action: (m['action'] as String?) ?? '',
      location: Location.fromMap(_m(m['location'])),
      extras: m['extras'] == null ? null : _m(m['extras']),
    );
  }
}

class GeofencesChangeEvent {
  final List<Geofence> on;
  final List<String> off;
  const GeofencesChangeEvent({required this.on, required this.off});

  factory GeofencesChangeEvent.fromMap(Object? raw) {
    final m = _m(raw);
    return GeofencesChangeEvent(
      on: ((m['on'] as List?) ?? const []).map((e) => Geofence.fromMap(_m(e))).toList(),
      off: ((m['off'] as List?) ?? const []).map((e) => e.toString()).toList(),
    );
  }
}

class ActivityChangeEvent {
  final String activity;
  final int confidence;
  const ActivityChangeEvent({required this.activity, required this.confidence});

  factory ActivityChangeEvent.fromMap(Object? raw) {
    final m = _m(raw);
    return ActivityChangeEvent(
      activity: (m['activity'] as String?) ?? 'unknown',
      confidence: (m['confidence'] as num?)?.toInt() ?? 0,
    );
  }
}

class ProviderChangeEvent {
  final bool enabled, gps, network;

  /// Authorization status as reported by the platform.
  final int status;

  /// 0 = reduced, 1 = full (iOS 14+).
  final int accuracyAuthorization;

  const ProviderChangeEvent({
    this.enabled = false,
    this.gps = false,
    this.network = false,
    this.status = 0,
    this.accuracyAuthorization = 0,
  });

  factory ProviderChangeEvent.fromMap(Object? raw) {
    final m = _m(raw);
    return ProviderChangeEvent(
      enabled: m['enabled'] == true,
      gps: m['gps'] == true,
      network: m['network'] == true,
      status: (m['status'] as num?)?.toInt() ?? 0,
      accuracyAuthorization: (m['accuracyAuthorization'] as num?)?.toInt() ?? 0,
    );
  }
}

class ConnectivityChangeEvent {
  final bool connected;
  const ConnectivityChangeEvent({required this.connected});
  factory ConnectivityChangeEvent.fromMap(Object? raw) =>
      ConnectivityChangeEvent(connected: _m(raw)['connected'] == true);
}

class HeartbeatEvent {
  final Location location;
  const HeartbeatEvent({required this.location});
  factory HeartbeatEvent.fromMap(Object? raw) =>
      HeartbeatEvent(location: Location.fromMap(_m(_m(raw)['location'])));
}

class HttpEvent {
  final bool success;
  final int status;
  final String responseText;
  const HttpEvent({required this.success, required this.status, this.responseText = ''});

  factory HttpEvent.fromMap(Object? raw) {
    final m = _m(raw);
    return HttpEvent(
      success: m['success'] == true,
      status: (m['status'] as num?)?.toInt() ?? 0,
      responseText: (m['responseText'] as String?) ?? '',
    );
  }
}

class LocationError {
  final int code;
  const LocationError(this.code);
}

/// Returned by `on*` subscriptions; call [remove] to unsubscribe.
class Subscription {
  final void Function() _cancel;
  const Subscription(this._cancel);
  void remove() => _cancel();
}
