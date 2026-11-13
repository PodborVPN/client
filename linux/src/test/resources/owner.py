#!/usr/bin/env python3
import pathlib
import signal
import subprocess
import sys

child = subprocess.Popen(sys.argv[1:], stdin=subprocess.DEVNULL)


def stop():
    child.terminate()
    try:
        child.wait(timeout=1)
    except subprocess.TimeoutExpired:
        child.kill()
        child.wait()


def interrupted(number, frame):
    stop()
    sys.exit(0)


signal.signal(signal.SIGTERM, interrupted)
sys.stdin.buffer.read()
pathlib.Path(__file__).with_name("owner-eof").touch()
stop()
