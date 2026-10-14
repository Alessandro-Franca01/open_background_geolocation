import 'config.dart';
import 'enums.dart';

double _d(Object? v) => (v as num?)?.toDouble() ?? 0.0;
Map<String, dynamic> _map(Object? v) =>
    v is Map ? Map<String, dynamic>.from(v) : <String, dynamic>{};

class Coords {
  final double latitude, longitude, accuracy, speed, heading, altitude;
  final double? ellipsoidalAltitude;

  const Coords({
    required this.latitude,
    required this.longitude,
    this.accuracy = -1,
    this.speed = -1,
    this.heading = -1,
    this.altitude = 0,
    this.ellipsoidalAltitude,
  });

  factory Coords.fromMap(Map<String, dynamic> m) => Coords(
        latitude: _d(m['latitude']),
        longitude: _d(m['longitude']),
        accuracy: _d(m['accuracy']),
        speed: _d(m['speed']),
        heading: _d(m['heading']),
        altitude: _d(m['altitude']),
        ellipsoidalAltitude: (m['ellipsoidal_altitude'] as num?)?.toDouble(),
      );

  Map<String, dynamic> toMap() => {
        'latitude': latitude,
        'longitude': longitude,
        'accuracy': accuracy,
        'speed': speed,
        'heading': heading,
        'altitude': altitude,
        if (ellipsoidalAltitude != null) 'ellipsoidal_altitude': ellipsoidalAltitude,
      };
}

class Battery {
  final double level;
  final bool isCharging;
  const Battery({this.level = -1, this.isCharging = false});

  factory Battery.fromMap(Map<String, dynamic> m) => Battery(
        level: _d(m['level']),
        isCharging: (m['is_charging'] ?? m['isCharging']) == true,
      );

  Map<String, dynamic> toMap() => {'level': level, 'is_charging': isCharging};
}

class Activity {
  /// still | on_foot | walking | running | in_vehicle | on_bicycle | unknown
  final String type;
  final int confidence;
  const Activity({this.type = 'unknown', this.confidence = 0});

  factory Activity.fromMap(Map<String, dynamic> m) => Activity(
        type: (m['type'] as String?) ?? 'unknown',
        confidence: (m['confidence'] as num?)?.toInt() ?? 0,
      );

  Map<String, dynamic> toMap() => {'type': type, 'confidence': confidence};
}

class GeofenceTrigger {
  final String identifier, action, timestamp;
  const GeofenceTrigger({required this.identifier, required this.action, this.timestamp = ''});

  factory GeofenceTrigger.fromMap(Map<String, dynamic> m) => GeofenceTrigger(
        identifier: (m['identifier'] as String?) ?? '',
        action: (m['action'] as String?) ?? '',
        timestamp: (m['timestamp'] ?? '').toString(),
      );

  Map<String, dynamic> toMap() =>
      {'identifier': identifier, 'action': action, 'timestamp': timestamp};
}

class Location {
  /// ISO-8601 string or epoch millis, per `PersistenceConfig.timestampFormat`.
  final Object timestamp;
  final String uuid;
  final Coords coords;
  final Activity activity;
  final Battery battery;
  final double odometer;

  /// motionchange | providerchange | geofence | heartbeat | null for normal fixes.
  final String? event;
  final bool isMoving;
  final bool sample;
  final bool mock;
  final double age;
  final Object? recordedAt;
  final Map<String, dynamic>? extras;
  final GeofenceTrigger? geofence;

  const Location({
    required this.timestamp,
    required this.uuid,
    required this.coords,
    this.activity = const Activity(),
    this.battery = const Battery(),
    this.odometer = 0,
    this.event,
    this.isMoving = false,
    this.sample = false,
    this.mock = false,
    this.age = 0,
    this.recordedAt,
    this.extras,
    this.geofence,
  });

  factory Location.fromMap(Map<dynamic, dynamic> raw) {
    final m = Map<String, dynamic>.from(raw);
    return Location(
      timestamp: m['timestamp'] ?? '',
      uuid: (m['uuid'] as String?) ?? '',
      coords: Coords.fromMap(_map(m['coords'])),
      activity: Activity.fromMap(_map(m['activity'])),
      battery: Battery.fromMap(_map(m['battery'])),
      odometer: _d(m['odometer']),
      event: m['event'] as String?,
      isMoving: (m['is_moving'] ?? m['isMoving']) == true,
      sample: m['sample'] == true,
      mock: m['mock'] == true,
      age: _d(m['age']),
      recordedAt: m['recorded_at'],
      extras: m['extras'] == null ? null : _map(m['extras']),
      geofence: m['geofence'] == null ? null : GeofenceTrigger.fromMap(_map(m['geofence'])),
    );
  }

