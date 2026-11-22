# Plano — reduzir consumo de bateria em background (Android)

> Status: implementado (Android), testes JVM e Dart verdes, **não validado em aparelho**.
> Guia de uso: `docs/BATERIA.md`. Origem: relatório de campo `TESTES-GPS-SEGURANCA-MUNICIPAL.md`
> (20/11/2026) e dreno de bateria observado em uso longo em background.

## Contexto

Em uso longo em background (turnos de 8 h, viatura parada com motor ligado), o celular drena bateria.
Lendo `TrackingEngine.kt`, `Settings.kt` e `HttpSync.kt`, os gastos vêm de quatro fontes:

1. **GPS nunca desliga sem Activity Recognition.** A única saída de MOVING → STATIONARY é
   `onActivity("still")` + `stopTimeout` (`TrackingEngine.kt:577-583`). Se `ACTIVITY_RECOGNITION`
   for negada, `disableMotionActivityUpdates` estiver ligado, ou o sensor não reportar `still`
   (viatura parada com motor ligado vibra → `in_vehicle`/`unknown`), o GPS fica ligado para sempre.
2. **GPS a 1 Hz em alta precisão.** Padrão `locationUpdateInterval = 1000` + `PRIORITY_HIGH_ACCURACY`.
   O `distanceFilter` só filtra a *entrega*; o chip GPS continua amostrando a cada 1 s.
3. **Um POST HTTP por posição.** Padrão `autoSync = true`, `autoSyncThreshold = 0`, `batchSync = false`:
   cada fix acorda o rádio de dados (que fica ~10-20 s em alta potência depois de cada envio).
4. **Sem visibilidade.** Não há como medir quanto tempo o GPS ficou ligado num turno.

Resultado esperado: GPS desligado sempre que o aparelho estiver parado (com ou sem Activity
Recognition), rádio acordado em lotes, e métricas para comparar antes/depois em campo.

Os padrões da API (`Settings.defaults()`) **não mudam** — compatibilidade com a API documentada em
`docs/API_SPEC.md`. Os itens 2 e 3 viram configuração recomendada no app + documentação.

## Mudanças

### Divisão de responsabilidade (decidida com o usuário)

- **App** decide o que é de negócio: turno, viatura assumida/entregue, ponto fixo →
  `start()` / `stop()` / `changePace(false|true)` (já existem).
- **Plugin** mantém uma rede de segurança física: sem deslocamento por `stopTimeout` → GPS off.
  Ligada por padrão; nova flag `activity.disableDistanceStopDetection` (default `false`, segue o
  padrão `disable*` existente) para o app desligar. `disableStopDetection` continua desligando tudo.

### 1. Detecção de parada por distância (rede de segurança do plugin)

Novo `android/src/main/kotlin/.../StopLogic.kt` — objeto puro, no padrão de `ScheduleLogic.kt` /
`AuthLogic.kt`, testável na JVM sem Android:

- Mantém uma **âncora** (lat, lon, tempo). Cada fix aceito que fica a mais de `stationaryRadius`
  da âncora move a âncora para ele.
- `shouldStop(now, stopTimeoutMin)` = âncora existe e `now - âncora.tempo >= stopTimeout`.
- Distância calculada por haversine dentro do objeto (sem `android.location.Location`, para testar na JVM).

Integração em `TrackingEngine.kt`:
- `enterMoving()`: zera a âncora e agenda um `Runnable` de checagem a cada 60 s no `main` Handler
  (mesmo padrão de `stopTimer`/`heartbeat`). `enterStationary()`/`end()` cancelam.
- `onLocation()` (após aceitar o fix): `StopLogic.onFix(...)`.
- Checagem: se `!disableStopDetection && !disableDistanceStopDetection` e `shouldStop` → mesma ação de `onStopTimeout()`
  (respeita `stopOnStationary`). Log `"no displacement > ${radius}m for ${min} min; going STATIONARY"`.
- Funciona em paralelo à detecção por Activity Recognition; a primeira que disparar vence.
  Sem fixes chegando (parado + `distanceFilter`), a âncora não se move → para corretamente.

**Evitar ping-pong:** com a viatura parada e o motor ligado, o AR continua dizendo `in_vehicle`
e `onActivity` (`TrackingEngine.kt:588-590`) religaria o GPS na hora. Regra: se o STATIONARY foi
causado pela detecção por distância, gatilhos de AR ficam suprimidos até (a) a saída do geofence
estacionário (`onStationaryExit`) ou (b) o AR reportar `still` (aí volta ao comportamento normal).
Custo: a partida passa a ser detectada pelo geofence de `stationaryRadius` (mín. 100 m), com
atraso típico de 1-3 min no Android (aceito; o app pode antecipar com `changePace(true)`).
`changePace(true)` do app sempre vence e limpa a supressão.

