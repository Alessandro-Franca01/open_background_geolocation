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
| `watchPosition`, schedule, headless | planned | planned |

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

## Roadmap

See [docs/API_SPEC.md](docs/API_SPEC.md).

## License

MIT, see [LICENSE](LICENSE).
