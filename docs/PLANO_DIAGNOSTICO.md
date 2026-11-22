# Plano — falha do serviço em primeiro plano, diagnóstico e fluxo mudo (Android)

> Status: implementado (Android), testes JVM e Dart verdes, **não validado em aparelho**.
> Uso: seção "Diagnóstico e falhas" de `docs/BATERIA.md`.

## Contexto

O relatório de campo do app Segurança Municipal (`TESTES-GPS-SEGURANCA-MUNICIPAL.md`, 20/11/2026,
feito com o `geolocator`, não com esta lib) mostrou um serviço em primeiro plano que "abriu e
ficou mudo" sem nenhum erro visível, e que só foi descoberto graças a um cartão de diagnóstico no app.
Cruzando com o código desta lib, três lacunas ficam de fora do PR #2 (bateria):

1. **Falha do serviço em primeiro plano derruba o app sem log.** `TrackingService.start()`
   (`TrackingService.kt:61`) chama `startForegroundService` e `onStartCommand` (`:32`) chama
   `startForeground` sem `try/catch`. No Android 12+ iniciar o serviço em segundo plano pode lançar
   `ForegroundServiceStartNotAllowedException`; no Android 14+ o tipo `location` exige permissão de
   localização utilizável no momento → `SecurityException` quando só há `whileInUse` e o app não
   está visível. Caminhos afetados: `BootReceiver` (`Receivers.kt:57`), `Scheduler.evaluate`
   (`Scheduler.kt:65`, o `try` de lá não pega a falha, que é assíncrona) e o restart `START_STICKY`.
   Além disso `ready()` (`OpenBackgroundGeolocationPlugin.kt:108`) não retoma o rastreamento, então
   depois de uma falha nada religa até o app chamar `start()` de novo.