  Map<String, dynamic> toMap() => {
        'timestamp': timestamp,
        'uuid': uuid,
        'coords': coords.toMap(),
        'activity': activity.toMap(),
        'battery': battery.toMap(),
        'odometer': odometer,
        if (event != null) 'event': event,
        'is_moving': isMoving,
        'sample': sample,
        'mock': mock,
        'age': age,
        if (recordedAt != null) 'recorded_at': recordedAt,
        if (extras != null) 'extras': extras,
        if (geofence != null) 'geofence': geofence!.toMap(),
      };
}

class Geofence {
  final String identifier;
  final double? latitude, longitude, radius;
  final bool? notifyOnEntry, notifyOnExit, notifyOnDwell;
  final int? loiteringDelay;
  final Map<String, dynamic>? extras;

  const Geofence({
    required this.identifier,
    this.latitude,
    this.longitude,
    this.radius,
    this.notifyOnEntry,
    this.notifyOnExit,
    this.notifyOnDwell,
    this.loiteringDelay,
    this.extras,
  });

  factory Geofence.fromMap(Map<dynamic, dynamic> raw) {
    final m = Map<String, dynamic>.from(raw);
    return Geofence(
      identifier: m['identifier'] as String,
      latitude: (m['latitude'] as num?)?.toDouble(),
      longitude: (m['longitude'] as num?)?.toDouble(),
      radius: (m['radius'] as num?)?.toDouble(),
      notifyOnEntry: m['notifyOnEntry'] as bool?,
      notifyOnExit: m['notifyOnExit'] as bool?,
      notifyOnDwell: m['notifyOnDwell'] as bool?,
      loiteringDelay: (m['loiteringDelay'] as num?)?.toInt(),
      extras: m['extras'] == null ? null : _map(m['extras']),
    );
  }

  Map<String, dynamic> toMap() {
    final m = <String, dynamic>{
      'identifier': identifier,
      'latitude': latitude,
      'longitude': longitude,
      'radius': radius,
      'notifyOnEntry': notifyOnEntry,
      'notifyOnExit': notifyOnExit,
      'notifyOnDwell': notifyOnDwell,
      'loiteringDelay': loiteringDelay,
      'extras': extras,
    };
    m.removeWhere((_, v) => v == null);
    return m;
  }
}

/// Snapshot of SDK state and effective configuration.
class State {
  final bool enabled;
  final bool? isMoving;
  final TrackingMode trackingMode;
  final double odometer;
  final bool schedulerEnabled;
  final bool didDeviceReboot;
  final bool didLaunchInBackground;
  final bool isFirstBoot;

  /// Raw state map, including the effective config groups.
  final Map<String, dynamic> raw;

  const State({
    required this.enabled,
    this.isMoving,
    this.trackingMode = TrackingMode.location,
    this.odometer = 0,
    this.schedulerEnabled = false,
    this.didDeviceReboot = false,
    this.didLaunchInBackground = false,
    this.isFirstBoot = false,
    this.raw = const {},
  });

  GeoConfig get geolocation => GeoConfig.fromMap(_map(raw['geolocation']));

  factory State.fromMap(Map<dynamic, dynamic> r) {
    final m = Map<String, dynamic>.from(r);
    return State(
      enabled: m['enabled'] == true,
      isMoving: m['isMoving'] as bool?,
      trackingMode: (m['trackingMode'] as num?)?.toInt() == 0
          ? TrackingMode.geofencesOnly
          : TrackingMode.location,
      odometer: _d(m['odometer']),
      schedulerEnabled: m['schedulerEnabled'] == true,
      didDeviceReboot: m['didDeviceReboot'] == true,
      didLaunchInBackground: m['didLaunchInBackground'] == true,
      isFirstBoot: m['isFirstBoot'] == true,
      raw: m,
    );
  }
}
