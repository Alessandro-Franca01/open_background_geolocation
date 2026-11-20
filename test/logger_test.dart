import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:open_background_geolocation/open_background_geolocation.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  final calls = <MethodCall>[];

  setUp(() {
    calls.clear();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
      const MethodChannel('dev.openbg/methods'),
      (call) async {
        calls.add(call);
        switch (call.method) {
          case 'getLog':
            return '2026-01-01T00:00:00.000Z INFO [engine] -> MOVING';
          case 'destroyLog':
            return true;
          default:
            return true;
        }
      },
    );
  });

  test('LoggerConfig.toMap serializes level and retention', () {
    final map = const LoggerConfig(debug: true, logLevel: LogLevel.verbose, logMaxDays: 3).toMap();
    expect(map, {'debug': true, 'logLevel': 'verbose', 'logMaxDays': 3});
  });

  test('Logger.getLog sends only the given bounds and returns the text', () async {
    final text = await Logger.getLog(
      limit: 10,
      start: DateTime.utc(2026, 1, 1),
      order: 'DESC',
    );
    expect(text, contains('MOVING'));
    expect(calls.single.method, 'getLog');
    expect(calls.single.arguments, {
      'limit': 10,
      'start': DateTime.utc(2026, 1, 1).millisecondsSinceEpoch,
      'order': 'DESC',
    });
  });

  test('Logger writers map to native log levels', () async {
    await Logger.error('a');
    await Logger.warn('b');
    await Logger.notice('c');
    await Logger.debug('d');
    expect(calls.map((c) => (c.arguments as Map)['level']), ['error', 'warning', 'info', 'debug']);
    expect(calls.every((c) => c.method == 'log'), isTrue);
  });

  test('Logger.destroyLog returns the native result', () async {
    expect(await Logger.destroyLog(), isTrue);
    expect(calls.single.method, 'destroyLog');
  });
}
