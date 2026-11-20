## Unreleased

* Android: persistent diagnostic logger (`Logger.getLog`, `destroyLog`, `error/warn/info/debug`),
  filtered by `LoggerConfig.logLevel` and purged by `logMaxDays`. The engine now logs state
  transitions, dropped fixes, provider/network changes, Doze and power-save changes, service
  lifecycle, HTTP sync and geofence transitions.
* Android: `powersavechange` events are now emitted.
* Android: headless mode. `BackgroundGeolocation.registerHeadlessTask(task)` receives events in a
  background Flutter engine while the app has no UI (`enableHeadless: true`). The plugin no longer
  clears another engine's event sink when one engine detaches.
* Android: `watchPosition` / `stopWatchPosition` implemented (independent streams, optional persist,
  timeout error 408). Dart: each watcher now only receives its own events.

* Android: JWT authorization for HTTP sync. `Config(authorization: Authorization(...))` sends
  `Authorization: Bearer`, refreshes the token before it expires or after a 401 (one retry), stores
  the new pair and fires `onAuthorization`.

## 0.0.1

* Initial Dart API and Android tracking engine, geofencing and HTTP sync.
