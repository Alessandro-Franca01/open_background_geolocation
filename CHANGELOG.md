## Unreleased

* Android: persistent diagnostic logger (`Logger.getLog`, `destroyLog`, `error/warn/info/debug`),
  filtered by `LoggerConfig.logLevel` and purged by `logMaxDays`. The engine now logs state
  transitions, dropped fixes, provider/network changes, Doze and power-save changes, service
  lifecycle, HTTP sync and geofence transitions.
* Android: `powersavechange` events are now emitted.
* Android: `watchPosition` / `stopWatchPosition` implemented (independent streams, optional persist,
  timeout error 408). Dart: each watcher now only receives its own events.

## 0.0.1

* Initial Dart API and Android tracking engine, geofencing and HTTP sync.
