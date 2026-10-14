#!/usr/bin/env python3
"""Create two synthetic PDFs for manual browser verification, without credentials."""
import hashlib
import runpy
from pathlib import Path

root = Path(__file__).resolve().parents[1]
create_pdf = runpy.run_path(str(root / 'scripts/presentation-demo.py'))['synthetic_pdf']
original = create_pdf()
modified = original.replace(b'DEMONSTRATION ONLY', b'MODIFIED DOCUMENT!')
assert len(modified) == len(original) and modified != original
output = root / '.runtime/submission/demo-files'
output.mkdir(parents=True, exist_ok=True)
for name, data in [('original.pdf', original), ('modified.pdf', modified)]:
    target = output / name
    if target.exists() and target.read_bytes() != data:
        raise SystemExit('Refusing to overwrite a different demo file: ' + name)
    target.write_bytes(data)
    print(name + ': SHA-256 ' + hashlib.sha256(data).hexdigest())
print('Synthetic files: ' + str(output))
