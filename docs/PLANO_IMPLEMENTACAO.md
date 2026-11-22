# Plano de implementação — open_background_geolocation (6 etapas)

## Status da implementação

| Etapa | Estado |
|---|---|
| 1. Logger | feito (Android), testado por unidade; não validado em aparelho |
| 2. `watchPosition` | feito (Android), testes Dart; não validado em aparelho |
| 3. Headless | feito (Android), compila; **não validado em aparelho** |
| 4. JWT | feito (Android), testes JVM; não validado contra backend real |
| 5. Schedule | feito (Android), testes JVM; não validado em aparelho |
| 6. iOS | código escrito, **nunca compilado nem executado** (sem Mac); ver `docs/IOS_VALIDACAO.md` |
| Bateria (Android) | detecção de parada por distância, histerese da elasticidade e `stats`; testes JVM; **não validado em aparelho**; ver `docs/PLANO_BATERIA.md` e `docs/BATERIA.md` |

## Contexto

Plugin Flutter de geolocalização em segundo plano (`open_background_geolocation`, MIT, clean-room).
O Android já tem rastreamento, detecção de movimento, SQLite, geofences e sync HTTP
(commits `e456a26`, `d209a48`). O iOS não começou. Os testes de campo foram adiados; este plano
cobre as funcionalidades que faltam, na ordem de valor para o app do motorista (turnos de 8 h):

1. Logger persistente (Android)
2. `watchPosition` / `stopWatchPosition` (Android)
3. Headless — eventos para o Dart com o app fechado (Android)
4. Autorização JWT / refresh token no HTTP (Android)
5. Schedule — rastrear só em janelas de horário (Android)
6. iOS (CoreLocation + CoreMotion)

Cada etapa é independente, termina em **um commit** e deve deixar `flutter test` verde e o
exemplo compilando.

## Regras para todas as etapas

- **Clean-room** (`docs/CLEAN_ROOM.md`):
  só documentação pública e APIs de plataforma. Nunca descompilar nem copiar código do SDK
  comercial; não usar a marca dele.
- **Contrato Dart ↔ nativo** (seguir o padrão existente, não inventar outro):
  - Métodos: `MethodChannel('dev.openbg/methods')`, tratados no `when` de
    `OpenBackgroundGeolocationPlugin.handle()` (`android/.../OpenBackgroundGeolocationPlugin.kt`).
    No Dart, usar `_invoke<T>()` em `lib/src/background_geolocation.dart`.
  - Eventos: `EventBus.emit(name, data)` (`android/.../EventBus.kt`) → envelope
    `{event, data}` → `_subscribe<T>(name, parse, cb)` no Dart.
  - Config: ler com `Settings.int/bool/string/double/obj/array(grupo, chave, default)`
    (`Settings.kt`); todo default novo vai em `Settings.defaults()`. Chaves que devem ser
    substituídas inteiras (não mescladas) vão em `Settings.replaceKeys`.
  - JSON: helpers `toMap()`, `toJson()`, `isoTimestamp()` em `Json.kt`.
  - Banco: `Db.kt` (`SQLiteOpenHelper`, versão atual **2**). Tabela nova = subir versão e
    tratar em `onUpgrade` com `if (oldVersion < N)`.
- **Dart**: classes de config com campos `final` anuláveis, `const` construtor e `toMap()` via
  `_compact()` (`lib/src/config.dart`). Exportar tipos novos em `lib/open_background_geolocation.dart`.
- **Testes Dart**: em `test/`, no estilo de `test/models_test.dart`. Para métodos de canal, usar
  `TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler`.
- **Testes Kotlin (JVM)**: o `android/build.gradle` já aponta `src/test/kotlin`, mas não tem
  dependência. Na primeira etapa que precisar, adicionar `testImplementation("junit:junit:4.13.2")`.
  Manter a lógica testável em funções puras **sem `android.*` nem `org.json`** (o `org.json` do
  Android é stub no JVM).
