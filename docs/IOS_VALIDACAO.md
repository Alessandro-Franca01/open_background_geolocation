# Validação do iOS (a fazer em um Mac)

O código Swift em `ios/Classes/` foi escrito **sem compilador**: não havia Mac nem toolchain Swift
no ambiente de desenvolvimento. Nada foi compilado, analisado ou executado. Trate-o como um
primeiro rascunho completo: a primeira compilação provavelmente revelará erros de tipo ou de API.

O contrato com o Dart é o mesmo do Android (canais `dev.openbg/methods` e `dev.openbg/events`,
mesmos nomes de método e de evento, mesmo formato de payload). A referência é o código Android.

## 1. Compilar

1. `cd example && flutter pub get && cd ios && pod install`.
2. Abrir `example/ios/Runner.xcworkspace` no Xcode (iOS 12+), `Product > Build`.
3. Corrigir os erros do compilador. Pontos que mais merecem atenção:
   - inferência de tipos em literais `[String: Any]` grandes (`Settings.defaults`, `buildLocation`);
   - assinaturas de delegates (`CLLocationManagerDelegate`, `FlutterStreamHandler`);
   - closures que capturam variáveis locais (`HttpClient.perform`, `startBackgroundTask`);
   - `#available` para APIs de iOS 14 e 15 (`accuracyAuthorization`, `sourceInformation`).

## 2. Configuração do app

O app precisa declarar no `Info.plist` (o exemplo já declara):

- `NSLocationAlwaysAndWhenInUseUsageDescription` e `NSLocationWhenInUseUsageDescription`;
- `NSMotionUsageDescription` (detecção de movimento);
- `UIBackgroundModes` contendo `location`. Sem isso o plugin não liga
  `allowsBackgroundLocationUpdates` e registra um aviso no log;
- `NSLocationTemporaryUsageDescriptionDictionary`, se usar `requestTemporaryFullAccuracy`.

## 3. Testes no simulador

Use `Features > Location > Freeway Drive` (ou City Run) com o app de exemplo.

| # | Teste | Esperado |
|---|---|---|
| 1 | Iniciar (`start`) e aceitar as permissões | `onLocation` e `onMotionChange` chegam; `getLocations` retorna registros |
| 2 | `Logger.getLog()` | linhas de `begin()`, `-> MOVING`, fixes aceitos |
| 3 | `getCurrentPosition` | uma posição, ou erro de timeout |
| 4 | `watchPosition` (2 watchers) / `stopWatchPosition` | cada um recebe só os próprios eventos |
| 5 | `addGeofence` + rota que cruza o raio | `onGeofence` ENTER e EXIT |
| 6 | `http.url` apontando para um servidor local | 2xx apaga a fila; falha mantém e reenvia |
| 7 | Servidor que responde 401 com token expirado | refresh, reenvio e `onAuthorization` |
| 8 | `schedule` de +1 a +3 minutos | liga e desliga; `onSchedule` nas duas pontas |

## 4. Testes em aparelho (o simulador não cobre)

| # | Teste | Esperado |
|---|---|---|
| 9 | App em segundo plano, tela bloqueada, andando | posições continuam chegando |
| 10 | Parado por mais que `stopTimeout` | `motionchange` com `is_moving: false`, região estacionária criada |
| 11 | Sair da região estacionária | volta a rastrear (`-> MOVING` no log) |
| 12 | App encerrado no seletor de apps com `stopOnTerminate: false` | o iOS relança o app em uma mudança significativa de localização e o rastreamento retoma |
| 13 | Modo de baixo consumo | `onPowerSaveChange` dispara |
| 14 | Turno completo de 8 h | sem lacunas inexplicadas; bateria aceitável |

## 5. Limitações conhecidas do iOS

- **Sem headless separado:** o iOS relança o app com o isolate Dart rodando, então
  `registerHeadlessTask` é aceito mas não faz nada.
- **Schedule com `Timer`:** só dispara enquanto o app está em execução. O iOS não acorda um app
  suspenso por alarme. A agenda é reavaliada quando o app fica ativo ou é relançado.
- **Geofences:** o limite do sistema é 20 regiões; o plugin monitora no máximo 19 (uma é reservada
  para a detecção de parada). Dwell é emulado com timer e só dispara com o app em execução.
- **Atividade (`still`):** o Core Motion não entrega eventos com o app suspenso. Em modo parado o
  despertar vem da saída da região estacionária.
- **Eventos sem escuta Dart** são descartados; se o app foi relançado em segundo plano antes de o
  Dart assinar, os primeiros eventos podem se perder.