Flag nova: `Settings.defaults()` (`activity`), `lib/src/config.dart` (`ActivityConfig`, ao lado de
`disableStopDetection`, `toMap`/`fromMap`) e o teste Dart de config correspondente.

### 2. Reduzir reemissões do LocationRequest por elasticidade

`adjustElasticity()` (`TrackingEngine.kt:310`) refaz `requestLocationUpdates()` toda vez que a
faixa de 20 km/h muda; no trânsito urbano (15↔25 km/h) isso reinicia o pedido várias vezes por
minuto. Adicionar histerese simples: só reemitir se o fator novo diferir ≥ 2 do atual, ou se o
fator se mantiver por 3 fixes seguidos. Lógica pura em `StopLogic.kt` (ou `ElasticityLogic`) para teste.

### 3. Métricas de consumo no `getState` e no log

Em `TrackingEngine`, contadores em memória (resetados em `begin()`):
`gpsOnMillis` (acumulado em MOVING), `stationaryMillis`, `fixCount`, `syncCount`, `lastFixAt`.
Expor em `Settings.stateMap()` como `stats: {...}` e logar um resumo em `end()` e a cada transição
MOVING/STATIONARY. Sem mudar a API Dart além de um campo opcional novo em `State`
(`lib/src/` — verificar onde `State` é desserializado).

### 4. Configuração recomendada e documentação

Nova seção "Bateria em turnos longos" em `docs/API_SPEC.md` (ou `docs/BATERIA.md`), com a config
sugerida para o app da viatura e o porquê de cada valor:

```dart
Config(
  geolocation: GeoConfig(desiredAccuracy: 'medium' /* ou high */, distanceFilter: 25,
    locationUpdateInterval: 5000, fastestLocationUpdateInterval: 5000,
    stopTimeout: 3, stationaryRadius: 100, deferTime: 30000),
  http: HttpConfig(autoSync: true, batchSync: true, autoSyncThreshold: 6, maxBatchSize: 50),
)
```
Guia de integração do app (mesma seção): chamar `start()` ao assumir a viatura e `stop()` ao
entregar; `changePace(false)` ao marcar ponto fixo e `changePace(true)` ao sair; quando usar
`disableDistanceStopDetection`. Mais: pedir `ACTIVITY_RECOGNITION`, e orientar o usuário a tirar o app da otimização de bateria
da Samsung (o log de `begin()` já registra `ignoringBatteryOptimizations`).

Atualizar a tabela de status em `docs/PLANO_IMPLEMENTACAO.md` e o roteiro de
`docs/TESTES_CAMPO.md` com o teste de bateria (item 6 do relatório de campo: "Consumo de bateria").

## Arquivos

- `android/src/main/kotlin/dev/openbg/open_background_geolocation/StopLogic.kt` (novo)
- `android/src/main/kotlin/dev/openbg/open_background_geolocation/TrackingEngine.kt`
- `android/src/main/kotlin/dev/openbg/open_background_geolocation/Settings.kt` (`stateMap`)
- `android/src/test/kotlin/dev/openbg/open_background_geolocation/StopLogicTest.kt` (novo)
- `lib/src/config.dart` — flag `disableDistanceStopDetection`
- `lib/src/` — campo `stats` opcional no `State`, se aplicável
- `docs/` — seção de bateria, status, roteiro de campo

## Verificação

1. `cd android && ./gradlew testDebugUnitTest` (via `example/android`) — `StopLogicTest`:
   parado sem fixes → para após `stopTimeout`; fixes dentro do raio → para; um fix fora do raio →
   reinicia a contagem; histerese de elasticidade não reemite em 15↔25 km/h.
2. `flutter test` verde; `cd example && flutter build apk --debug` compila.
3. Campo (Samsung do relatório): turno de 2 h com `ACTIVITY_RECOGNITION` **negada** e viatura
   parada ≥ 10 min — o log deve mostrar `-> STATIONARY ... GPS updates off` e `stats.gpsOnMillis`
   bem menor que o tempo total. Comparar % de bateria/hora com e sem a config recomendada
   (Configurações → Bateria → uso por app).
4. Commit único: `Reduce battery use in long background sessions on Android`.
