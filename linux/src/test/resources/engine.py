#!/usr/bin/env python3
import json
import os
import pathlib
import socket
import sys
import time

assert sys.argv[1:3] == ["run", "-c"]
config = json.loads(pathlib.Path(sys.argv[3]).read_text())
pathlib.Path(config["started"]).write_text(str(os.getpid()))
if config.get("starting", False):
    time.sleep(30)
else:
    with socket.socket() as listener:
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listener.bind(("127.0.0.1", config["port"]))
        listener.listen()
        print("Xray test started", flush=True)
        while True:
            connection, peer = listener.accept()
            connection.close()
