import 'package:flutter/services.dart';

const MethodChannel _methods = MethodChannel('dev.openbg/methods');

/// Persistent diagnostic log kept by the native side. Which lines are recorded is controlled by
/// `LoggerConfig.logLevel`; `LoggerConfig.logMaxDays` controls retention.
class Logger {
  Logger._();

  /// Returns the log as text, one line per record: `ISO8601 LEVEL [tag] message`.
  ///
  /// [start] and [end] are optional time bounds (UTC milliseconds since epoch). [order] is
  /// `ASC` (default) or `DESC`.
  static Future<String> getLog({int? limit, DateTime? start, DateTime? end, String? order}) async {
    final args = <String, dynamic>{
      'limit': limit,
      'start': start?.toUtc().millisecondsSinceEpoch,
      'end': end?.toUtc().millisecondsSinceEpoch,
      'order': order,
    }..removeWhere((_, v) => v == null);
    return await _methods.invokeMethod<String>('getLog', args) ?? '';
  }

  /// Deletes every log record.
  static Future<bool> destroyLog() async =>
      await _methods.invokeMethod<bool>('destroyLog') ?? false;

  static Future<void> error(String message) => _write('error', message);
  static Future<void> warn(String message) => _write('warning', message);
  static Future<void> info(String message) => _write('info', message);
  static Future<void> notice(String message) => _write('info', message);
  static Future<void> debug(String message) => _write('debug', message);

  static Future<void> _write(String level, String message) =>
      _methods.invokeMethod<bool>('log', {'level': level, 'message': message});
}