- **Ao fim de cada etapa**: atualizar a tabela de features do `README.md`, a seção 6 de
  `docs/TESTES_CAMPO.md` (pendências) e o `CHANGELOG.md`.
- **Não mudar comportamento existente** fora do escopo da etapa.

---

## Etapa 1 — Logger persistente (Android)

**Por quê:** `LoggerConfig` (`debug`, `logLevel`, `logMaxDays`) já é aceito mas não faz nada.
Sem log, as lacunas de 23–31 min do teste de campo não têm explicação.

**Nativo**
- Novo `android/.../Logger.kt` (`object Logger`): `e/w/i/d/v(tag, msg)`.
  - Nível vem de `Settings.string("logger","logLevel","verbose")`; ordem
    `off < error < warning < info < debug < verbose` (igual ao enum `LogLevel` do Dart).
  - Grava na tabela nova `logs (id INTEGER PK AUTOINCREMENT, ts INTEGER, level TEXT, tag TEXT, message TEXT)`
    — subir `Db` para versão 3. Escrita fora da main thread (executor de thread única).
  - Se `logger.debug == true`, espelhar em `android.util.Log`.
  - Purga por `logMaxDays` (default 3) no `TrackingEngine.init` e a cada 500 inserções.
- Instrumentar (nível entre parênteses):
  - `TrackingEngine`: `begin/end` (i), `enterMoving/enterStationary` com motivo (i),
    `onStopTimeout` (i), `requestLocationUpdates` sem permissão (w), parâmetros efetivos do
    `LocationRequest` (d), cada fix aceito/descartado e por quê (v), `onActivity` (d),
    mudança de provedor (i), conectividade (i), heartbeat (d).
  - `TrackingService`: `onStartCommand`, `onTaskRemoved`, `onDestroy` (i).
  - `HttpSync`: início/fim, status HTTP, quantidade enviada, erros (i/e).
  - `GeofenceManager`: add/remove, falha de registro (e), transições (i).
  - `BootReceiver` (i).
- **Doze / economia de bateria** (essencial para a hipótese H3): no `begin()` registrar receiver
  para `PowerManager.ACTION_POWER_SAVE_MODE_CHANGED` e `ACTION_DEVICE_IDLE_MODE_CHANGED`
  (API 23+); logar e emitir `powersavechange` com `{enabled: Boolean}` (o Dart já escuta).
  Remover no `end()`. Logar também, no `begin()`, se o app está em
  `isIgnoringBatteryOptimizations`.
- Métodos novos no plugin: `getLog` (args `{start?, end?, order?, limit?}` → `String` com uma
  linha por registro `ISO8601 LEVEL [tag] mensagem`), `destroyLog` → `Boolean`,
  `log` (args `{level, message}`, para o Dart escrever no mesmo log).

**Dart**
- Novo `lib/src/logger.dart`: `class Logger` estática com `getLog({...})`, `destroyLog()`,
  `error/warn/info/debug/notice(String msg)` → método `log`. Exportar.

**Aceite:** com `LoggerConfig(logLevel: LogLevel.verbose)`, `Logger.getLog()` mostra as
transições moving/stationary e eventos de Doze; `logLevel: off` não grava nada.

**Testes:** Dart — `LoggerConfig.toMap`, e `Logger.getLog` montando os argumentos certos
(mock do canal).

---

## Etapa 2 — `watchPosition` / `stopWatchPosition` (Android)

**Estado atual:** o Dart já expõe os dois métodos, mas o nativo responde `notImplemented`.
Há também um bug no Dart: cada `watchPosition` chama `_subscribe('watchposition', ...)` e nunca
cancela, e todos os watchers recebem as posições de todos.

**Nativo** (`TrackingEngine.kt`)
- `watchPosition(ctx, args): Int`: cria um `LocationCallback` próprio (independente do
  rastreamento principal) com `interval` (default 1000 ms), prioridade via `priorityFor()`
  existente (`desiredAccuracy`, default da config), `persist` (default `false`) e `extras`.
  Guardar em `Map<Int, LocationCallback>` com ids incrementais.
