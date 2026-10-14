# Regras clean-room

Este projeto reimplementa o comportamento de um SDK de geolocalização em background a partir
**somente** de:

- documentação pública oficial (docs.transistorsoft.com);
- comportamento observável do plugin em debug (eventos, formato dos payloads);
- documentação das APIs de plataforma (Android Fused Location, Activity Recognition,
  Geofencing API, Core Location, Core Motion).

Proibido:

- descompilar, desmontar ou inspecionar os binários nativos fechados (AAR / XCFramework);
- copiar código nativo do original, ou remover/contornar checagem de licença;
- usar nome, logotipo ou marca "Transistor Software".

A camada Dart do original é Apache-2.0; se algum trecho for reutilizado, manter o aviso de
copyright e marcar a alteração. Por padrão, escrevemos tudo do zero.

Nome do pacote: `open_background_geolocation`. Licença: MIT.
