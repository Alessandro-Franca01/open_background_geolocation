# Bateria em turnos longos (Android)

Guia para apps que rastreiam por horas em segundo plano (ex.: viatura em turno de 8 h).
Contexto e decisões em `docs/PLANO_BATERIA.md`.

## De onde vem o gasto

| Fonte | Por quê | O que fazer |
|---|---|---|
| GPS ligado com o aparelho parado | sem `still` do reconhecimento de atividade, o GPS não desliga | já tratado no plugin (detecção por distância, abaixo) e pelo app (`changePace`) |
| GPS a 1 Hz em alta precisão | padrão `locationUpdateInterval: 1000`; o `distanceFilter` só filtra a entrega, o chip continua amostrando | subir o intervalo (5-10 s) |
| Um POST por posição | padrão `autoSyncThreshold: 0`, `batchSync: false`; cada envio deixa o rádio de dados ~10-20 s em alta potência | enviar em lote |
| Otimização de bateria do fabricante | Samsung/One UI pode suspender o app | orientar o usuário a liberar o app |

## Quem decide quando o GPS desliga

- **O app** decide o que é regra de negócio:
  - `BackgroundGeolocation.start()` ao assumir a viatura, `stop()` ao entregar.
  - `changePace(false)` ao marcar ponto fixo (GPS desliga na hora) e `changePace(true)` ao sair
    (GPS liga na hora, sem esperar o geofence).
- **O plugin** tem uma rede de segurança física, ligada por padrão: se nenhuma posição se afastar
  mais de `stationaryRadius` (mín. 100 m) da âncora por `stopTimeout` minutos, vai para
  STATIONARY e desliga o GPS. Isso vale mesmo sem a permissão de atividade física, e também com a
  viatura parada e o motor ligado (o sensor continua dizendo `in_vehicle`).
  - Depois de parar por distância, o `in_vehicle` não religa o GPS. A partida é detectada pela
    saída do geofence estacionário (atraso típico de 1-3 min) ou por `changePace(true)`.
  - Se o sensor reportar `still`, volta ao comportamento normal (qualquer atividade de movimento religa).
  - Para desativar só essa detecção: `ActivityConfig(disableDistanceStopDetection: true)`.
    `disableStopDetection: true` desativa as duas detecções (sensor e distância).

## Configuração sugerida para a viatura

```dart
await BackgroundGeolocation.ready(Config(
  geolocation: GeoConfig(
    desiredAccuracy: DesiredAccuracy.high,   // medium economiza mais, com ±30-100 m
    distanceFilter: 25,
    locationUpdateInterval: 5000,            // GPS a cada 5 s em vez de 1 s
    fastestLocationUpdateInterval: 5000,
    stopTimeout: 3,                          // minutos parado até desligar o GPS
    stationaryRadius: 100,
    deferTime: 30000,                        // o sistema pode agrupar entregas em até 30 s
  ),
  http: HttpConfig(
    url: 'https://.../posicoes',
    autoSync: true,
    batchSync: true,
    autoSyncThreshold: 6,                    // ~1 envio a cada 6 posições (~30 s em movimento)
    maxBatchSize: 50,
  ),
));
```

- `deferTime` atrasa a entrega ao Dart/servidor. Se a central precisa ver a viatura em tempo real,
  use `0` e compense com `autoSyncThreshold`.
- Parado (STATIONARY), não há posições novas; o envio só acontece quando houver fila. Se o
  servidor precisar de um "estou vivo", use `heartbeatInterval` (ex.: 300 s), que reenvia a última posição.
- Peça a permissão de atividade física (`requestPermission` já pede): com ela, a parada é detectada
  pelo sensor, além da distância.
- Peça ao usuário para tirar o app da otimização de bateria (Configurações → Apps → app →
  Bateria → "Sem restrições"). O log de `begin()` registra `ignoringBatteryOptimizations`.

## Diagnóstico e falhas

`BackgroundGeolocation.getDiagnostics()` (Android) mostra numa chamada por que o rastreamento
está ou não funcionando: `serviceState` (`stopped`/`starting`/`running`/`failed`), `lastError`
(persistido entre reinícios), permissões, notificações, otimização de bateria, `locationAvailable`
e `stats`. Os erros também chegam por `onTrackingError` e pelo headless (`trackingerror`).

| `lastError.code` | Significado | O que fazer |
|---|---|---|
| `service_permission` | Android 14+: o serviço não pôde iniciar em segundo plano (boot, schedule, restart) com permissão só "Ao usar o app"; ou sem permissão de localização | abrir o app retoma sozinho (`ready()`); para iniciar sem o app aberto, pedir "Permitir o tempo todo" |
| `service_start_not_allowed` | Android 12+: o sistema recusou iniciar o serviço em primeiro plano a partir do segundo plano | abrir o app; verificar restrições de bateria do fabricante |
| `location_silence` | em movimento, nenhuma posição em `silenceTimeout` s (padrão 120) e a sondagem não achou posição; o plugin reabre o pedido uma vez | ver `locationAvailable`, GPS do aparelho e economia de bateria |
| `location_unavailable` | o provedor do Google informou localização indisponível (GPS desligado, sem sinal, restrição) | ver `provider.gps` e o ambiente (túnel, garagem) |

O app nunca cai por falha do serviço: a falha é registrada e `enabled` continua `true`, então o
rastreamento volta na próxima vez que o app abrir. `onLocationSilence` avisa o fluxo mudo;
`geolocation.silenceTimeout: 0` desliga essa checagem.

## Medir

`getState()` traz `stats` (Android) com os contadores da sessão atual:

```dart
final st = (await BackgroundGeolocation.getState()).stats;
// st.session, st.gpsOn, st.stationary, st.fixCount, st.httpRequestCount, st.stationaryTransitions
```

A razão `gpsOn / session` é o indicador principal: numa patrulha com paradas, deve ficar bem
abaixo de 100%. O log grava o mesmo resumo (`stats (stationary)` e `stats (end)`).
Compare com o uso de bateria por app em Configurações → Bateria.
