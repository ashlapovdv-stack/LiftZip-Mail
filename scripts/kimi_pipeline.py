"""Trusted CI glue for Kimi. The coding container never receives a GitHub token."""
import argparse
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import urllib.request
import urllib.parse


def git(*args, cwd='work'):
    return subprocess.check_output(['git', '-c', 'core.hooksPath=/dev/null', *args], cwd=cwd)


def allowed(path):
    p = PurePosixPath(path)
    return (not p.is_absolute() and '..' not in p.parts and not any(x.startswith('.') for x in p.parts)
            and (path.startswith(('app/', 'docs/')) or path in {'README.md', 'BUILD_CHECKS.md', 'BACKLOG.md'}))


def output(name, value):
    with open(os.environ['GITHUB_OUTPUT'], 'a') as f:
        f.write(f'{name}={value}\n')


def api(path):
    req = urllib.request.Request('https://api.github.com/repos/' + os.environ['GITHUB_REPOSITORY'] + path,
        headers={'Authorization': 'Bearer ' + os.environ['GH_TOKEN'], 'Accept': 'application/vnd.github+json'})
    with urllib.request.urlopen(req, timeout=30) as response:
        return json.load(response)


def prepare():
    event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())
    smoke = os.environ['GITHUB_EVENT_NAME'] == 'push' or event.get('inputs', {}).get('smoke') == 'true'
    issue = 0
    if smoke:
        base = api('')['default_branch']
        task = ('Create only docs/KIMI_SMOKE_TEST.md containing exactly these two lines:\n'
                '# Kimi pipeline test\nKIMI_PIPELINE_OK\n'
                'Do not edit any other file. Do not run builds or tests. Do not use subagents.')
    else:
        issue = int(event.get('inputs', {}).get('issue_number') or event.get('issue', {}).get('number') or 0)
        if issue <= 0:
            raise ValueError('Specify an issue number')
        data = api(f'/issues/{issue}')
        if data.get('pull_request') or data['state'] != 'open':
            raise ValueError('An open Issue is required, not a pull request')
        task = data.get('body') or ''
        match = re.search(r'^Kimi-Base:\s*([^\s]+)\s*$', task, re.M)
        base = event.get('inputs', {}).get('base_branch') or (match.group(1) if match else '')
        if not base:
            raise ValueError('Add Kimi-Base: branch-name to the issue, or set base_branch')
        if len(task) > 60000:
            raise ValueError('Issue is too large; reference task files in the selected branch')
    # Only resolve an existing branch, never a tag or untrusted arbitrary checkout URL.
    if not re.fullmatch(r'[A-Za-z0-9_./-]+', base) or '..' in base or base.startswith('-'):
        raise ValueError('Invalid base branch')
    commit = api('/branches/' + urllib.parse.quote(base, safe=''))['commit']['sha']
    prompt = ('Work only in /work on the supplied task. Read AGENTS.md if present. '
              'Fix root causes and preserve existing working behavior. Do not use subagents. '
              'Do not commit, push, create PRs, access credentials, or alter .git, .github, scripts, '
              'AGENTS.md, Gradle wrapper, or infrastructure. Allowed changes: app/, docs/, README.md, '
              'BUILD_CHECKS.md, BACKLOG.md. Do not claim checks passed unless you ran them. '
              'The pipeline builds and tests afterwards in a separate environment. '
              'Do not run Gradle in this container. Finish the changes, then stop.\n\nTASK:\n' + task)
    Path('task').mkdir(exist_ok=True)
    Path('task/prompt.txt').write_text(prompt)
    Path('task/meta.json').write_text(json.dumps({'base': base, 'sha': commit, 'issue': issue, 'smoke': smoke}))
    for name, value in [('base', base), ('sha', commit), ('issue', issue), ('smoke', str(smoke).lower())]:
        output(name, value)


def snapshot():
    meta = json.loads(Path('task/meta.json').read_text())
    # Reset any agent-created staging or commits; collect the working tree against the pinned base.
    git('reset', '--mixed', meta['sha'])
    git('add', '-A')
    paths = [p.decode() for p in git('diff', '--cached', '--name-only', '-z').split(b'\0') if p]
    if not paths or any(not allowed(p) for p in paths):
        raise ValueError('No changes, or changes outside the allowed scope')
    for entry in git('ls-files', '--stage', '-z').split(b'\0'):
        if entry and entry.split(b' ', 1)[0] not in (b'100644', b'100755'):
            raise ValueError('Symlinks and submodules are not supported')
    patch = git('diff', '--cached', '--binary', meta['sha'])
    if len(patch) > 2_000_000:
        raise ValueError('Patch exceeds 2 MB; split the task')
    secret = os.environ.get('KIMI_API_KEY', '').encode()
    if secret and secret in patch:
        raise ValueError('Credential found in patch; refusing upload')
    Path('result').mkdir(exist_ok=True)
    Path('result/changes.patch').write_bytes(patch)
    Path('result/meta.json').write_text(json.dumps(meta))
    output('changed', 'true')
    output('code', str(any(p.startswith('app/') for p in paths)).lower())


def apply():
    meta = json.loads(Path('result/meta.json').read_text())
    if git('rev-parse', 'HEAD').decode().strip() != meta['sha']:
        raise ValueError('Checkout differs from the pinned task base')
    patch = Path('result/changes.patch').resolve()
    git('apply', '--check', '--index', str(patch))
    git('apply', '--index', str(patch))
    paths = [p.decode() for p in git('diff', '--cached', '--name-only', '-z').split(b'\0') if p]
    if not paths or any(not allowed(p) for p in paths):
        raise ValueError('Invalid patch scope')
    for entry in git('ls-files', '--stage', '-z').split(b'\0'):
        if entry and entry.split(b' ', 1)[0] not in (b'100644', b'100755'):
            raise ValueError('Symlinks and submodules are not supported')
    git('diff', '--cached', '--check')
    if meta['smoke']:
        if paths != ['docs/KIMI_SMOKE_TEST.md'] or Path('work/docs/KIMI_SMOKE_TEST.md').read_text() != '# Kimi pipeline test\nKIMI_PIPELINE_OK\n':
            raise ValueError('Unexpected smoke test result')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['prepare', 'snapshot', 'apply'])
    globals()[parser.parse_args().mode]()
