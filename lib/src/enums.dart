/// Location-provider accuracy tier. Only [high] and [navigation] use GPS.
enum DesiredAccuracy { navigation, high, medium, low, veryLow, lowest }

/// iOS activity hint for stop detection.
enum ActivityType { other, automotiveNavigation, fitness, otherNavigation, airborne }

/// Which event types are written to the local database.
enum PersistMode { all, location, geofence, none }

enum LogLevel { off, error, warning, info, debug, verbose }

enum LocationFilterPolicy { adjust, passThrough, conservative }

enum TrackingMode { geofencesOnly, location }

/// Parses a name that may be camelCase or SCREAMING_SNAKE, returning [fallback] if unknown.
T enumFromName<T extends Enum>(List<T> values, Object? name, T fallback) {
  if (name == null) return fallback;
  final key = name.toString().replaceAll('_', '').toLowerCase();
  for (final v in values) {
    if (v.name.toLowerCase() == key) return v;
  }
  return fallback;
}
