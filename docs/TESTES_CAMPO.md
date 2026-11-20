# Testes de campo — open_background_geolocation (Android)

Registro dos testes feitos no app do motorista e do plano para os próximos. Ao trazer novos
resultados, preencha as tabelas das seções 4 e 5 e anexe os registros do banco (seção 3).

Plugin testado: Android, commits até `d209a48` (rastreamento, geofencing e HTTP sync).
Aparelho: Samsung. Requisito de negócio: turnos de **8 horas** com rastreamento confiável.

## 1. Resultado do primeiro ciclo de testes

### Provado

- Compila e integra no app do motorista; os testes automáticos passam.
- Com a tela ligada, entrega posições com precisão de ±8 a ±32 m.
- Notificação e processo sobreviveram: app vivo por mais de 1 hora com a tela apagada.
- O banco do plugin guarda as posições com a hora do GPS.

### Não provado

| Item | Situação |
|---|---|
| Rastreamento com a tela apagada | **Inconclusivo.** Lacunas de 23 e 31 minutos sem posição. Causa desconhecida (seção 2). |
| Movimento real (carro, caminhada) | Não testado |
| App fechado deslizando nos recentes | Não testado |
| Turno de 8 horas | Maior teste: ~1 hora |
| Backend real | Só testado contra API simulada |

A lacuna é o ponto central, porque é o motivo da troca de biblioteca.

## 2. Por que pode haver lacunas (hipóteses)

Duas causas prováveis. A primeira é comportamento previsto do motor, não falha.

### H1 — O motor entrou em modo parado (por projeto)

Fluxo: o reconhecimento de atividade reporta `still` por `stopTimeout` (padrão 5 min) → o motor
desliga o GPS, registra um `motionchange` com `is_moving = false` e cria uma geofence de ~150 m
em volta da última posição. Só volta a rastrear quando o aparelho sai dessa geofence ou o
reconhecimento de atividade detecta movimento.

Aparelho parado em uma mesa gera exatamente esse padrão.

**Como confirmar:** no banco, procure um registro com `event == "motionchange"` e
`is_moving == false` logo antes do início da lacuna.

### H2 — Apenas o `distanceFilter` de 10 m

Se não houve `motionchange` parado, o motor continuou "em movimento", mas o Fused Location só
entrega posição quando o aparelho se desloca pelo menos 10 m. Parado, nada chega, e isso é normal.

**Como confirmar:** repetir o teste com `distanceFilter: 0` e `locationUpdateInterval: 10000`
(uma posição a cada ~10 s mesmo parado). Se as lacunas sumirem, era o filtro.

### H3 — O Android pausou o rastreamento (a hipótese que importa)

Só considere esta se H1 e H2 foram descartadas, isto é, com `distanceFilter: 0`, sem
`motionchange` parado e ainda assim sem posições. Candidatos: Doze, economia de bateria da
Samsung ("app dormindo"), restrição de localização em segundo plano.

### Variante: permissão de reconhecimento de atividade negada

Sem a permissão `ACTIVITY_RECOGNITION`, o motor nunca detecta `still` e nunca entra em modo
parado. Nesse caso só H2 e H3 se aplicam.

## 3. O que coletar do banco em cada teste

Use `BackgroundGeolocation.getLocations()` e exporte, para cada registro:

- `timestamp` (hora do GPS) e `recorded_at` (hora em que o plugin recebeu);
- `event` (`motionchange`, `heartbeat`, `geofence` ou ausente);
- `is_moving`, `coords.accuracy`, `activity.type` e `activity.confidence`;
- `battery.level`, `battery.is_charging`, `odometer`.

Importante: com `http.url` configurado e `autoSync` ligado, **registros enviados com sucesso são
apagados do banco**. Para o teste de lacunas, deixe `autoSync: false` (ou sem `url`) para que o
histórico completo fique disponível, e compare depois com o que o backend recebeu.

Também anote o estado do app no começo e no fim do teste: `BackgroundGeolocation.state`
(`enabled`, `isMoving`, `trackingMode`) e `providerState` (GPS ligado, autorização).

## 4. Configuração recomendada para os próximos testes

### Teste de diagnóstico das lacunas (aparelho parado, tela apagada)

```dart
Config(
  geolocation: GeoConfig(
    distanceFilter: 0,
    locationUpdateInterval: 10000,
    disableElasticity: true,
  ),
  activity: ActivityConfig(disableStopDetection: true), // elimina H1
  app: AppConfig(stopOnTerminate: false, heartbeatInterval: 60),
  persistence: PersistenceConfig(maxDaysToPersist: 3),
  http: HttpConfig(autoSync: false),
)
```

Se ainda houver lacunas com essa configuração, é H3 (Android pausando).

### Teste em movimento (carro, caminhada)

```dart
Config(
  geolocation: GeoConfig(
    distanceFilter: 10,
    disableElasticity: true, // sem isso o filtro cresce até 10x em velocidade
  ),
  app: AppConfig(stopOnTerminate: false),
)
```

### Teste do app fechado nos recentes

`stopOnTerminate` vale `true` por padrão e o serviço **para o rastreamento** ao fechar o app.
Para esse teste use `AppConfig(stopOnTerminate: false)`.

Observações: o upload HTTP e o banco são nativos e continuam sem a interface, mas os eventos
para o Dart são descartados quando não há tela (headless ainda não implementado).

### Ajustes do aparelho (Samsung)

- Bateria do app: "Sem restrições".
- Remover o app de "Apps dormindo" e "Apps em suspensão profunda".
- Permissão de localização: "Permitir o tempo todo"; localização precisa ligada.
- Permissão de atividade física concedida.

## 5. Plano de testes e modelo de resultado

Marque e preencha ao trazer os resultados.

| # | Teste | Duração | Configuração | Resultado esperado | Resultado obtido |
|---|---|---|---|---|---|
| 1 | Parado, tela apagada, diagnóstico (seção 4) | 1 h | diagnóstico | posição a cada ~10 s sem lacunas > 1 min | |
| 2 | Parado, tela apagada, padrão atual | 1 h | `distanceFilter: 10` | lacunas esperadas (H1/H2); confirmar `motionchange` | |
| 3 | Caminhada | 20 min | movimento | posições a cada ~10 m, `activity` = walking | |
| 4 | Carro | 30 min | movimento | trilha contínua, sem saltos grandes | |
| 5 | App fechado nos recentes | 30 min | `stopOnTerminate: false` | rastreamento segue e registros chegam ao banco | |
| 6 | Turno completo | 8 h | definitiva do app | sem lacunas inexplicadas; bateria aceitável | |
| 7 | Backend real | 1 h | `http.url` real | 2xx apaga a fila; falha mantém e reenvia | |
| 8 | Perda e retorno de rede | 30 min | `autoSync: true` | fila acumula offline e esvazia ao voltar a rede | |

Para cada teste, anote também: modelo e versão do Android, nível de bateria inicial e final,
se o aparelho estava carregando, e se houve interação do usuário com o app.

## 6. Pendências conhecidas no plugin

- ~~Logger persistente~~ implementado (`Logger.getLog()`); ver `docs/PLANO_IMPLEMENTACAO.md`.
- Eventos para o Dart são descartados sem tela (headless não implementado).
- `watchPosition`, schedule e autorização por token (JWT) não implementados.
- Elasticidade usa heurística própria (até 10x o filtro) e pode ser grosseira para motorista;
  prefira `disableElasticity: true`.
- Android depende do Google Play Services.
- Nenhum comportamento de segundo plano foi validado em turnos longos.
