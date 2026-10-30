#!/usr/bin/env python3
"""Check an installed minified ARM64 SAO APK from the account-free probe's foreign UID."""
import argparse
import json
from pathlib import Path
import re
import shlex
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--mapping', type=Path, default=Path(__file__).resolve().parents[2] / 'app/build/outputs/mapping/release/mapping.txt')
    args = parser.parse_args()
    boot = subprocess.check_output(['adb', '-s', args.serial, 'shell', 'getprop', 'ro.kernel.qemu'], text=True).strip()
    if boot != '1':
        raise SystemExit('This fixture is emulator-only; it does not inspect a personal device.')
    owner = None
    entries = set()
    signature = 'one.njk.sao.media.NativeDecoder$Companion.open(android.content.Context,java.io.File,long,java.util.function.BooleanSupplier)'
    for line in args.mapping.read_text().splitlines():
        match = re.match(r'^\S+ -> (\S+):$', line)
        if match:
            owner = match.group(1)
        if signature in line:
            entries.add((owner, line.rsplit(' -> ', 1)[1]))
    if len(entries) != 1:
        raise SystemExit('Expected one decoder entry in the matching R8 map; rebuild the module and inspect its mapping.')
    owner, method = entries.pop()
    command = ['am', 'instrument', '--abi', 'arm64-v8a', '-w', '-e', 'entryClass', owner,
               '-e', 'entryMethod', method, 'one.njk.sao.instagramprobe/one.njk.sao.floating.nativebridge.ModuleLibraryProbe']
    output = subprocess.check_output(['adb', '-s', args.serial, 'shell', shlex.join(command)], text=True, timeout=60)
    line = next((line for line in output.splitlines() if line.startswith('INSTRUMENTATION_RESULT: report=')), None)
    if line is None:
        raise SystemExit(output)
    report = json.loads(line.split('=', 1)[1])
    if not all(report.get(key) is True for key in ('module_feedback_retained', 'module_shaders_retained',
            'foreign_host_identity_preserved', 'minified_module_decoder_loaded')) or report.get('decoded_frames') != 10:
        raise SystemExit('Module resource/library verification failed: ' + json.dumps(report))
    print(json.dumps(report))


if __name__ == '__main__':
    main()