2. **Sem diagnóstico consolidado.** As peças existem espalhadas (`providerState()`,
   `isIgnoringBatteryOptimizations()`, `stats()` do PR #2, log de `begin()`), mas o app não tem
   uma chamada única para mostrar "por que não está rastreando".
3. **Sem aviso de fluxo mudo.** Nada avisa quando o motor está em MOVING, com o GPS pedido, e
   nenhuma posição chega.

Recomendação do relatório **não adotada**: "modo sem notificação" como plano B — sem serviço em
primeiro plano o Android corta a localização em segundo plano em minutos; não serve para turno de 8 h.

Resultado esperado: a falha vira log + evento + campo de diagnóstico (sem crash) e é retomada
quando o app volta ao primeiro plano; o app tem `getDiagnostics()`; e um evento avisa (e tenta
corrigir) quando o fluxo fica mudo.

## Mudanças

### 0. Documento do plano
Copiar este plano para `docs/PLANO_DIAGNOSTICO.md` (mesmo padrão de `docs/PLANO_BATERIA.md`).

### 1. Novo `Diagnostics.kt` (estado de saúde)
Objeto Kotlin pequeno que guarda:
- `serviceState`: `stopped | starting | running | failed` e `serviceStateAt`.
- `lastError`: `{code, message, at}` — **persistido** em `SharedPreferences` (uma falha no boot
  acontece sem UI; o app precisa ler depois). Códigos: `service_start_not_allowed`,
  `service_permission`, `location_silence`, `location_unavailable`.
- `fun record(code, message)`: grava `lastError`, `Logger.e`, emite evento `trackingerror`.
- `fun snapshot(ctx)`: mapa com `serviceState`, `lastError`, `providerState()` (reusa
  `TrackingEngine.providerState`), permissões (`activity`, `notifications` via
  `NotificationManagerCompat.areNotificationsEnabled`), `ignoringBatteryOptimizations` e
  `powerSaveMode` (tornar `isIgnoringBatteryOptimizations` `internal` em `TrackingEngine.kt`),
  `appInForeground`, `sdk`, `manufacturer/model`, e `stats` (`TrackingEngine.stats()`).
Lógica pura de decisão (ver item 2) em `DiagnosticsLogic` para teste JVM, no padrão de
`ScheduleLogic.kt` / `StopLogic.kt`.

### 2. Proteger o início do serviço
- `TrackingService.start(ctx)`:
  - **Pré-checagem** (evita o crash "did not call startForeground in time", que um `catch`
    depois não impede): `DiagnosticsLogic.canStartLocationService(sdk, hasLocation,
    hasBackground, appInForeground)` — no SDK 34+ sem `ACCESS_BACKGROUND_LOCATION` e com o app
    fora do primeiro plano → não inicia, `Diagnostics.record("service_permission", ...)`,
    `serviceState = failed`. Primeiro plano via `ActivityManager.getMyMemoryState()`
    (`importance == IMPORTANCE_FOREGROUND`).
  - `try/catch` em `ContextCompat.startForegroundService`: `IllegalStateException` (inclui
    `ForegroundServiceStartNotAllowedException` no 31+) → `record("service_start_not_allowed")`.
- `TrackingService.onStartCommand`: `try/catch (SecurityException | IllegalStateException)` em
  `startForeground`; na falha, `record(...)`, `stopSelf()`, `START_NOT_STICKY`, **não** chama
  `TrackingEngine.begin()`. Sucesso → `serviceState = running`; `onDestroy` → `stopped`.
- `Settings.enabled` **continua true** após a falha (a intenção do app era rastrear).
- `ready()`: se `Settings.enabled && trackingMode == 1` e o motor não está rodando e o app está em
  primeiro plano → `TrackingService.start()` (retoma após falha no boot/schedule). Log
  `"ready(): resuming tracking"`.

### 3. Fluxo mudo
- Config nova `geolocation.silenceTimeout` (segundos, padrão `120`, `0` desliga) em
  `Settings.defaults()` e `GeoConfig` (`lib/src/config.dart`).
- Em `TrackingEngine`: `Runnable` agendado em `enterMoving()` e reagendado a cada fix aceito
  (mesmo padrão de `distanceCheck`); cancelado em `enterStationary()`/`end()`.
- Ao disparar (MOVING, nenhum fix em `silenceTimeout`): como com `distanceFilter` a ausência de
  fix pode ser só "não andou", faz **uma sondagem** com `getCurrentLocation` (reusa o
  `CurrentLocationRequest` de `currentPosition()`, timeout 30 s):
  - sondagem com posição → fluxo ok, só filtrado; alimenta `stopDetector.onFix` e reagenda.
  - sondagem sem posição → `Diagnostics.record("location_silence", ...)`, evento
    `locationsilence {seconds, lastFixAt}` e **reabre** o pedido (`requestLocationUpdates()`),
    o equivalente ao plano B do app. Limitar a 1 reabertura por janela para não entrar em loop.
- Implementar `onLocationAvailability` no `LocationCallback` de `requestLocationUpdates()`:
  logar mudanças e registrar `location_unavailable` quando ficar indisponível.

### 4. Dart
- `lib/src/background_geolocation.dart`: `getDiagnostics()` (método `getDiagnostics` no
  `handle()` do plugin), `onTrackingError(cb)` e `onLocationSilence(cb)` no padrão de
  `onPowerSaveChange`/`_subscribe`.
- `lib/src/models.dart`: `Diagnostics` (com `stats` reusando `TrackingStats` do PR #2).
- `lib/src/events.dart`: `TrackingErrorEvent {code, message, timestamp}`,
  `LocationSilenceEvent {seconds, lastFixAt}`.
- Headless: adicionar `trackingerror` e `locationsilence` em `HeadlessTask.EVENTS`
  (`HeadlessTask.kt:24`) e no parse de `lib/src/headless.dart` — a falha no boot acontece com o
  app fechado, então o headless é onde o app fica sabendo.
- iOS: não implementar agora (rascunho nunca compilado); `getDiagnostics` no iOS devolve o
  mínimo ou fica documentado como só Android.

### 5. Exemplo e docs
- `example/`: botão/cartão "Diagnóstico" que mostra `getDiagnostics()` e os eventos novos.
- `docs/BATERIA.md` → seção "Diagnóstico e falhas" (o que cada `lastError.code` significa e o
  que pedir ao usuário). Atualizar `docs/PLANO_IMPLEMENTACAO.md` (status) e
  `docs/TESTES_CAMPO.md` (pendências e um teste novo: boot com só `whileInUse`).

## Arquivos

- novos: `Diagnostics.kt`, `DiagnosticsLogic.kt` (ou dentro de `Diagnostics.kt`),
  `android/src/test/.../DiagnosticsLogicTest.kt`, `docs/PLANO_DIAGNOSTICO.md`
- `TrackingService.kt`, `TrackingEngine.kt`, `OpenBackgroundGeolocationPlugin.kt`,
  `Settings.kt`, `HeadlessTask.kt`
- `lib/src/background_geolocation.dart`, `models.dart`, `events.dart`, `config.dart`, `headless.dart`
- `test/` (Dart), `example/lib/`, `docs/`

## Branch e PR

Depende do PR #2 (`stats`, `StopDetector`). Branch `feature/diagnostics-service-guard` a partir de
`feature/battery-stop-detection`; PR empilhado com base nessa branch (rebase para `main` quando o
#2 for mergeado). Commit único: `Guard the foreground service start and add diagnostics on Android`.

## Verificação

1. JVM (`example/android`: `./gradlew :open_background_geolocation:testDebugUnitTest`):
   `DiagnosticsLogicTest` — SDK 34 sem background e fora do primeiro plano → bloqueia; com
   background ou em primeiro plano → permite; SDK < 34 → permite; regra de "1 reabertura por janela".
2. Dart: `flutter test` (parse de `Diagnostics`, `TrackingErrorEvent`, `LocationSilenceEvent`,
   `getDiagnostics` via mock do channel no padrão de `test/watch_position_test.dart`, headless
   dos eventos novos) e `flutter analyze`.
3. `cd example && flutter build apk --debug`.
4. Aparelho (manual): permissão só "Ao usar o app" + `startOnBoot: true` → reiniciar o celular →
   app **não** cai; `getDiagnostics().lastError.code == service_permission`; abrir o app →
   `ready()` retoma e `serviceState == running`. Fluxo mudo: em MOVING, cobrir o aparelho / ambiente
   fechado por > 2 min → log da sondagem e, se sem fix, evento `locationsilence`.