- Cada fix: `buildLocation(loc, null, moving)` existente; se `persist`, chamar `persist()`;
  emitir `watchposition` com `{id, location}`.
- `timeout` (s): se nenhum fix chegar no prazo, emitir `{id, error: 408}` (código de timeout).
- `stopWatchPosition(id): Boolean`; também parar **todos** os watches em `onDetachedFromActivity`
  do plugin (é uma API de primeiro plano) e em `stop()`.
- Plugin: casos `watchPosition` e `stopWatchPosition`; checar permissão como em
  `getCurrentPosition`.

**Dart** (`background_geolocation.dart`)
- Guardar `Map<int, Subscription>`; o parse filtra pelo `id` do envelope; erro vira
  `LocationError(code)`. `stopWatchPosition` cancela a assinatura correspondente.

**Aceite:** dois watchers simultâneos recebem só as próprias posições; `stopWatchPosition`
para as entregas; o rastreamento principal não é afetado.

**Testes:** Dart — roteamento por id entre dois watchers (injetar envelopes no `_controllers`
via mock do EventChannel ou extrair a lógica de roteamento para função testável).

---

## Etapa 3 — Headless (Android)

**Por quê:** com `stopOnTerminate: false`, o rastreamento continua com o app fechado, mas
`EventBus.emit` descarta tudo quando `sink == null`. O motorista perde `onLocation`,
`onGeofence`, `onHttp` em segundo plano.

**Dart**
- `BackgroundGeolocation.registerHeadlessTask(Future<void> Function(HeadlessEvent) task)`:
  obtém `PluginUtilities.getCallbackHandle` de um dispatcher top-level
  `@pragma('vm:entry-point') void _headlessDispatcher()` e do `task` do usuário (deve ser
  top-level/estático — validar e lançar `ArgumentError` se o handle for nulo); envia os dois
  handles pelo método `registerHeadlessTask`.
- `_headlessDispatcher`: `WidgetsFlutterBinding.ensureInitialized()`,
  `DartPluginRegistrant.ensureInitialized()`, escuta `MethodChannel('dev.openbg/headless')`,
  resolve o callback do usuário com `PluginUtilities.getCallbackFromHandle` e o chama; ao
  terminar setup, invoca `initialized` no canal.
- Novo `class HeadlessEvent { final String name; final Object? event; }` com o payload já
  convertido pelos `fromMap` existentes conforme o nome (`location`/`motionchange` → `Location`,
  `geofence` → `GeofenceEvent`, `http` → `HttpEvent`, etc.). Exportar.
- `AppConfig.enableHeadless` já existe.

**Nativo**
- Novo `HeadlessTask.kt`:
  - Guarda os dois handles em `SharedPreferences`.
  - `dispatch(ctx, name, data)`: se `Settings.bool("app","enableHeadless")` e há handles,
    sobe (uma vez) um `FlutterEngine` em segundo plano — `FlutterInjector.instance().flutterLoader()`
    `startInitialization` + `ensureInitializationComplete`, depois
    `dartExecutor.executeDartCallback(DartCallback(assets, bundlePath, FlutterCallbackInformation.lookupCallbackInformation(dispatcherHandle)))`.
  - Fila de eventos até o Dart chamar `initialized`; depois `invokeMethod("event", {handle, name, data})`.
  - Destruir o engine em `TrackingEngine.stop()`.
- `EventBus.emit`: dentro do `main.post`, se `sink == null`, chamar `HeadlessTask.dispatch`.
  Eventos headless: `location, motionchange, activitychange, providerchange, geofence,
  geofenceschange, heartbeat, http, connectivitychange, enabledchange, powersavechange,
  schedule` (este último após a Etapa 5).
- **Cuidado com várias instâncias do plugin:** o engine headless também instancia
  `OpenBackgroundGeolocationPlugin`. Hoje `onDetachedFromEngine` e `onCancel` fazem
  `EventBus.sink = null` sem checar dono — isso apagaria o sink da UI. Guardar o sink da própria
  instância e só limpar se `EventBus.sink === meuSink`. O dispatcher **não** deve escutar o
  EventChannel.
