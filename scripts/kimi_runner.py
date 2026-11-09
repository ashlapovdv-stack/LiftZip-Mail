"""Bounded Docker lifecycle. Publish status codes, never private CLI output."""
import json
import os
from pathlib import Path
import subprocess
import time


def run(command, seconds, log, **kwargs):
    with log.open('ab') as stream:
        return subprocess.run(command, timeout=seconds, stdout=stream,
                              stderr=subprocess.STDOUT, **kwargs).returncode


def classify(log):
    # Whitelisted categories avoid leaking task contents, model output or secrets.
    data = log.read_text(errors='replace').lower() if log.exists() else ''
    for category, markers in [
        ('authentication', ('unauthorized', 'invalid api key', 'authentication failed', '401')),
        ('quota_or_rate_limit', ('insufficient balance', 'quota exceeded', 'rate limit', '429')),
        ('network', ('connection refused', 'connection timed out', 'enotfound')),
    ]:
        if any(marker in data for marker in markers):
            return category
    return 'unclassified'


def main():
    temporary = Path(os.environ['RUNNER_TEMP'])
    log = temporary / 'kimi-private-output.txt'
    status_dir = Path('agent-status')
    status_dir.mkdir(exist_ok=True)
    tag = 'kimi-task:' + os.environ['GITHUB_RUN_ID'] + '-' + os.environ['GITHUB_RUN_ATTEMPT']
    name = tag.replace(':', '-')
    status = {'phase': 'setup', 'state': 'failed', 'exit_code': None,
              'model': 'kimi-for-coding', 'cli_version': '2.5.2'}
    started = time.monotonic()
    try:
        if not os.environ.get('KIMI_MODEL_API_KEY'):
            status['reason'] = 'missing_api_key'
            return 1
        # No secret is provided to installation. Its budget is separate from inference.
        dockerfile = ('FROM node:24-bookworm\n'
                      'RUN npm install --prefix /opt/kimi @moonshot-ai/kimi-code@2.5.2\n')
        rc = run(['docker', 'build', '-t', tag, '-'], 240, log,
                 input=dockerfile.encode())
        if rc:
            status['exit_code'] = rc
            return 1
        status['phase'] = 'agent'
        env = {
            'KIMI_MODEL_API_KEY': None, 'KIMI_MODEL_NAME': 'kimi-for-coding',
            'KIMI_MODEL_BASE_URL': 'https://api.kimi.com/coding/v1',
            'KIMI_MODEL_PROVIDER_TYPE': 'kimi', 'KIMI_CODE_HOME': '/tmp/kimi-home',
            'KIMI_DISABLE_TELEMETRY': '1', 'KIMI_CODE_NO_AUTO_UPDATE': '1',
            'KIMI_CODE_TRUST_WORKSPACE': '1', 'KIMI_LOG_LEVEL': 'off',
            'KIMI_DISABLE_CRON': '1', 'KIMI_LOOP_MAX_STEPS_PER_TURN': '60',
            'KIMI_LOOP_MAX_ATTEMPTS_PER_STEP': '2',
        }
        command = ['docker', 'create', '--name', name, '--init',
                   '--user', f'{os.getuid()}:{os.getgid()}',
                   '-v', str(Path('work').resolve()) + ':/work',
                   '-v', str(Path('task').resolve()) + ':/task:ro', '-w', '/work']
        for key, value in env.items():
            command += ['-e', key if value is None else key + '=' + value]
        command += [tag, 'sh', '-c',
                    'exec /opt/kimi/node_modules/.bin/kimi -p "$(cat /task/prompt.txt)"']
        rc = run(command, 30, log)
        if not rc:
            rc = run(['docker', 'start', '--attach', name], 2400, log)
        status['exit_code'] = rc
        status['state'] = 'completed' if rc == 0 else 'failed'
        return 0 if rc == 0 else 1
    except subprocess.TimeoutExpired:
        status['state'] = 'timed_out'
        return 1
    except OSError:
        status['reason'] = 'runner_error'
        return 1
    finally:
        # Killing the attach client alone does not stop its container. Remove it before
        # snapshotting, so no writer can race git add / patch creation.
        try:
            rc = run(['docker', 'rm', '-f', name], 30, log)
            # A setup failure may mean the container never existed.
            if rc and status['phase'] == 'agent':
                status['cleanup_failed'] = True
        except (subprocess.TimeoutExpired, OSError):
            status['cleanup_failed'] = True
        status['elapsed_seconds'] = round(time.monotonic() - started)
        if status['state'] != 'completed':
            status['error_category'] = classify(log)
        (status_dir / 'status.json').write_text(json.dumps(status, indent=2) + '\n')
        summary = (f"Kimi: {status['state']}; phase: {status['phase']}; "
                   f"exit: {status['exit_code']}; model: {status['model']}")
        print(summary)
        if os.environ.get('GITHUB_STEP_SUMMARY'):
            with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as stream:
                stream.write(summary + '\n')
        log.unlink(missing_ok=True)


if __name__ == '__main__':
    raise SystemExit(main())
