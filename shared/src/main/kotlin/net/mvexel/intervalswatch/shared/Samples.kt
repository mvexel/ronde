package net.mvexel.intervalswatch.shared

/** Realistic sample workouts (intervals.icu resolve=true shape) for previews and screenshot tests. */
object Samples {
    private const val JSON = """
    [{
      "id": 101, "type": "Ride", "category": "WORKOUT", "name": "4x8m VO2 Max",
      "start_date_local": "2026-11-02T07:00:00",
      "workout_doc": {
        "duration": 3600, "ftp": 280, "target": "POWER",
        "steps": [
          {"duration": 900, "warmup": true, "ramp": true, "text": "Warm up",
           "power": {"start": 50, "end": 75, "units": "%ftp"}, "_power": {"start": 140, "end": 210}},
          {"reps": 4, "steps": [
            {"duration": 480, "text": "VO2", "power": {"value": 110, "units": "%ftp"},
             "_power": {"value": 308, "start": 300, "end": 316}},
            {"duration": 240, "intensity": "rest", "power": {"value": 50, "units": "%ftp"},
             "_power": {"value": 140, "start": 137, "end": 144}}
          ]},
          {"duration": 600, "cooldown": true, "power": {"value": 50, "units": "%ftp"},
           "_power": {"value": 140, "start": 137, "end": 144}}
        ]
      }
    },
    {
      "id": 102, "type": "Run", "category": "WORKOUT", "name": "Tempo 3x10'",
      "start_date_local": "2026-11-03T06:30:00",
      "workout_doc": {
        "duration": 3300, "threshold_pace": 4.0, "lthr": 168, "pace_units": "MINS_KM", "target": "PACE",
        "steps": [
          {"distance": 2000, "warmup": true, "pace": {"value": 75, "units": "%pace"},
           "_pace": {"value": 3.0, "start": 2.93, "end": 3.08}},
          {"reps": 3, "steps": [
            {"duration": 600, "text": "Tempo", "pace": {"value": 92, "units": "%pace"},
             "_pace": {"value": 3.68, "start": 3.59, "end": 3.77}},
            {"duration": 120, "intensity": "recovery", "hr": {"value": 75, "units": "%lthr"},
             "_hr": {"value": 126, "start": 124, "end": 128}}
          ]},
          {"until_lap_press": true, "cooldown": true, "text": "Easy until lap"}
        ]
      }
    },
    {
      "id": 104, "type": "Walk", "category": "WORKOUT", "name": "Brisk walk 4x5'",
      "start_date_local": "2026-11-04T12:00:00",
      "workout_doc": {
        "duration": 2400, "threshold_pace": 2.5, "pace_units": "MINS_KM", "target": "PACE",
        "steps": [
          {"duration": 300, "warmup": true, "pace": {"value": 60, "units": "%pace"},
           "_pace": {"value": 1.5, "start": 1.45, "end": 1.55}},
          {"reps": 4, "steps": [
            {"duration": 300, "text": "Brisk", "pace": {"value": 90, "units": "%pace"},
             "_pace": {"value": 2.25, "start": 2.2, "end": 2.3}},
            {"duration": 120, "intensity": "recovery", "pace": {"value": 60, "units": "%pace"},
             "_pace": {"value": 1.5, "start": 1.45, "end": 1.55}}
          ]},
          {"duration": 300, "cooldown": true, "pace": {"value": 60, "units": "%pace"},
           "_pace": {"value": 1.5, "start": 1.45, "end": 1.55}}
        ]
      }
    },
    {
      "id": 103, "type": "VirtualRide", "category": "WORKOUT", "name": "Endurance 90",
      "start_date_local": "2026-11-05T17:30:00",
      "workout_doc": {"duration": 5400, "ftp": 280, "target": "HR",
        "steps": [{"duration": 5400, "hr": {"start": 70, "end": 80, "units": "%lthr"},
                   "_hr": {"start": 118, "end": 134}}]}
    }]
    """

    val workouts: List<Workout> by lazy { IntervalsParser.parseEvents(JSON) }
    val ride: Workout get() = workouts.first { it.id == 101L }
    val run: Workout get() = workouts.first { it.id == 102L }
    val walk: Workout get() = workouts.first { it.id == 104L }
}
