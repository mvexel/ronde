"""Decode a FIT file with fitdecode (independent of our encoder) and print a summary. Usage: python -I verify_fit.py file.fit"""
import sys, collections
import fitdecode

counts = collections.Counter()
recs, laps, events, session = [], [], [], None
with fitdecode.FitReader(sys.argv[1], check_crc=fitdecode.CrcCheck.RAISE) as fit:
    for f in fit:
        if f.frame_type != fitdecode.FIT_FRAME_DATA:
            continue
        counts[f.name] += 1
        d = {x.name: x.value for x in f.fields}
        if f.name == "record": recs.append(d)
        elif f.name == "lap": laps.append(d)
        elif f.name == "event": events.append(d)
        elif f.name == "session": session = d
print("messages:", dict(counts))
print("first record:", recs[0]); print("last record:", recs[-1])
print("events:", [(e["timestamp"].isoformat(), e["event"], e["event_type"]) for e in events])
for l in laps: print("lap:", {k: l[k] for k in ("start_time","timestamp","total_elapsed_time","total_timer_time","total_distance","intensity")})
print("session:", session)
ts = [r["timestamp"] for r in recs]
assert ts == sorted(ts), "records not chronological"
print("record span s:", (ts[-1]-ts[0]).total_seconds(), "gap max s:", max((b-a).total_seconds() for a,b in zip(ts,ts[1:])))
