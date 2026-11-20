# open_background_geolocation

Open source background location tracking, motion detection and geofencing plugin for Flutter.
No license key, free in debug and release builds (MIT).

> **Status: early development.** Android tracking, geofencing and HTTP sync are implemented but
> have not been tested on a real device yet. See the roadmap below.

This is an independent, clean-room implementation. It is not affiliated with, endorsed by, or
derived from the code of any commercial SDK. See [docs/CLEAN_ROOM.md](docs/CLEAN_ROOM.md).

## Features

| Feature | Android | iOS |
|---|---|---|
| Config model and Dart API | done | done (shared Dart) |
| Continuous tracking (distance filter, elasticity, odometer) | implemented, untested | planned |
| Motion detection (moving / stationary state machine) | implemented, untested | planned |
| Foreground service and notification | implemented, untested | n/a |
| Local persistence (SQLite) | implemented | planned |
| `getCurrentPosition`, `changePace` | implemented, untested | planned |
| Geofencing (circular; enter / exit / dwell) | implemented, untested | planned |
| HTTP sync (`autoSync`, batch, retry) | implemented, untested | planned |
| Persistent logger (`Logger.getLog`) | implemented | planned |
| `watchPosition` | implemented, untested | planned |
| JWT authorization with token refresh (`Authorization`) | implemented, untested | planned |
| headless (`registerHeadlessTask`) | implemented, untested | n/a |
| schedule (`startSchedule`) | implemented, untested | planned |

## Usage

```dart
import 'package:open_background_geolocation/open_background_geolocation.dart' as bg;

bg.BackgroundGeolocation.onLocation((bg.Location l) {
  print('${l.coords.latitude}, ${l.coords.longitude}');
});
bg.BackgroundGeolocation.onMotionChange((bg.Location l) => print('moving: ${l.isMoving}'));

await bg.BackgroundGeolocation.ready(const bg.Config(
  geolocation: bg.GeoConfig(desiredAccuracy: bg.DesiredAccuracy.high, distanceFilter: 10),
  app: bg.AppConfig(stopOnTerminate: false, startOnBoot: true),
));
await bg.BackgroundGeolocation.start();
```

## Android notes

- Requires Google Play Services (`play-services-location`).
- `minSdk` 21, `compileSdk` 35.
- Declared permissions: fine/coarse/background location, activity recognition, foreground
  service (location), notifications, boot completed, internet.

- Geofences: `notifyOnEntry` and `notifyOnExit` default to true, `notifyOnDwell` to false. Polygon
  geofences are not supported. Play Services caps monitoring at 100 geofences; beyond 95 only the
  nearest are active.
- HTTP: a 2xx response deletes the uploaded records, anything else keeps them queued. Cleartext
  `http://` URLs need `usesCleartextTraffic` in your app manifest.

## Headless (Android)

With `AppConfig(stopOnTerminate: false, enableHeadless: true)` the plugin keeps tracking after the
app is closed from the recents list and delivers events to a Dart callback that runs in a
background engine:

```dart
@pragma('vm:entry-point')
Future<void> headlessTask(bg.HeadlessEvent event) async {
  if (event.event is bg.Location) { /* upload, log, ... */ }
}

void main() {
  runApp(const MyApp());
  bg.BackgroundGeolocation.registerHeadlessTask(headlessTask);
}
```

The task must be a top-level or static function. Call `registerHeadlessTask` on every app start so
the stored callback handle stays valid after an app update.

## Schedule (Android)

```dart
await bg.BackgroundGeolocation.ready(bg.Config(
  app: bg.AppConfig(schedule: ['2-6 08:00-18:00', '7 09:00-12:00 geofence']),
));
await bg.BackgroundGeolocation.startSchedule();
```

Each entry is `"<days> <HH:mm>-<HH:mm> [geofence]"`. Days are 1-7 with **1 = Sunday**, as a range
(`2-6`), a list (`1,7`) or a mix. The start is inclusive and the end exclusive; `24:00` is a valid
end, and an end before the start crosses midnight (`22:00-06:00`). The `geofence` suffix monitors
geofences only. If windows overlap, continuous tracking wins. Malformed entries are ignored and
noted in the plugin log; `startSchedule()` does nothing when no entry is valid.

`stopSchedule()` stops scheduling but leaves tracking as it is. Alarms are inexact by default (they
may slip by a few minutes in Doze). Set `scheduleUseAlarmManager: true` for exact alarms; on
Android 12+ your app must hold the `SCHEDULE_EXACT_ALARM` permission, which the plugin does not
declare for you. Android 12+ can also refuse to start a foreground service from the background, so
exempt the app from battery optimization; a refused start is logged with tag `schedule`.

## Authorization (JWT)

```dart
Config(
  http: HttpConfig(url: 'https://api.example.com/locations'),
  authorization: Authorization(
    accessToken: token,
    refreshToken: refresh,
    expires: expiresAtEpochSeconds,
    refreshUrl: 'https://api.example.com/auth/refresh',
    refreshPayload: {'refresh_token': '{refreshToken}'},
  ),
)
```

Uploads carry `Authorization: Bearer <accessToken>`. When the token is about to expire, or the
server answers 401, the plugin POSTs the payload (form-encoded) to `refreshUrl`, reads
`accessToken` / `access_token`, `refreshToken` / `refresh_token` and `expires` / `expires_in` from
the JSON response, stores the pair and retries once. If the refresh fails the records stay queued.
Listen with `BackgroundGeolocation.onAuthorization`. `Config.reset` defaults to true, so pass
`reset: false` to `ready()` if you want the plugin to keep tokens it refreshed in an earlier run.

## Roadmap

See [docs/API_SPEC.md](docs/API_SPEC.md).

## License

MIT, see [LICENSE](LICENSE).
