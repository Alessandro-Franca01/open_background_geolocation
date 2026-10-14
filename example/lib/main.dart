import 'package:flutter/material.dart';
import 'package:open_background_geolocation/open_background_geolocation.dart' as bg;

void main() => runApp(const MyApp());

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
    bg.BackgroundGeolocation.ready(const bg.Config(
      geolocation: bg.GeoConfig(desiredAccuracy: bg.DesiredAccuracy.high, distanceFilter: 10),
      app: bg.AppConfig(stopOnTerminate: false, startOnBoot: true),
      logger: bg.LoggerConfig(debug: true),
    )).then((s) => setState(() => _enabled = s.enabled));
  }

  void _add(String line) => setState(() => _log.insert(0, line));

  Future<void> _toggle() async {
    final s = _enabled
        ? await bg.BackgroundGeolocation.stop()
        : await bg.BackgroundGeolocation.start();
    setState(() => _enabled = s.enabled);
  }

  @override
  Widget build(BuildContext context) => MaterialApp(
        home: Scaffold(
          appBar: AppBar(title: const Text('open_background_geolocation')),
          body: ListView(children: _log.map((e) => ListTile(title: Text(e))).toList()),
          floatingActionButton: FloatingActionButton(
            onPressed: _toggle,
            child: Icon(_enabled ? Icons.stop : Icons.play_arrow),
          ),
        ),
      );
}
