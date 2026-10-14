#!/usr/bin/env python3
"""Regression checks for release-gate false success paths."""
import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('security_scan', Path(__file__).with_name('security-scan.py'))
scan = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scan)


class GateTests(unittest.TestCase):
    def report(self, severity=None):
        return {'SchemaVersion': 2, 'ArtifactName': 'synthetic', 'Results': [
            {'Target': 'synthetic', 'Vulnerabilities': [] if severity is None else [
                {'Severity': severity, 'VulnerabilityID': 'TEST', 'PkgName': 'synthetic'}]}]}

    def test_critical_and_unfixed_high_block(self):
        for severity in ['HIGH', 'CRITICAL', 'UNKNOWN']:
            with self.subTest(severity=severity):
                self.assertEqual(1, scan.gate([scan.assess(self.report(severity))], False))

    def test_clean_and_lower_severity_full_scan_pass(self):
        for severity in [None, 'LOW', 'MEDIUM']:
            self.assertEqual(0, scan.gate([scan.assess(self.report(severity))], False))

    def test_partial_scan_never_passes(self):
        self.assertEqual(2, scan.gate([scan.assess(self.report())], True))

    def test_missing_or_failed_scan_never_passes(self):
        self.assertEqual(2, scan.gate([], False))
        self.assertEqual(2, scan.gate([{'error': 'timeout'}], False))

    def test_empty_or_invalid_reports_rejected(self):
        for report in [{}, [], {'SchemaVersion': 2, 'ArtifactName': 'x', 'Results': []},
                       {'SchemaVersion': 1, 'ArtifactName': 'x', 'Results': [{}]}]:
            with self.subTest(report=report):
                with self.assertRaises(ValueError):
                    scan.assess(report)


if __name__ == '__main__':
    unittest.main()
