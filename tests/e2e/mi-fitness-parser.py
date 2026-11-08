"""Synthetic protocol regression. Run with the bridge virtualenv."""
import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'tools'))
from mi_fitness_sleep import sleep_summary, sleep_details
from datetime import datetime, timezone

class SleepSummaryTests(unittest.TestCase):
    def test_actual_sleep_does_not_subtract_awake_twice(self):
        summary = sleep_summary({'sleep_deep_duration': 90, 'sleep_light_duration': 300, 'sleep_rem_duration': 60, 'sleep_awake_duration': 30, 'duration': 450})
        self.assertEqual(summary['asleep'], 450)
        self.assertEqual(summary['stages']['deep'], 90)
        self.assertEqual(summary['awake'], 30)

    def test_trace_only_sleep_is_not_light_or_zero_deep_sleep(self):
        summary = sleep_summary({'sleep_deep_duration': 0, 'sleep_light_duration': 0, 'sleep_trace_duration': 450, 'sleep_awake_duration': 30, 'duration': 450})
        self.assertEqual(summary['stages'], {})
        self.assertEqual(summary['asleep'], 450)

    def test_missing_rem_stays_missing(self):
        summary = sleep_summary({'sleep_deep_duration': 90, 'sleep_light_duration': 300, 'sleep_awake_duration': 0, 'duration': 390})
        self.assertNotIn('rem', summary['stages'])

    def test_unknown_total_is_never_zero(self):
        for value in [None, '60', True, -1, 10000, 0.5]:
            with self.assertRaises(ValueError):
                sleep_summary({'duration': value, 'sleep_awake_duration': 0})

class SleepDetailsTests(unittest.TestCase):
    def test_segments_wake_count_and_unknown_snore_unit(self):
        stamp = lambda value: datetime.fromtimestamp(value, timezone.utc)
        start, end = stamp(1000), stamp(2000)
        payload = {'awake_count': 2, 'is_uncomplete': False, 'total_snore_time': 100,
                   'items': [{'start_time': 1000, 'end_time': 1500, 'state': 6}, {'start_time': 1500, 'end_time': 2000, 'state': 5}]}
        details = sleep_details(payload, start, end, stamp)
        self.assertEqual(details['wake_count'], 2)
        self.assertEqual([item['stage'] for item in details['sleep_segments']], ['unclassified', 'awake'])
        self.assertIsNone(details['raw_extensions']['total_snore_time']['unit'])
        payload['items'][1]['start_time'] = 1400
        self.assertEqual(sleep_details(payload, start, end, stamp)['sleep_segments'], [])

if __name__ == '__main__':
    unittest.main()
