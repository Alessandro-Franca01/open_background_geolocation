import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:open_background_geolocation/open_background_geolocation.dart';

Map<String, dynamic> _fix(String uuid) => {
      'uuid': uuid,
      'coords': {'latitude': 1.0, 'longitude': 2.0},
    };

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const methods = MethodChannel('dev.openbg/methods');
  const events = EventChannel('dev.openbg/events');
  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  late MockStreamHandlerEventSink sink;
  late List<MethodCall> calls;
  var nextId = 1;
  Map<String, dynamic>? emitBeforeReply;

  void emit(Map<String, dynamic> data) => sink.success({'event': 'watchposition', 'data': data});

  setUp(() {
    calls = [];
    nextId = 1;
    emitBeforeReply = null;
    messenger.setMockStreamHandler(
      events,
      MockStreamHandler.inline(onListen: (args, s) => sink = s),
    );
    messenger.setMockMethodCallHandler(methods, (call) async {
      calls.add(call);
      if (call.method == 'watchPosition') {
        final id = nextId++;
        final early = emitBeforeReply;
        if (early != null) emit({'id': id, ...early});
        return id;
      }
      return true;
    });
  });

  tearDown(() async {
    await BackgroundGeolocation.removeListeners();
  });

  test('each watcher only receives events with its own id', () async {
    final a = <String>[];
    final b = <String>[];
    final idA = await BackgroundGeolocation.watchPosition((l) => a.add(l.uuid));
    final idB = await BackgroundGeolocation.watchPosition((l) => b.add(l.uuid));
    expect([idA, idB], [1, 2]);

    emit({'id': idA, 'location': _fix('for-a')});
    emit({'id': idB, 'location': _fix('for-b')});
    await pumpEventQueue();

    expect(a, ['for-a']);
    expect(b, ['for-b']);
  });

  test('stopWatchPosition stops delivery for that watcher only', () async {
    final a = <String>[];
    final b = <String>[];
    final idA = await BackgroundGeolocation.watchPosition((l) => a.add(l.uuid));
    final idB = await BackgroundGeolocation.watchPosition((l) => b.add(l.uuid));

    expect(await BackgroundGeolocation.stopWatchPosition(idA), isTrue);
    expect(calls.last.method, 'stopWatchPosition');
    expect(calls.last.arguments, idA);

    emit({'id': idA, 'location': _fix('late-a')});
    emit({'id': idB, 'location': _fix('still-b')});
    await pumpEventQueue();

    expect(a, isEmpty);
    expect(b, ['still-b']);
  });

  test('an error envelope reaches only the failure callback of its watcher', () async {
    final errors = <int>[];
    final other = <int>[];
    final id = await BackgroundGeolocation.watchPosition((_) {}, failure: (e) => errors.add(e.code));
    await BackgroundGeolocation.watchPosition((_) {}, failure: (e) => other.add(e.code));

    emit({'id': id, 'error': 408});
    await pumpEventQueue();

    expect(errors, [408]);
    expect(other, isEmpty);
  });

  test('a fix delivered before the id is known is not lost', () async {
    final got = <String>[];
    emitBeforeReply = {'location': _fix('early')};
    await BackgroundGeolocation.watchPosition((l) => got.add(l.uuid));
    await pumpEventQueue();
    expect(got, ['early']);
  });

  test('arguments are forwarded and null options are omitted', () async {
    await BackgroundGeolocation.watchPosition((_) {}, interval: 5000, persist: true, timeout: 30);
    expect(calls.single.method, 'watchPosition');
    expect(calls.single.arguments, {'interval': 5000, 'persist': true, 'timeout': 30});
  });
}
