import 'package:flutter/material.dart';
import 'package:open_background_geolocation/open_background_geolocation.dart' as bg;

/// Runs in a background isolate while the app has no UI (needs `enableHeadless: true` and
/// `stopOnTerminate: false`). It writes to the plugin log so the result can be read afterwards.
@pragma('vm:entry-point')
Future<void> headlessTask(bg.HeadlessEvent event) async {
  final e = event.event;
  if (e is bg.Location) {
    await bg.Logger.info('headless ${event.name}: ${e.coords.latitude}, ${e.coords.longitude}');
  } else {
    await bg.Logger.info('headless ${event.name}');
  }
}

void main() {
  runApp(const MyApp());
  bg.BackgroundGeolocation.registerHeadlessTask(headlessTask);
}

class MyApp extends StatefulWidget {
  const MyApp({super.key});

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  final List<String> _log = [];
  bool _enabled = false;

  @override
  void initState() {
    super.initState();
    bg.BackgroundGeolocation.onLocation((l) => _add('${l.coords.latitude}, ${l.coords.longitude}'));
    bg.BackgroundGeolocation.onMotionChange((l) => _add('motion: ${l.isMoving}'));
    bg.BackgroundGeolocation.onSchedule((s) {
      _add('schedule event: enabled=${s.enabled}');
      setState(() => _enabled = s.enabled);
    });
    bg.BackgroundGeolocation.ready(const bg.Config(
      geolocation: bg.GeoConfig(desiredAccuracy: bg.DesiredAccuracy.high, distanceFilter: 10),
      app: bg.AppConfig(stopOnTerminate: false, startOnBoot: true, enableHeadless: true),
      logger: bg.LoggerConfig(debug: true),
    )).then((s) => setState(() => _enabled = s.enabled));
  }

  void _add(String line) => setState(() => _log.insert(0, line));

  /// Stops tracking and schedules a window from +1 to +3 minutes, to watch it turn on and off.
  Future<void> _testSchedule() async {
    String hm(DateTime t) =>
        '${t.hour.toString().padLeft(2, '0')}:${t.minute.toString().padLeft(2, '0')}';
    final now = DateTime.now();
    final window = '1-7 ${hm(now.add(const Duration(minutes: 1)))}-${hm(now.add(const Duration(minutes: 3)))}';
    await bg.BackgroundGeolocation.stop();
    await bg.BackgroundGeolocation.setConfig(bg.Config(app: bg.AppConfig(schedule: [window])));
    final s = await bg.BackgroundGeolocation.startSchedule();
    setState(() => _enabled = s.enabled);
    _add('schedule $window (schedulerEnabled=${s.schedulerEnabled})');
  }

  int? _watchId;

  Future<void> _toggleWatch() async {
    final id = _watchId;
    if (id != null) {
      await bg.BackgroundGeolocation.stopWatchPosition(id);
      setState(() => _watchId = null);
      _add('watch stopped');
      return;
    }
    final newId = await bg.BackgroundGeolocation.watchPosition(
      (l) => _add('watch: ${l.coords.latitude}, ${l.coords.longitude}'),
      failure: (e) => _add('watch error ${e.code}'),
      interval: 5000,
      timeout: 60,
    );
    setState(() => _watchId = newId);
    _add('watch started (id $newId)');
  }

  Future<void> _showLog(BuildContext context) async {
    final text = await bg.Logger.getLog(order: 'DESC', limit: 300);
    if (!context.mounted) return;
    showDialog<void>(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('Plugin log'),
        content: SingleChildScrollView(
          child: SelectableText(text.isEmpty ? '(empty)' : text, style: const TextStyle(fontSize: 11)),
        ),
        actions: [
          TextButton(
            onPressed: () async {
              await bg.Logger.destroyLog();
              if (context.mounted) Navigator.pop(context);
            },
            child: const Text('Clear'),
          ),
          TextButton(onPressed: () => Navigator.pop(context), child: const Text('Close')),
        ],
      ),
    );
  }

  Future<void> _toggle() async {
    final s = _enabled
        ? await bg.BackgroundGeolocation.stop()
        : await bg.BackgroundGeolocation.start();
    setState(() => _enabled = s.enabled);
  }

  @override
  Widget build(BuildContext context) => MaterialApp(
        home: Scaffold(
          appBar: AppBar(
            title: const Text('open_background_geolocation'),
            actions: [
              IconButton(
                tooltip: 'Testar schedule (+1 a +3 min)',
                icon: const Icon(Icons.schedule),
                onPressed: _testSchedule,
              ),
              IconButton(
                tooltip: _watchId == null ? 'Iniciar watch' : 'Parar watch',
                icon: Icon(_watchId == null ? Icons.visibility_outlined : Icons.visibility_off_outlined),
                onPressed: _toggleWatch,
              ),
              Builder(
                builder: (ctx) => IconButton(
                  tooltip: 'Ver log',
                  icon: const Icon(Icons.article_outlined),
                  onPressed: () => _showLog(ctx),
                ),
              ),
            ],
          ),
          body: ListView(children: _log.map((e) => ListTile(title: Text(e))).toList()),
          floatingActionButton: FloatingActionButton(
            onPressed: _toggle,
            child: Icon(_enabled ? Icons.stop : Icons.play_arrow),
          ),
        ),
      );
}
