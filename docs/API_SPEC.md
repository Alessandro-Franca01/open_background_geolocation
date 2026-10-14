# Especificação da API alvo (fonte: documentação pública)

## Métodos estáticos (`BackgroundGeolocation`)
Ciclo de vida: `ready(Config)`, `start()`, `stop()`, `startGeofences()`, `changePace(bool)`,
`setConfig(Config)`, `reset([Config])`, `startSchedule()`, `stopSchedule()`.

Posição: `getCurrentPosition(...)`, `watchPosition(...)`, `odometer`, `setOdometer(double)`.

Geofences: `addGeofence`, `addGeofences`, `geofenceExists`, `getGeofence`, `geofences`,
`removeGeofence`, `removeGeofences([ids])`.

Persistência: `getLocations([query])`, `count`, `insertLocation(map)`, `destroyLocation(uuid)`,
`destroyLocations()`, `sync()`.

Plataforma: `providerState`, `requestPermission`, `requestTemporaryFullAccuracy` (iOS 14+),
`startBackgroundTask`, `stopBackgroundTask`, `registerHeadlessTask` (Android),
`removeListeners()`.

## Eventos (cada um retorna `Subscription` com `remove()`)
onLocation, onMotionChange, onActivityChange, onAuthorization, onConnectivityChange,
onEnabledChange, onGeofence, onGeofencesChange, onHeartbeat, onHttp, onLocationFilter,
onNotificationAction (Android), onPowerSaveChange, onProviderChange, onSchedule.

## Config (grupos)
geolocation (desiredAccuracy, distanceFilter, stopTimeout, stationaryRadius, ...),
activity (disableStopDetection, motionTriggerDelay, ...),
http (url, method, autoSync, headers, params, ...),
persistence (persistMode, maxDaysToPersist, extras, ...),
app (stopOnTerminate, startOnBoot, enableHeadless, ...),
logger (debug, logLevel, logMaxDays), authorization, reset.

Lacunas: tipos exatos, defaults e payloads detalhados ainda precisam ser lidos nas páginas
GeoConfig, ActivityConfig, HttpConfig, PersistenceConfig, AppConfig, Location, etc.

## Roadmap
1. Núcleo Dart: Config, State, Location, eventos, canal de plataforma.
2. Android: tracking com Fused Location + foreground service + motion detection.
3. Persistência SQLite + sync HTTP.
4. Geofencing.
5. iOS: CoreLocation/CoreMotion.
6. Headless, schedule, heartbeat, testes de bateria.
