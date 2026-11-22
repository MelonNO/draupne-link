"""The level-attitude feeder for Betaflight as a program (SITL): what a simulator would send it, for a drone that
stands level and still on the ground - forty times a second, to UDP 127.0.0.1:9003, Betaflight's "fdm_packet".

Without it SITL does not arm: its attitude is set by the simulator's packets alone (USE_IMU_CALC is off in SITL),
and until the first one comes it is "not established" - the arming block ANGLE holds. With it: level, 1 G down on
the accelerometer, the gyro at rest, the barometer at 1013.25 hPa (Betaflight 4.5 on has a pressure field).

Forty a second and not faster, on purpose: Betaflight sets its own clock's rate from the packets' time stamps when
two of them are less than 20 ms apart (simRate); 25 ms apart it leaves its clock at the PC's own, so the failsafe's
times on SITL are real times.

  python3 feeder.py [version]      version: 4.4.1 (default: the version.txt of the folder it runs in)
"""
import os
import socket
import struct
import sys
import time

PERIOD = 0.025
STATE_PORT = 9003


def with_pressure(version):
    """Betaflight 4.5 added "double pressure" at the end of the packet; it takes a packet of its exact size only."""
    parts = version.split(".")
    try:
        major, minor = int(parts[0]), int(parts[1])
    except (ValueError, IndexError):
        return True
    return major > 4 or (major == 4 and minor >= 5)


def packet(t, pressure):
    gyro = (0.0, 0.0, 0.0)              # rad/s
    acc = (0.0, 0.0, -9.80665)          # m/s/s, NED body frame: standing still, 1 G (Betaflight turns it to +256 on z)
    quat = (1.0, 0.0, 0.0, 0.0)         # w, x, y, z: level
    vel = (0.0, 0.0, 0.0)
    pos = (0.0, 0.0, 0.0)
    values = (t,) + gyro + acc + quat + vel + pos
    if pressure:
        values += (101325.0,)
    return struct.pack("<%dd" % len(values), *values)


def main():
    version = sys.argv[1] if len(sys.argv) > 1 else open("version.txt").read().strip() if os.path.exists("version.txt") else "4.4.1"
    pressure = with_pressure(version)
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    print("feeder: Betaflight %s, %d-byte packets to 127.0.0.1:%d every %d ms" % (version, len(packet(0, pressure)), STATE_PORT, PERIOD * 1000), flush=True)
    last = 0.0
    sent = 0
    while True:
        now = time.monotonic()
        if now - last >= PERIOD:
            s.sendto(packet(now, pressure), ("127.0.0.1", STATE_PORT))
            last = now
            sent += 1
            if sent % 2400 == 0:
                print("feeder: %d packets" % sent, flush=True)
        time.sleep(max(0.001, PERIOD - (time.monotonic() - last)))


if __name__ == "__main__":
    main()
