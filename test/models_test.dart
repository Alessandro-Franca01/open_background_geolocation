import 'package:flutter_test/flutter_test.dart';
import 'package:open_background_geolocation/open_background_geolocation.dart';

void main() {
  test('Config.toMap omits null values and nests groups', () {
    final map = const Config(
      geolocation: GeoConfig(desiredAccuracy: DesiredAccuracy.high, distanceFilter: 10),
      http: HttpConfig(url: 'https://example.com', autoSync: true),
      persistence: PersistenceConfig(persistMode: PersistMode.all),
      reset: true,
    ).toMap();

    expect(map['geolocation'], {'desiredAccuracy': 'high', 'distanceFilter': 10.0});
    expect(map['http'], {'autoSync': true, 'url': 'https://example.com'});
    expect(map['persistence'], {'persistMode': 'all'});
    expect(map.containsKey('app'), isFalse);
    expect(map['reset'], isTrue);
  });

  test('Location.fromMap parses the HTTP-style snake_case schema', () {
    final loc = Location.fromMap({
      'timestamp': '2025-04-10T12:00:01.123Z',
      'uuid': 'abc',
      'coords': {'latitude': -23.5, 'longitude': -46.6, 'accuracy': 5, 'ellipsoidal_altitude': 12.5},
      'activity': {'type': 'walking', 'confidence': 90},
      'battery': {'level': 0.8, 'is_charging': true},
      'is_moving': true,
      'odometer': 120,
      'event': 'motionchange',
    });

    expect(loc.coords.latitude, -23.5);
    expect(loc.coords.ellipsoidalAltitude, 12.5);
    expect(loc.activity.type, 'walking');
    expect(loc.battery.isCharging, isTrue);
    expect(loc.isMoving, isTrue);
    expect(loc.event, 'motionchange');
    expect(Location.fromMap(loc.toMap()).uuid, 'abc');
  });

  test('Geofence round-trips through toMap/fromMap', () {
    const g = Geofence(
        identifier: 'home', latitude: 1, longitude: 2, radius: 200, notifyOnEntry: true);
    final back = Geofence.fromMap(g.toMap());
    expect(back.identifier, 'home');
    expect(back.radius, 200);
    expect(back.notifyOnExit, isNull);
  });

  test('State.fromMap maps trackingMode and flags', () {
    final s = State.fromMap({'enabled': true, 'trackingMode': 0, 'isMoving': false});
    expect(s.enabled, isTrue);
    expect(s.trackingMode, TrackingMode.geofencesOnly);
    expect(s.isMoving, isFalse);
  });

  test('GeofenceEvent.fromMap parses geofence record and timestamp', () {
    final e = GeofenceEvent.fromMap({
      'identifier': 'home',
      'action': 'ENTER',
      'timestamp': '2026-01-01T00:00:00.000Z',
      'location': {
        'uuid': 'u1',
        'coords': {'latitude': 1.0, 'longitude': 2.0},
        'geofence': {'identifier': 'home', 'action': 'ENTER', 'timestamp': 't'},
      },
      'geofence': {
        'identifier': 'home',
        'latitude': 1.0,
        'longitude': 2.0,
        'radius': 150,
        'hits': 3,
        'entryState': 1,
      },
    });
    expect(e.action, 'ENTER');
    expect(e.geofence?.hits, 3);
    expect(e.geofence?.entryState, 1);
    expect(e.location.geofence?.identifier, 'home');
    expect(e.timestamp, '2026-01-01T00:00:00.000Z');
  });

  test('HttpEvent.fromMap reads status and body', () {
    final e = HttpEvent.fromMap({'success': true, 'status': 201, 'responseText': 'ok'});
    expect(e.success, isTrue);
    expect(e.status, 201);
    expect(e.responseText, 'ok');
  });
}
