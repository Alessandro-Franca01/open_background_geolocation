import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:open_background_geolocation/open_background_geolocation.dart';
import 'package:open_background_geolocation/src/headless.dart' show parseHeadlessEvent;

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const methods = MethodChannel('dev.openbg/methods');
  const events = EventChannel('dev.openbg/events');
  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  late MockStreamHandlerEventSink sink;

  setUp(() {
    messenger.setMockStreamHandler(
      events,
      MockStreamHandler.inline(onListen: (args, s) => sink = s),
    );
    messenger.setMockMethodCallHandler(methods, (call) async {
      if (call.method == 'getDiagnostics') {
        return {
          'serviceState': 'failed',
          'serviceStateAt': '2026-11-21T08:00:00.000Z',
          'lastError': {
            'code': 'service_permission',
            'message': 'not usable from the background',
            'timestamp': '2026-11-21T08:00:00.000Z',
          },
          'enabled': true,
          'isMoving': false,
          'locationAvailable': null,
          'provider': {'enabled': true, 'gps': true, 'network': true, 'status': 4},
          'backgroundPermission': false,
          'activityPermission': true,
          'notificationsEnabled': true,
          'ignoringBatteryOptimizations': false,
          'powerSaveMode': false,
          'appInForeground': true,
          'sdk': 34,
          'device': 'samsung SM-A546E',
          'stats': {'running': false, 'fixCount': 0},
        };
      }
      return true;
    });
  });

  tearDown(() async {
    messenger.setMockMethodCallHandler(methods, null);
    messenger.setMockStreamHandler(events, null);
    await BackgroundGeolocation.removeListeners();
  });

  test('getDiagnostics parses the native snapshot', () async {
    final d = await BackgroundGeolocation.getDiagnostics();
    expect(d.serviceState, 'failed');
    expect(d.lastErrorCode, 'service_permission');
    expect(d.enabled, isTrue);
    expect(d.locationAvailable, isNull);
    expect(d.provider['status'], 4);
    expect(d.backgroundPermission, isFalse);
    expect(d.activityPermission, isTrue);
    expect(d.notificationsEnabled, isTrue);
    expect(d.appInForeground, isTrue);
    expect(d.sdk, 34);
    expect(d.device, 'samsung SM-A546E');
    expect(d.stats?.running, isFalse);
  });

  test('onTrackingError and onLocationSilence receive typed events', () async {
    final errors = <TrackingErrorEvent>[];
    final silences = <LocationSilenceEvent>[];
    BackgroundGeolocation.onTrackingError(errors.add);
    BackgroundGeolocation.onLocationSilence(silences.add);
    await pumpEventQueue();

    sink.success({
      'event': 'trackingerror',
      'data': {'code': 'location_silence', 'message': 'no fix', 'timestamp': 't'},
    });
    sink.success({
      'event': 'locationsilence',
      'data': {'seconds': 120, 'lastFixAt': '2026-11-21T08:00:00.000Z'},
    });
    await pumpEventQueue();

    expect(errors.single.code, 'location_silence');
    expect(errors.single.message, 'no fix');
    expect(silences.single.seconds, 120);
    expect(silences.single.lastFixAt, '2026-11-21T08:00:00.000Z');
  });

  test('headless parses the new events', () {
    final e = parseHeadlessEvent('trackingerror', {'code': 'service_permission', 'message': 'm'});
    expect(e.event, isA<TrackingErrorEvent>());
    expect((e.event as TrackingErrorEvent).code, 'service_permission');
    expect(parseHeadlessEvent('locationsilence', {'seconds': 60}).event, isA<LocationSilenceEvent>());
  });

  test('GeoConfig serializes silenceTimeout', () {
    expect(const GeoConfig(silenceTimeout: 90).toMap(), {'silenceTimeout': 90});
    expect(GeoConfig.fromMap({'silenceTimeout': 90}).silenceTimeout, 90);
  });
}
