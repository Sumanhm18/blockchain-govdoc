#!/usr/bin/env python3
"""Check that resuming never starts containers until all ownership checks pass."""
import importlib.util
import json
import unittest
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('resume', Path(__file__).with_name('resume-demo.py'))
resume = importlib.util.module_from_spec(spec)
spec.loader.exec_module(resume)


class ResumeTests(unittest.TestCase):
    def inspect(self, command, **kwargs):
        name = command[-1]
        project = 'govdoc-staging' if name.startswith('govdoc-staging-') else 'govdoc-dev-app' if name.startswith('govdoc-dev-app-') else 'govdoc-dev-fabric'
        if self.wrong_owner and name == 'govdoc-staging-api-1':
            project = 'unrelated-project'
        return json.dumps([{'Config': {'Labels': {'com.docker.compose.project': project}}, 'State': {'Running': self.running}}]).encode()

    def setUp(self):
        self.wrong_owner = False
        self.running = False

    def test_late_owner_mismatch_starts_nothing(self):
        self.wrong_owner = True
        with patch.object(resume.subprocess, 'check_output', side_effect=self.inspect), patch.object(resume.subprocess, 'run') as run:
            with self.assertRaises(RuntimeError):
                resume.main(True)
            run.assert_not_called()

    def test_citizen_mode_starts_only_eleven_validated_services(self):
        with patch.object(resume.subprocess, 'check_output', side_effect=self.inspect), patch.object(resume.subprocess, 'run') as run, patch.object(resume, 'wait_identity') as identity:
            resume.main(True)
            start = run.call_args_list[0].args[0]
            self.assertEqual(start[:2], ['docker', 'start'])
            self.assertEqual(len(start[2:]), 11)
            self.assertEqual(len(set(start[2:])), 11)
            identity.assert_called_once()
            self.assertEqual(run.call_args_list[-1].args[0], ['python3', 'scripts/wait-staging.py'])

    def test_running_department_mode_does_not_restart_or_use_identity(self):
        self.running = True
        with patch.object(resume.subprocess, 'check_output', side_effect=self.inspect), patch.object(resume.subprocess, 'run') as run, patch.object(resume, 'wait_identity') as identity:
            resume.main()
            self.assertEqual(run.call_count, 1)
            self.assertEqual(run.call_args.args[0][1], 'scripts/wait-ready.py')
            identity.assert_not_called()


if __name__ == '__main__':
    unittest.main()
