import 'enums.dart';

Map<String, dynamic> _compact(Map<String, dynamic> m) {
  m.removeWhere((_, v) => v == null);
  return m;
}

/// Kalman/accuracy filtering applied before a location is recorded.
class LocationFilter {
  final LocationFilterPolicy? policy;
  final int? useKalman;
  final double? kalmanProfile;
  final double? trackingAccuracyThreshold;
  final double? maxImpliedSpeed;
  final double? odometerAccuracyThreshold;

  const LocationFilter({
    this.policy,
    this.useKalman,
    this.kalmanProfile,
    this.trackingAccuracyThreshold,
    this.maxImpliedSpeed,
    this.odometerAccuracyThreshold,
  });

  Map<String, dynamic> toMap() => _compact({
        'policy': policy?.name,
        'useKalman': useKalman,
        'kalmanProfile': kalmanProfile,
        'trackingAccuracyThreshold': trackingAccuracyThreshold,
        'maxImpliedSpeed': maxImpliedSpeed,
        'odometerAccuracyThreshold': odometerAccuracyThreshold,
      });
}

class GeoConfig {
  final ActivityType? activityType;
  final bool? allowIdenticalLocations;
  final int? deferTime;
  final DesiredAccuracy? desiredAccuracy;
  final bool? disableElasticity;
  final bool? disableLocationAuthorizationAlert;
  final double? distanceFilter;
  final double? elasticityMultiplier;
  final bool? enableTimestampMeta;
  final int? fastestLocationUpdateInterval;
  final LocationFilter? filter;
  final bool? geofenceInitialTriggerEntry;
  final bool? geofenceModeHighAccuracy;
  final int? geofenceProximityRadius;

  /// Always | WhenInUse | Any
  final String? locationAuthorizationRequest;
  final int? locationTimeout;
  final int? locationUpdateInterval;
  final bool? pausesLocationUpdatesAutomatically;
  final bool? showsBackgroundLocationIndicator;
  /// Android: seconds without a fix while moving before a probe and, if it finds nothing, a
  /// `locationsilence` event and one re-issue of the location request. Default 120; 0 disables.
  final int? silenceTimeout;
  final int? stationaryRadius;
  final int? stopAfterElapsedMinutes;
  final int? stopTimeout;
  final bool? useSignificantChangesOnly;

  const GeoConfig({
    this.activityType,
    this.allowIdenticalLocations,
    this.deferTime,
    this.desiredAccuracy,
    this.disableElasticity,
    this.disableLocationAuthorizationAlert,
    this.distanceFilter,
    this.elasticityMultiplier,
    this.enableTimestampMeta,
    this.fastestLocationUpdateInterval,
    this.filter,
    this.geofenceInitialTriggerEntry,
    this.geofenceModeHighAccuracy,
    this.geofenceProximityRadius,
    this.locationAuthorizationRequest,
    this.locationTimeout,
    this.locationUpdateInterval,
    this.pausesLocationUpdatesAutomatically,
    this.showsBackgroundLocationIndicator,
    this.silenceTimeout,
    this.stationaryRadius,
    this.stopAfterElapsedMinutes,
    this.stopTimeout,
    this.useSignificantChangesOnly,
  });

  Map<String, dynamic> toMap() => _compact({
        'activityType': activityType?.name,
        'allowIdenticalLocations': allowIdenticalLocations,
        'deferTime': deferTime,
        'desiredAccuracy': desiredAccuracy?.name,
        'disableElasticity': disableElasticity,
        'disableLocationAuthorizationAlert': disableLocationAuthorizationAlert,
        'distanceFilter': distanceFilter,
        'elasticityMultiplier': elasticityMultiplier,
        'enableTimestampMeta': enableTimestampMeta,
        'fastestLocationUpdateInterval': fastestLocationUpdateInterval,
        'filter': filter?.toMap(),
        'geofenceInitialTriggerEntry': geofenceInitialTriggerEntry,
        'geofenceModeHighAccuracy': geofenceModeHighAccuracy,
        'geofenceProximityRadius': geofenceProximityRadius,
        'locationAuthorizationRequest': locationAuthorizationRequest,
        'locationTimeout': locationTimeout,
        'locationUpdateInterval': locationUpdateInterval,
        'pausesLocationUpdatesAutomatically': pausesLocationUpdatesAutomatically,
        'showsBackgroundLocationIndicator': showsBackgroundLocationIndicator,
        'silenceTimeout': silenceTimeout,
        'stationaryRadius': stationaryRadius,
        'stopAfterElapsedMinutes': stopAfterElapsedMinutes,
        'stopTimeout': stopTimeout,
        'useSignificantChangesOnly': useSignificantChangesOnly,
      });