- Quando a UI volta (`onListen`), os eventos voltam para o sink normal; o engine headless pode
  continuar vivo até `stop()`.
- Logar tudo pelo Logger da Etapa 1.

**Aceite:** com `enableHeadless: true` e `stopOnTerminate: false`, fechar o app nos recentes e
andar: o callback headless recebe `location` (verificar via `Logger.getLog()` ou um
`Logger.info` dentro do callback).

**Testes:** Dart — `HeadlessEvent` converte cada nome no tipo certo.

---

## Etapa 4 — Autorização JWT / refresh token (Android)

**Dart** (`config.dart`, `events.dart`)
- `class Authorization { strategy ('JWT'), accessToken, refreshToken, refreshUrl,
  refreshPayload (Map), refreshHeaders (Map), expires (epoch s; -1 = desconhecido) }`.
  `Config.authorization` novo grupo `authorization`.
- `refreshPayload` aceita o placeholder `"{refreshToken}"` nos valores.
- Evento `onAuthorization(cb)` com `AuthorizationEvent { success, error?, response (Map) }`.

**Nativo**
- Novo `Auth.kt`:
  - `header()`: `Authorization: Bearer <accessToken>` se `strategy == JWT` e token presente.
  - `refreshIfNeeded()`: se `expires` passou (folga de 60 s), POST
    `application/x-www-form-urlencoded` em `refreshUrl` com `refreshPayload` (placeholder
    substituído) e `refreshHeaders`.
  - Parse da resposta aceitando `accessToken|access_token`, `refreshToken|refresh_token`,
    `expires|expires_in` (se `expires_in`, converter para epoch). Função pura testável.
  - Salvar com `Settings.update(mapOf("authorization" to ...))`; emitir `authorization`.
- `HttpSync.post`: chamar `refreshIfNeeded()` antes; aplicar o header; se 401, tentar refresh
  **uma vez** e reenviar; se falhar, manter a fila (comportamento atual).
- `Settings.defaults()`: grupo `authorization` vazio; `refreshPayload` e `refreshHeaders` em
  `replaceKeys`.

**Aceite:** com servidor de teste que devolve 401 para token expirado, o plugin renova o token,
reenvia e a fila esvazia; `onAuthorization` dispara com `success: true`.

**Testes:** Dart — `Authorization.toMap`, `AuthorizationEvent.fromMap`. JVM — parse da resposta
de token (3 formatos) e substituição do placeholder.

---

## Etapa 5 — Schedule (Android)

**Formato** (`AppConfig.schedule`, já existe): lista de strings `"<dias> <HH:mm>-<HH:mm> [geofence]"`.
Dias `1–7` com **1 = domingo**, aceitando faixa (`2-6`) e lista (`1,7`). Sufixo `geofence` =
modo só geofences. Janelas que cruzam a meia-noite (`22:00-06:00`) devem funcionar.

**Nativo**
- `Schedule.kt`: parser e `nextTransition(now)`/`activeWindow(now)` como **funções puras**
  (usar `java.time` não é possível no minSdk 21 sem desugaring — usar `Calendar` ou cálculo
  manual em minutos da semana).
- `ScheduleReceiver` (registrar no `AndroidManifest.xml`, `exported=false`): avalia a janela
  atual → `TrackingEngine.start` / `startGeofences` / `stop`; emite `schedule` com
  `Settings.stateMap()`; agenda o próximo alarme.
- Alarme: `AlarmManager.setExactAndAllowWhileIdle` se `scheduleUseAlarmManager` e
  (API < 31 ou `canScheduleExactAlarms()`); senão `setAndAllowWhileIdle`. **Não** declarar
  `SCHEDULE_EXACT_ALARM` no manifest do plugin (política da Play) — documentar no README que o
  app pode declarar.
- Métodos `startSchedule` (liga `schedulerEnabled`, avalia já, agenda) e `stopSchedule` (cancela
  alarme, desliga flag; **não** para o rastreamento — igual à doc pública).
