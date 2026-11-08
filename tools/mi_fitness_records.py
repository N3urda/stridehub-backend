"""Read-only raw health streams through the bridge's existing authentication.
Stable source IDs retain corrections and overlapping devices separately.
"""
import asyncio
import hashlib
import json
import math
from datetime import datetime
from fastapi import Request, HTTPException

KINDS = ('steps', 'calories', 'heart_rate', 'resting_heart_rate', 'spo2', 'stress', 'weight', 'abnormal_heart_beat', 'workouts')


def sanitize(value, depth=0):
    if depth > 12:
        raise ValueError('Nested source payload exceeds limit')
    if value is None or isinstance(value, (bool, int, str)):
        if isinstance(value, str) and len(value) > 16384:
            raise ValueError('Source string exceeds limit')
        return value
    if isinstance(value, float):
        return value if math.isfinite(value) else None
    if isinstance(value, list):
        if len(value) > 20000:
            raise ValueError('Source list exceeds limit')
        return [sanitize(item, depth + 1) for item in value]
    if isinstance(value, dict):
        blocked = ('token', 'password', 'secret', 'cookie', 'userid', 'user_id', 'account', 'authorization', 'url', 'signature', 'ticket', 'security')
        return {str(key): sanitize(item, depth + 1) for key, item in value.items() if not any(term in str(key).lower() for term in blocked)}
    raise ValueError('Unsupported source value')


def normalize(adapter, kind, item):
    values = sanitize(adapter._parse_value(item))
    if not isinstance(values, dict):
        raise ValueError('Expected source object')
    timestamp = item.get('time') or (values.get('start_time') if kind == 'workouts' else None)
    if not isinstance(timestamp, (int, float)) or timestamp <= 0:
        raise ValueError('Missing source timestamp')
    stamp = adapter._record_datetime({**item, 'time': timestamp})
    metadata = {key: item[key] for key in ('category', 'key', 'zone_offset', 'zone_name') if key in item}
    if metadata:
        values['_source'] = sanitize(metadata)
    device = hashlib.sha256(str(item.get('sid', 'unknown')).encode()).hexdigest()
    identity = json.dumps([kind, item.get('sid'), item.get('key'), timestamp], separators=(',', ':'))
    return {'sourceId': hashlib.sha256(identity.encode()).hexdigest(), 'sourceDeviceKey': device,
            'recordedAt': stamp.isoformat(), 'values': values}


def install_records(bridge):
    @bridge.app.get('/api/stridehub/records')
    async def records(request: Request, kind: str, start_date: str, end_date: str):
        if kind not in KINDS:
            raise HTTPException(422, 'Unsupported data type')
        try:
            start, end = datetime.strptime(start_date, '%Y-%m-%d'), datetime.strptime(end_date, '%Y-%m-%d')
            if not 0 <= (end-start).days <= 93:
                raise ValueError()
        except ValueError:
            raise HTTPException(422, 'Invalid date range') from None
        context = bridge._resolve_context(request)
        adapter = context.adapter
        if adapter is None:
            raise HTTPException(503, 'Not configured')
        async def fetch():
            if not adapter.is_connected() and not await adapter.connect():
                raise HTTPException(401, 'Cloud authorization unavailable')
            if kind == 'workouts':
                rows = await adapter._fetch_sport_records_by_time(start_date, end_date)
            else:
                rows = await adapter._fetch_key(kind, start_date, end_date)
            if len(rows) > 30000:
                raise HTTPException(413, 'Reduce the requested range')
            result = [normalize(adapter, kind, item) for item in rows]
            if len(json.dumps(result)) > 7 * 1024 * 1024:
                raise HTTPException(413, 'Reduce the requested range')
            return {'status': 'ok', 'kind': kind, 'count': len(result), 'data': result}
        try:
            return await asyncio.wait_for(fetch(), timeout=40)
        except HTTPException:
            raise
        except Exception:
            raise HTTPException(502, 'Source unavailable or incompatible; retry with a shorter range') from None