  factory GeoConfig.fromMap(Map<dynamic, dynamic> m) => GeoConfig(
        desiredAccuracy: m['desiredAccuracy'] == null
            ? null
            : enumFromName(DesiredAccuracy.values, m['desiredAccuracy'], DesiredAccuracy.high),
        distanceFilter: (m['distanceFilter'] as num?)?.toDouble(),
        stationaryRadius: (m['stationaryRadius'] as num?)?.toInt(),
        stopTimeout: (m['stopTimeout'] as num?)?.toInt(),
        silenceTimeout: (m['silenceTimeout'] as num?)?.toInt(),
        locationUpdateInterval: (m['locationUpdateInterval'] as num?)?.toInt(),
        useSignificantChangesOnly: m['useSignificantChangesOnly'] as bool?,
        disableElasticity: m['disableElasticity'] as bool?,
        elasticityMultiplier: (m['elasticityMultiplier'] as num?)?.toDouble(),
        locationTimeout: (m['locationTimeout'] as num?)?.toInt(),
        stopAfterElapsedMinutes: (m['stopAfterElapsedMinutes'] as num?)?.toInt(),
        geofenceProximityRadius: (m['geofenceProximityRadius'] as num?)?.toInt(),
        geofenceInitialTriggerEntry: m['geofenceInitialTriggerEntry'] as bool?,
        geofenceModeHighAccuracy: m['geofenceModeHighAccuracy'] as bool?,
      );
}

class ActivityConfig {
  final int? activityRecognitionInterval;
  final bool? disableMotionActivityUpdates;
  final bool? disableStopDetection;

  /// Disables only the distance-based stop detection (Android): with it on (the default), GPS is
  /// turned off when no fix moves farther than `stationaryRadius` for `stopTimeout` minutes, even
  /// if activity recognition never reports STILL. The app can still drive the state with
  /// `changePace`. `disableStopDetection` turns off both detectors.
  final bool? disableDistanceStopDetection;
  final int? minimumActivityRecognitionConfidence;
  final int? motionTriggerDelay;
  final int? stopDetectionDelay;
  final bool? stopOnStationary;
  final List<String>? triggerActivities;

  const ActivityConfig({
    this.activityRecognitionInterval,
    this.disableMotionActivityUpdates,
    this.disableStopDetection,
    this.disableDistanceStopDetection,
    this.minimumActivityRecognitionConfidence,
    this.motionTriggerDelay,
    this.stopDetectionDelay,
    this.stopOnStationary,
    this.triggerActivities,
  });

  Map<String, dynamic> toMap() => _compact({
        'activityRecognitionInterval': activityRecognitionInterval,
        'disableMotionActivityUpdates': disableMotionActivityUpdates,
        'disableStopDetection': disableStopDetection,
        'disableDistanceStopDetection': disableDistanceStopDetection,
        'minimumActivityRecognitionConfidence': minimumActivityRecognitionConfidence,
        'motionTriggerDelay': motionTriggerDelay,
        'stopDetectionDelay': stopDetectionDelay,
        'stopOnStationary': stopOnStationary,
        'triggerActivities': triggerActivities,
      });
}

class HttpConfig {
  final bool? autoSync;
  final int? autoSyncThreshold;
  final bool? batchSync;
  final bool? disableAutoSyncOnCellular;
  final Map<String, dynamic>? headers;
  final int? maxBatchSize;

  /// POST | PUT | PATCH
  final String? method;
  final Map<String, dynamic>? params;
  final String? rootProperty;
  final int? timeout;
  final String? url;

  const HttpConfig({
    this.autoSync,
    this.autoSyncThreshold,
    this.batchSync,
    this.disableAutoSyncOnCellular,
    this.headers,
    this.maxBatchSize,
    this.method,
    this.params,
    this.rootProperty,
    this.timeout,
    this.url,
  });

  Map<String, dynamic> toMap() => _compact({
        'autoSync': autoSync,
        'autoSyncThreshold': autoSyncThreshold,
        'batchSync': batchSync,
        'disableAutoSyncOnCellular': disableAutoSyncOnCellular,
        'headers': headers,
        'maxBatchSize': maxBatchSize,
        'method': method,
        'params': params,
        'rootProperty': rootProperty,
        'timeout': timeout,
        'url': url,
      });
}

class PersistenceConfig {
  final bool? disableProviderChangeRecord;
  final Map<String, dynamic>? extras;

  /// ASC | DESC
  final String? locationsOrderDirection;
  final int? maxDaysToPersist;
  final int? maxRecordsToPersist;
  final PersistMode? persistMode;

  /// iso | epoch
  final String? timestampFormat;

  const PersistenceConfig({
    this.disableProviderChangeRecord,
    this.extras,
    this.locationsOrderDirection,
    this.maxDaysToPersist,
    this.maxRecordsToPersist,
    this.persistMode,
    this.timestampFormat,
  });

