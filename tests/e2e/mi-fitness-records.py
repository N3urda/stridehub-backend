import sys
import unittest
from pathlib import Path
from datetime import datetime, timezone
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'tools'))
from mi_fitness_records import normalize, sanitize

class Adapter:
    def _parse_value(self, item): return item['value']
    def _record_datetime(self, item): return datetime.fromtimestamp(item['time'], timezone.utc)

class RecordsTest(unittest.TestCase):
    def test_identity_retains_device_and_accepts_corrections(self):
        item = dict(sid='phone', key='steps', time=1700000000, value={'steps': 0, 'distance': None, 'access_token': 'secret'})
        original = normalize(Adapter(), 'steps', item)
        corrected = normalize(Adapter(), 'steps', {**item, 'value': {'steps': 20}})
        self.assertEqual(original['sourceId'], corrected['sourceId'])
        self.assertNotEqual(original['sourceId'], normalize(Adapter(), 'steps', {**item, 'sid': 'band'})['sourceId'])
        self.assertEqual(original['values']['steps'], 0)
        self.assertIsNone(original['values']['distance'])
        self.assertNotIn('access_token', original['values'])
        self.assertNotIn('phone', str(original))
    def test_workout_fallback_and_metadata(self):
        result = normalize(Adapter(), 'workouts', {'category': 'running', 'value': {'start_time': 1700000000, 'duration': 3600}})
        self.assertEqual(result['values']['_source']['category'], 'running')
        self.assertEqual(result['values']['duration'], 3600)
    def test_missing_timestamp_and_sensitive_nested_fields(self):
        with self.assertRaises(ValueError): normalize(Adapter(), 'heart_rate', {'value': {'hr': 70}})
        self.assertEqual({'values': [None, {'hr': 70}]}, sanitize({'values': [float('nan'), {'hr': 70, 'user_id': 'private'}], 'url': 'private'}))

if __name__ == '__main__': unittest.main()
