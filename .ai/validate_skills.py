"""Validate local skill bundle integrity; standard library only, no network or writes."""
from pathlib import Path
import hashlib
import json
import re
import sys

root = Path(__file__).resolve().parents[1]
lock = json.loads((root / '.ai/skills.lock.json').read_text(encoding='utf-8'))
errors = []
tracked = lock['files']
for rel, expected in tracked.items():
    path = root / rel
    if not path.is_file():
        errors.append(f'missing: {rel}')
    elif hashlib.sha256(path.read_bytes()).hexdigest() != expected:
        errors.append(f'hash mismatch: {rel}')
seen = set()
actual = list((root / '.agents/skills').glob('*/SKILL.md'))
for path in actual:
    text = path.read_text(encoding='utf-8')
    match = re.match(r'^---\nname: ([a-z0-9]+(?:-[a-z0-9]+)*)\ndescription: (.+)\n---\n', text)
    if not match:
        errors.append(f'invalid metadata: {path.relative_to(root)}')
        continue
    name = match[1]
    if name != path.parent.name or name in seen or len(name) > 64:
        errors.append(f'invalid/duplicate name: {name}')
    seen.add(name)
    if '[TODO' in text or len(text.splitlines()) > 500:
        errors.append(f'unfinished/oversized skill: {name}')
    if not (path.parent / 'agents/openai.yaml').is_file():
        errors.append(f'missing interface metadata: {name}')
    if path.relative_to(root).as_posix() not in tracked:
        errors.append(f'unlocked skill: {name}')
if seen != set(lock['installed_skills']):
    errors.append('installed skill list differs from manifest')
index = (root / '.agents/skills/INDEX.md').read_text(encoding='utf-8')
for target in re.findall(r'\]\(([^)]+)\)', index):
    if '://' not in target and not (root / '.agents/skills' / target).resolve().is_file():
        errors.append(f'broken index link: {target}')
if errors:
    print('\n'.join(errors))
    sys.exit(1)
print(f'OK: {len(seen)} skills, {len(tracked)} verified files; version {lock["version"]}')
