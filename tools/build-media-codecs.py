#!/usr/bin/env python3
"""Pinned, source-built, offline-at-runtime FFmpeg decoder libraries for the NDK."""
import argparse
import hashlib
import os
from pathlib import Path
import platform
import re
import subprocess
import tarfile
import urllib.request

VERSION = '9.0.2'
SHA256 = '8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e'
TARGETS = {
    'arm64-v8a': ('aarch64', 'aarch64-linux-android'),
    'armeabi-v7a': ('arm', 'armv7a-linux-androideabi'),
    'x86_64': ('x86_64', 'x86_64-linux-android'),
    'x86': ('x86', 'i686-linux-android'),
}

def subtitle_decoders(source):
    # The checksum-pinned registry is authoritative; retain every audio/video entry.
    registry = (source / 'libavcodec/allcodecs.c').read_text()
    _, start, remainder = registry.partition('/* subtitles */')
    section, end, _ = remainder.partition('/* external libraries */')
    names = re.findall(r'^extern const FFCodec ff_(\w+)_decoder;$', section, re.MULTILINE)
    if not start or not end or not names or len(names) != section.count('_decoder;'):
        raise RuntimeError('Pinned FFmpeg subtitle registry changed')
    return sorted(names)

def main():
    args = argparse.ArgumentParser()
    args.add_argument('--ndk', type=Path, required=True)
    args.add_argument('--abi', choices=TARGETS, required=True)
    args.add_argument('--api', type=int, default=30)
    args.add_argument('--output', type=Path, required=True)
    options = args.parse_args()
    output = options.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    archive = output / f'ffmpeg-{VERSION}.tar.xz'
    if not archive.exists():
        local = os.environ.get('SAO_FFMPEG_ARCHIVE')
        if local:
            archive.write_bytes(Path(local).read_bytes())
        else:
            with urllib.request.urlopen(f'https://ffmpeg.org/releases/ffmpeg-{VERSION}.tar.xz', timeout=90) as response:
                with archive.open('wb') as destination:
                    import shutil
                    shutil.copyfileobj(response, destination)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != SHA256:
        archive.unlink()
        raise RuntimeError('FFmpeg source checksum mismatch')
    source = output / f'ffmpeg-{VERSION}'
    if not source.exists():
        with tarfile.open(archive) as bundle:
            bundle.extractall(output, filter='data')
    host = {'Linux': 'linux-x86_64', 'Darwin': 'darwin-x86_64'}[platform.system()]
    tools = options.ndk.resolve() / 'toolchains/llvm/prebuilt' / host / 'bin'
    arch, target = TARGETS[options.abi]
    build = output / 'objects'
    build.mkdir(exist_ok=True)
    configuration = [str(source / 'configure'), f'--prefix={output}', '--target-os=android',
        '--enable-cross-compile', f'--arch={arch}', f'--cc={tools / (target + str(options.api) + "-clang")}',
        f'--cxx={tools / (target + str(options.api) + "-clang++")}', f'--ar={tools / "llvm-ar"}',
        f'--ranlib={tools / "llvm-ranlib"}', f'--strip={tools / "llvm-strip"}', f'--nm={tools / "llvm-nm"}',
        '--enable-static', '--disable-shared', '--enable-pic', '--enable-small',
        '--disable-decoder=' + ','.join(subtitle_decoders(source)),
        '--disable-debug', '--disable-doc', '--disable-programs', '--disable-network',
        '--disable-autodetect', '--enable-zlib', '--disable-gpl', '--disable-nonfree', '--disable-encoders',
        '--disable-muxers', '--disable-avdevice', '--disable-avfilter', '--disable-swresample',
        '--disable-devices', '--disable-protocols', '--enable-protocol=file',
        '--extra-cflags=-Oz -ffunction-sections -fdata-sections -fvisibility=hidden',
        '--extra-ldflags=-Wl,--gc-sections']
    if arch in ('x86', 'x86_64'):
        configuration += ['--disable-x86asm']
    if arch == 'x86':
        configuration += ['--disable-inline-asm']
    stamp = build / 'sao-configuration.txt'
    desired = '\n'.join(configuration)
    if not stamp.exists() or stamp.read_text() != desired:
        # A changed compiler/LTO policy must not reuse objects from the old configuration.
        if (build / 'ffbuild/config.mak').exists():
            subprocess.run(['make', 'distclean'], cwd=build, check=True)
        subprocess.run(configuration, cwd=build, check=True)
        stamp.write_text(desired)
    subprocess.run(['make', f'-j{min(8, os.cpu_count() or 1)}'], cwd=build, check=True)
    subprocess.run(['make', 'install-libs', 'install-headers'], cwd=build, check=True)

if __name__ == '__main__':
    main()
