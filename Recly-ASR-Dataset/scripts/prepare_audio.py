"""Single-pass ffmpeg conversion to 16 kHz mono signed 16-bit WAV."""
import hashlib
import subprocess
import wave
from pathlib import Path


def convert(source: bytes | Path, target: Path) -> tuple[float, str]:
    target.parent.mkdir(parents=True, exist_ok=True)
    command = ['ffmpeg', '-hide_banner', '-loglevel', 'error', '-nostdin', '-y']
    if isinstance(source, bytes):
        command += ['-i', 'pipe:0']
    else:
        command += ['-i', str(source)]
    command += ['-map', '0:a:0', '-vn', '-ac', '1', '-ar', '16000', '-c:a', 'pcm_s16le', str(target)]
    result = subprocess.run(command, input=source if isinstance(source, bytes) else None,
                            capture_output=True)
    if result.returncode:
        target.unlink(missing_ok=True)
        raise ValueError(result.stderr.decode(errors='replace')[-500:])
    with wave.open(str(target), 'rb') as wav:
        if (wav.getnchannels(), wav.getsampwidth(), wav.getframerate()) != (1, 2, 16000):
            raise ValueError('unexpected WAV format')
        frames = wav.getnframes()
    digest = hashlib.sha256()
    with target.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return frames / 16000, digest.hexdigest()
