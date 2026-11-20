import 'package:flutter_test/flutter_test.dart';
import 'package:open_background_geolocation/open_background_geolocation.dart';
import 'package:open_background_geolocation/src/headless.dart' show parseHeadlessEvent;

void main() {
  test('Authorization.toMap omits nulls and defaults the strategy to JWT', () {
    final map = const Authorization(accessToken: 'a', refreshToken: 'r', expires: 123).toMap();
    expect(map, {'strategy': 'JWT', 'accessToken': 'a', 'refreshToken': 'r', 'expires': 123});
  });

  test('Authorization keeps refresh payload and headers', () {
    final map = const Authorization(
      refreshUrl: 'https://x/refresh',
      refreshPayload: {'refresh_token': '{refreshToken}'},
      refreshHeaders: {'X-Key': 'k'},
    ).toMap();
    expect(map['refreshUrl'], 'https://x/refresh');
    expect(map['refreshPayload'], {'refresh_token': '{refreshToken}'});
    expect(map['refreshHeaders'], {'X-Key': 'k'});
  });

  test('Config nests the authorization group', () {
    final map = const Config(authorization: Authorization(accessToken: 'a')).toMap();
    expect(map['authorization'], {'strategy': 'JWT', 'accessToken': 'a'});
    expect(const Config().toMap().containsKey('authorization'), isFalse);
  });

  test('AuthorizationEvent.fromMap reads success, error and response', () {
    final ok = AuthorizationEvent.fromMap({
      'success': true,
      'response': {'accessToken': 'new'},
    });
    expect(ok.success, isTrue);
    expect(ok.error, isNull);
    expect(ok.response['accessToken'], 'new');

    final bad = AuthorizationEvent.fromMap({'success': false, 'error': 'Refresh failed (HTTP 400)'});
    expect(bad.success, isFalse);
    expect(bad.error, 'Refresh failed (HTTP 400)');
    expect(bad.response, isEmpty);
  });

  test('headless events carry authorization results typed', () {
    final e = parseHeadlessEvent('authorization', {'success': true});
    expect(e.event, isA<AuthorizationEvent>());
  });
}
