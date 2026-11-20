import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:open_background_geolocation/open_background_geolocation.dart';
import 'package:open_background_geolocation/src/headless.dart' show parseHeadlessEvent;

Map<String, dynamic> _loc() => {
      'uuid': 'u1',
      'coords': {'latitude': 1.0, 'longitude': 2.0},
    };

@pragma('vm:entry-point')
Future<void> topLevelTask(HeadlessEvent e) async {}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('parseHeadlessEvent', () {
    test('location and motionchange become Location', () {
      expect(parseHeadlessEvent('location', _loc()).event, isA<Location>());
      expect(parseHeadlessEvent('motionchange', _loc()).event, isA<Location>());
    });

    test('geofence becomes GeofenceEvent', () {
      final e = parseHeadlessEvent('geofence', {
        'identifier': 'home',
        'action': 'ENTER',
        'location': _loc(),
      });
      expect(e.name, 'geofence');
      expect((e.event as GeofenceEvent).identifier, 'home');
    });

    test('http, heartbeat, provider and connectivity events are typed', () {
      expect(parseHeadlessEvent('http', {'success': true, 'status': 200}).event, isA<HttpEvent>());
      expect(parseHeadlessEvent('heartbeat', {'location': _loc()}).event, isA<HeartbeatEvent>());
      expect(parseHeadlessEvent('providerchange', {'enabled': true}).event, isA<ProviderChangeEvent>());
      expect(
        (parseHeadlessEvent('connectivitychange', {'connected': true}).event as ConnectivityChangeEvent)
            .connected,
        isTrue,
      );
    });

    test('flag events accept both bool and {enabled}', () {
      expect(parseHeadlessEvent('enabledchange', true).event, isTrue);
      expect(parseHeadlessEvent('powersavechange', {'enabled': true}).event, isTrue);
      expect(parseHeadlessEvent('powersavechange', {'enabled': false}).event, isFalse);
    });

    test('schedule becomes State and unknown names keep the raw payload', () {
      expect(parseHeadlessEvent('schedule', {'enabled': true}).event, isA<State>());
      expect(parseHeadlessEvent('custom', {'a': 1}).event, {'a': 1});
    });
  });

  group('registerHeadlessTask', () {
    final calls = <MethodCall>[];
    setUp(() {
      calls.clear();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        const MethodChannel('dev.openbg/methods'),
        (call) async {
          calls.add(call);
          return true;
        },
      );
    });

    test('sends both callback handles to the native side', () async {
      expect(await BackgroundGeolocation.registerHeadlessTask(topLevelTask), isTrue);
      expect(calls.single.method, 'registerHeadlessTask');
      final args = calls.single.arguments as Map;
      expect(args['dispatcher'], isA<int>());
      expect(args['callback'], isA<int>());
    });

    test('rejects closures, which cannot be resolved in the background isolate', () async {
      await expectLater(
        BackgroundGeolocation.registerHeadlessTask((e) async {}),
        throwsArgumentError,
      );
      expect(calls, isEmpty);
    });
  });
}
