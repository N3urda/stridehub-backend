"""Conservative sleep adapter for the pinned Mi Fitness bridge.

Use explicit Xiaomi duration fields, rather than the upstream adapter's incorrect
stage-code mapping and subtraction of wake minutes from an already-asleep total.
Reference: Aaron-gx/mihealth-api @ 05edd1308025795e124f8958b20f8408c41b1c82,
API.md and static/index.html. Unknown schemas fail explicitly.
"""
from mi_fitness_mcp.adapters.mi_fitness_cloud import MiFitnessCloudAdapter
from mi_fitness_mcp.models import SleepSession, SleepStage


def minute_value(value):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or value < 0 or value > 2160 or int(value) != value:
        raise ValueError('Sleep duration schema is unsupported; no inferred values.')
    return int(value)


def sleep_summary(payload):
    # duration is already asleep time. Trace-only records use state 6 and have
    # placeholder zero deep/light fields; these must not become measured zero.
    asleep = minute_value(payload.get('duration'))
    awake = minute_value(payload.get('sleep_awake_duration'))
    trace = minute_value(payload.get('sleep_trace_duration', 0))
    stages = {}
    if trace == 0:
        for stage in ('deep', 'light', 'rem'):
            key = f'sleep_{stage}_duration'
            if key in payload:
                stages[stage] = minute_value(payload[key])
    if sum(stages.values()) > asleep + 1:
        raise ValueError('Sleep stage sum exceeds actual sleep duration.')
    return {'asleep': asleep, 'awake': awake, 'stages': stages}


class StrideHubMiFitnessAdapter(MiFitnessCloudAdapter):
    async def iter_sleep_sessions(self, start_date=None, end_date=None):
        if not self.is_connected() or not start_date or not end_date:
            return
        records = await self._fetch_key('sleep', start_date, end_date)
        for item in records:
            payload = self._parse_value(item)
            summary = sleep_summary(payload)
            stages = summary['stages']
            start = payload.get('bedtime') or payload.get('device_bedtime') or payload.get('bed_timestamp')
            end = payload.get('wake_up_time') or payload.get('device_wake_up_time') or payload.get('out_bed_timestamp')
            if not start or not end:
                raise ValueError('Sleep timestamps missing.')
            offset = int(item.get('zone_offset', 0) or 0)
            start_at = self._timestamp_to_datetime(start, offset)
            end_at = self._timestamp_to_datetime(end, offset)
            elapsed = (end_at - start_at).total_seconds() / 60
            asleep = summary['asleep']
            if not 0 < asleep <= 2160 or asleep + summary['awake'] > elapsed + 1:
                raise ValueError('Sleep summary exceeds its timestamp interval.')
            sleep_id = f"{item.get('sid', self.user_id)}_{item.get('time', int(end))}"
            if getattr(self, 'details_path', None):
                import json
                import sqlite3
                details = sleep_details(payload, start_at, end_at, lambda value: self._timestamp_to_datetime(value, offset))
                with sqlite3.connect(self.details_path) as db:
                    db.execute('INSERT OR REPLACE INTO stridehub_sleep_details(user_id,sleep_id,payload) VALUES(?,?,?)', (self.user_id, sleep_id, json.dumps(details)))
            yield SleepSession(id=f'mi_fitness_sleep_{sleep_id}', provider='mi_fitness', source_type='cloud_session',
                source_record_id=str(item.get('time', '')) or None, user_id=self.user_id,
                timezone=item.get('zone_name') or 'UTC', collected_at=self._record_datetime(item),
                sleep_id=sleep_id, start_at=start_at, end_at=end_at,
                duration_minutes=asleep + summary['awake'], time_asleep_minutes=asleep,
                time_awake_minutes=summary['awake'], sleep_score=self._optional_int(payload.get('score') or payload.get('sleep_score')),
                is_nap=bool(payload.get('is_nap', False)), stages=[SleepStage(stage=key, minutes=value) for key, value in stages.items()])


def sleep_details(payload, start_at, end_at, timestamp):
    """Additional observed fields; unknown units are retained only as raw extensions."""
    wake = payload.get('awake_count')
    wake = wake if isinstance(wake, int) and not isinstance(wake, bool) and 0 <= wake <= 500 else None
    incomplete = payload.get('is_uncomplete')
    segments = []
    names = {1: 'awake', 2: 'deep', 3: 'light', 4: 'rem', 5: 'awake', 6: 'unclassified'}
    for item in payload.get('items', []):
        if not isinstance(item, dict):
            continue
        try:
            start, end = timestamp(item['start_time']), timestamp(item['end_time'])
        except (KeyError, TypeError, ValueError, OverflowError):
            continue
        if start < start_at or end > end_at or end <= start:
            continue
        state = item.get('state')
        segments.append({'startedAt': start.isoformat(), 'endedAt': end.isoformat(), 'stage': names.get(state, 'unknown'), 'sourceState': state if isinstance(state, int) else None})
    segments.sort(key=lambda item: item['startedAt'])
    # Do not manufacture a nonoverlapping timeline from conflicting source segments.
    if any(a['endedAt'] > b['startedAt'] for a, b in zip(segments, segments[1:])):
        segments = []
    raw = {}
    snore = payload.get('total_snore_time')
    if isinstance(snore, (int, float)) and not isinstance(snore, bool) and snore >= 0:
        raw['total_snore_time'] = {'value': snore, 'unit': None}
    return {'wake_count': wake, 'is_incomplete': incomplete if isinstance(incomplete, bool) else None,
            'sleep_segments': segments, 'raw_extensions': raw}


def install_details(bridge, path):
    """Extend the authenticated bridge sleep query without exposing account secrets."""
    import json
    import sqlite3
    from mi_fitness_mcp.services.query_service import QueryService
    StrideHubMiFitnessAdapter.details_path = path
    with sqlite3.connect(path) as db:
        db.execute('CREATE TABLE IF NOT EXISTS stridehub_sleep_details (user_id TEXT, sleep_id TEXT, payload TEXT NOT NULL, PRIMARY KEY(user_id,sleep_id))')
    original = QueryService.get_sleep_sessions

    def enriched(self, *args, **kwargs):
        rows = original(self, *args, **kwargs)
        with sqlite3.connect(path) as db:
            for row in rows:
                found = db.execute('SELECT payload FROM stridehub_sleep_details WHERE user_id=? AND sleep_id=?', (self.user_id, row['sleep_id'])).fetchone()
                if found:
                    row.update(json.loads(found[0]))
        return rows
    QueryService.get_sleep_sessions = enriched
