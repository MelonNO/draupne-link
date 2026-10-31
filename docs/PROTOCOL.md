# DroidDrone protocol notes (protocol version 9, app 2.3.0, upstream commit 473608d)

These notes were worked out from the upstream source. File references are relative to `upstream/`.

## Topology
- **Flight app** (phone on the drone) talks MSP or MAVLink to the FC over USB OTG or UART, and encodes camera video.
- **Control app** (phone with the radio) shows video and OSD and sends RC. **This project replaces it with the PC.**
- **Server** (`Server/`, optional Java relay) is for when neither side has a reachable IP. Viewers can also connect through it.
- **Direct mode:** the Flight phone listens on UDP 6286 and the controller connects to its IP. A phone on 4G has no reachable IP, so direct mode then needs a VPN.
- **Over-server mode:** both sides connect out to the relay (`Server/src/.../Udp.java`). The relay echoes each `Connect` itself and forwards packets between drone (client type 0) and controller (type 1), so the controller code is the same in both modes. The relay knows clients by IP:port.

## Transport (`Common/.../UdpCommon.java`, `UdpSender.java`, `ReceiverBuffer.java`)
- UDP, default port 6286, packets of at most 1100 bytes, big-endian (Java `DataOutputStream`). Strings use `writeUTF`.
- Byte 0 is the packet type (0–26).
- **Numbered packets** (`isPacketNumbered`) carry an int16 sequence number after the type. The receiver delivers them in order and asks for gaps with `RequestPackets`.
- Some numbered packets are **ACKed** with `PacketReceived` (`isSendPacketReceived`). The sender resends them about every 1.2 × ping until ACKed or expired (1 s).
- Video, audio and RC frames are unnumbered and fire-and-forget.
- The link is considered lost after about 4000 ticks of `processTimer()` (about 4 s at the 1 ms loop) with no packets.
- Ping/Pong: the controller sends `Ping(toEndPoint=true, timeMs)` every 500 ms, and the end point echoes it as `Pong`.

## Handshake (Control `MainActivity.startConnectionThread`)
1. Every 500 ms: `Connect` = `[8][int16 -1][clientType u8][key UTF][version int16]`. Client type: 0 = flight, 1 = controller, 2 = viewer.
   The Flight side (`ReceiverBuffer` with isServer = true) checks the key and echoes the `Connect` back, which connects the controller too.
2. Every 500 ms until answered: `Config` (layout in `Control/.../Udp.sendConfig` and `Flight/.../Config.processReceivedConfig`).
   The Flight app replies `ConfigReceived`, or `VersionMismatch` if the version differs from 9.
3. `StartVideo(isHevcSupported bool)` every 2.5 s until a `VideoInitialFrame` arrives.
4. Requests for `FcInfo`, `OsdConfig`, `BoxIds`, `BatteryConfig`. Each is an empty packet of that type, and the answer comes back with the same type.

## RC
- `RcFrame` = `[24][count u8][count × int16]`, with 4–18 channels at 1000–2000, sent at `rcRefreshRate` Hz (default 25).
- The Flight app's only use of `rcRefreshRate` is a minimum gap in `Msp.setRawRc` (and `Mavlink`): frames arriving within `1000 / rate / 2` ms of the previous one are **dropped**. DRAUPNE Link announces 255, the most the byte holds (a 1 ms window), and sends at its own rate.
- Channel order is **AETR, then AUX1…** The Flight app applies the FC's `rxmap` (`Msp.processRxMap`) before `MSP_SET_RAW_RC`.
- The Flight RC thread only forwards frames it actually receives. If no frame arrives, nothing is sent to the FC, and Betaflight's RX-loss failsafe triggers.

## Video (milestone 2)
- Android `MediaCodec` encoder: H.265, falling back to H.264. I-frame interval 0.5 s. Bitrate up to `bitrateLimit`, adapted by `ChangeBitRate(increase bool)` from the controller.
- `VideoInitialFrame` = `[0][int16 num][width i16][height i16][isHevc bool][isFrontCamera bool][codec config bytes (VPS/SPS/PPS)]`.
- `VideoFrame`/`KeyFrame` = `[1|2][frameNum i16][offset i32][frameSize i32 if offset==0][bytes]`. Frames are split across packets and put back together by offset (`Control/.../FrameFragments.java`).
- The controller lowers the bitrate when frames are lost or late, and raises it again after 10 s without losses (`Udp.processBitRateChange`).

## Telemetry / OSD (milestone 3)
- `TelemetryData` = a run of `[code int16][payload]` records with **no length field**. You must know every code's layout to read past it.
  The full decoder is `Control/.../Udp.processTelemetryData`, and the drawing code is `Control/.../Osd.java`.
