"""The forwarder in front of Betaflight as a program (SITL), inside WSL, for the DRAUPNE test bench.

Windows reaches a WSL program's TCP port through WSL's forwarding of 127.0.0.1. SITL ends at every save, CLI exit and
MSP reboot and its supervisor starts it again, so its ports go away and come back - and now and then (2026-11-23)
WSL's forwarding then no longer reached the new program: Betaflight's log had no "New connection" for a minute while
Windows was refused, then connected and got no answer. This forwarder listens on ports of its own that never go away
(it does not restart with SITL); each connection from Windows gets a fresh connection to SITL's own port at 127.0.0.1
inside WSL, and when either side ends, both do - the next connection reaches the program that runs then. Nothing is
looked at or changed on the way. SITL itself takes one client a UART: a second one is closed by SITL, as without this.

  python3 -I forwarder.py 5761:15761 5762:15762 5764:15764      SITL's port : the forwarder's, one pair a UART
"""
import socket
import sys
import threading
import time


def say(text):
    print("%s forwarder: %s" % (time.strftime("%H:%M:%S"), text), flush=True)


def pump(src, dst, pair):
    try:
        while True:
            data = src.recv(65536)
            if not data:
                break
            dst.sendall(data)
    except OSError:
        pass
    finally:
        for s in pair:  # one side ended: both do
            try:
                s.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            try:
                s.close()
            except OSError:
                pass


def serve(target, listen):
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        server.bind(("0.0.0.0", listen))
        server.listen(16)
    except OSError as e:
        say("CANNOT LISTEN on %d for SITL's %d: %s" % (listen, target, e))
        return
    say("listening on %d for SITL's %d" % (listen, target))
    while True:
        client, _ = server.accept()
        try:
            board = socket.create_connection(("127.0.0.1", target), timeout=1)
        except OSError:
            client.close()  # SITL restarts: nothing there yet - the client tries again, as with the port itself
            continue
        board.settimeout(None)
        for s in (client, board):
            s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        pair = (client, board)
        threading.Thread(target=pump, args=(client, board, pair), daemon=True).start()
        threading.Thread(target=pump, args=(board, client, pair), daemon=True).start()


def main():
    pairs = []
    for word in sys.argv[1:]:
        target, listen = word.split(":")
        pairs.append((int(target), int(listen)))
    if not pairs:
        sys.exit("forwarder.py <sitl port>:<listen port> ...")
    for target, listen in pairs:
        threading.Thread(target=serve, args=(target, listen), daemon=True).start()
    while True:
        time.sleep(3600)


if __name__ == "__main__":
    main()