  Map<String, dynamic> toMap() => _compact({
        'disableProviderChangeRecord': disableProviderChangeRecord,
        'extras': extras,
        'locationsOrderDirection': locationsOrderDirection,
        'maxDaysToPersist': maxDaysToPersist,
        'maxRecordsToPersist': maxRecordsToPersist,
        'persistMode': persistMode?.name,
        'timestampFormat': timestampFormat,
      });
}

/// Android foreground-service notification.
class NotificationConfig {
  final String? title;
  final String? text;
  final String? channelName;
  final String? smallIcon;
  final String? color;
  final int? priority;
  final bool? sticky;

  const NotificationConfig({
    this.title,
    this.text,
    this.channelName,
    this.smallIcon,
    this.color,
    this.priority,
    this.sticky,
  });

  Map<String, dynamic> toMap() => _compact({
        'title': title,
        'text': text,
        'channelName': channelName,
        'smallIcon': smallIcon,
        'color': color,
        'priority': priority,
        'sticky': sticky,
      });
}

class AppConfig {
  final bool? enableHeadless;
  final int? heartbeatInterval;
  final NotificationConfig? notification;
  final bool? preventSuspend;
  final List<String>? schedule;
  final bool? scheduleUseAlarmManager;
  final bool? startOnBoot;
  final bool? stopOnTerminate;

  const AppConfig({
    this.enableHeadless,
    this.heartbeatInterval,
    this.notification,
    this.preventSuspend,
    this.schedule,
    this.scheduleUseAlarmManager,
    this.startOnBoot,
    this.stopOnTerminate,
  });

  Map<String, dynamic> toMap() => _compact({
        'enableHeadless': enableHeadless,
        'heartbeatInterval': heartbeatInterval,
        'notification': notification?.toMap(),
        'preventSuspend': preventSuspend,
        'schedule': schedule,
        'scheduleUseAlarmManager': scheduleUseAlarmManager,
        'startOnBoot': startOnBoot,
        'stopOnTerminate': stopOnTerminate,
      });
}

/// Bearer-token authorization for HTTP sync (Android).
///
/// The plugin sends `Authorization: Bearer <accessToken>` with every upload. When [expires] has
/// passed, or the server answers 401, it POSTs [refreshPayload] (form-encoded) to [refreshUrl] to
/// obtain a new pair, stores it, and retries once. The string `{refreshToken}` inside
/// [refreshPayload] values is replaced with the current refresh token; when no payload is given
/// the body is `refresh_token={refreshToken}`.
///
/// Note that `Config.reset` defaults to true, so `ready()` discards tokens the plugin refreshed
/// earlier in favor of the ones given here. Pass `reset: false` to keep the stored pair.
class Authorization {
  /// Only `JWT` is supported.
  final String? strategy;
  final String? accessToken;
  final String? refreshToken;
  final String? refreshUrl;
  final Map<String, dynamic>? refreshPayload;
  final Map<String, dynamic>? refreshHeaders;

  /// Expiry of [accessToken] in epoch seconds; omit or use -1 when unknown.
  final int? expires;

  const Authorization({
    this.strategy = 'JWT',
    this.accessToken,
    this.refreshToken,
    this.refreshUrl,
    this.refreshPayload,
    this.refreshHeaders,
    this.expires,
  });

  Map<String, dynamic> toMap() => _compact({
        'strategy': strategy,
        'accessToken': accessToken,
        'refreshToken': refreshToken,
        'refreshUrl': refreshUrl,
        'refreshPayload': refreshPayload,
        'refreshHeaders': refreshHeaders,
        'expires': expires,
      });
}

class LoggerConfig {
  final bool? debug;
  final LogLevel? logLevel;
  final int? logMaxDays;

  const LoggerConfig({this.debug, this.logLevel, this.logMaxDays});

  Map<String, dynamic> toMap() => _compact({
        'debug': debug,
        'logLevel': logLevel?.name,
        'logMaxDays': logMaxDays,
      });
}

/// Root configuration passed to `ready()` / `setConfig()`.
class Config {
  final GeoConfig? geolocation;
  final ActivityConfig? activity;
  final HttpConfig? http;
  final PersistenceConfig? persistence;
  final AppConfig? app;
  final LoggerConfig? logger;
  final Authorization? authorization;

  /// When true (default), `ready()` applies this config on top of defaults,
  /// discarding previously persisted configuration.
  final bool? reset;

  const Config({
    this.geolocation,
    this.activity,
    this.http,
    this.persistence,
    this.app,
    this.logger,
    this.authorization,
    this.reset,
  });

  Map<String, dynamic> toMap() => _compact({
        'geolocation': geolocation?.toMap(),
        'activity': activity?.toMap(),
        'http': http?.toMap(),
        'persistence': persistence?.toMap(),
        'app': app?.toMap(),
        'logger': logger?.toMap(),
        'authorization': authorization?.toMap(),
        'reset': reset,
      });
}