- `Settings`: persistir `schedulerEnabled`; `stateMap()` passa a usar o valor real (hoje é
  `false` fixo).
- `BootReceiver`: se `schedulerEnabled`, reavaliar e reagendar.

**Aceite:** com `schedule: ['1-7 HH:mm-HH:mm']` cobrindo os próximos 2 minutos, o rastreamento
liga e desliga sozinho e `onSchedule` dispara nas duas pontas.

**Testes:** JVM — parser (faixas, listas, sufixo, entrada inválida) e `nextTransition` incluindo
virada de meia-noite e de semana.

---

## Etapa 6 — iOS (CoreLocation + CoreMotion)

**Limitação:** não há Mac neste ambiente. O código Swift **não pode ser compilado nem testado
aqui**; a etapa termina com o código escrito e uma checklist de validação em Mac. Deixar isso
explícito no commit e no README.

**Contrato:** mesmo canal, mesmos nomes de métodos e eventos, mesmo formato de payload do
Android (`buildLocation` em `TrackingEngine.kt` é a referência). O Dart não muda.

**Arquivos** em `ios/Classes/` (substituir o modelo atual de `OpenBackgroundGeolocationPlugin.swift`):
- `Plugin.swift` — registro dos canais `dev.openbg/methods` e `dev.openbg/events`, roteamento.
- `Settings.swift` — mesmos defaults e merge do `Settings.kt`, em `UserDefaults`.
- `TrackingEngine.swift` — `CLLocationManager` com `allowsBackgroundLocationUpdates`,
  `pausesLocationUpdatesAutomatically`, `distanceFilter`, `desiredAccuracy`; máquina
  moving/stationary igual à do Android (`stopTimeout`; região `CLCircularRegion` de
  `stationaryRadius` para acordar); `startMonitoringSignificantLocationChanges` quando o app é
  encerrado e `stopOnTerminate == false`.
- `Motion.swift` — `CMMotionActivityManager` → mesmos nomes de atividade.
- `Db.swift` — SQLite via `sqlite3` C API, mesmas tabelas (locations, geofences, logs).
- `HttpSync.swift` — `URLSession`, mesmo corpo (`rootProperty`, `params`, batch) e JWT da Etapa 4.
- `GeofenceManager.swift` — monitoramento de regiões (limite iOS: **20**; manter as mais próximas).
- `Logger.swift` — mesma API da Etapa 1.
- Fora de escopo no iOS: headless (no iOS o app é relançado em background e o Dart roda
  normalmente), schedule com alarmes (usar só o que for viável com timers + significant changes;
  documentar limitação).
- `ios/open_background_geolocation.podspec`: frameworks `CoreLocation`, `CoreMotion`, `libsqlite3`.
- README: chaves obrigatórias no `Info.plist` do app (`NSLocationAlwaysAndWhenInUseUsageDescription`,
  `NSLocationWhenInUseUsageDescription`, `NSMotionUsageDescription`, `UIBackgroundModes: location`).

**Aceite (em Mac):** exemplo roda no simulador com rota simulada (Freeway Drive), entrega
`onLocation` e `onMotionChange`, grava no banco e faz sync.

---

## Verificação (toda etapa)

1. `flutter analyze` sem novos avisos.
2. `flutter test` — todos verdes (hoje 6 testes).
3. JVM, quando houver testes Kotlin: `cd example/android && ./gradlew :open_background_geolocation:testDebugUnitTest`.
4. Compilação Android: `cd example && flutter build apk --debug`.
5. Atualizar `example/lib/main.dart` com um botão/tela mínima que exercite a funcionalidade nova
   (ex.: "Ver log" na Etapa 1, "Watch" na Etapa 2), para teste manual no aparelho.
6. Commit único por etapa, mensagem `Add <feature> on Android` (ou iOS).

## Como pedir ao Sonnet

Uma etapa por sessão, por exemplo:

> Leia `docs/PLANO_IMPLEMENTACAO.md`, seções
> "Regras para todas as etapas" e "Etapa N". Implemente só a Etapa N, rode a verificação e faça
> o commit.
