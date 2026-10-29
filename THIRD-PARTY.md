# Third-party components

DroidDrone PC is licensed under the GPL-3.0 (see [LICENSE](LICENSE)). The release builds include these components:

| Component | Use | License |
|---|---|---|
| [DroidDrone](https://github.com/DroidDrone/DroidDrone) (`upstream/` submodule: Common protocol code and relay server) | Wire protocol, in-process relay | GPL-3.0 |
| [FFmpeg](https://ffmpeg.org) via [JavaCPP Presets](https://github.com/bytedeco/javacpp-presets) (`org.bytedeco:ffmpeg`, LGPL build) | H.265/H.264 decoding | LGPL-2.1+ (FFmpeg), Apache-2.0 / GPL-2.0 with Classpath Exception (JavaCPP) |
| [LWJGL 3](https://www.lwjgl.org) + GLFW | Joystick input | BSD-3-Clause, zlib |
| [FlatLaf](https://www.formdev.com/flatlaf/) | Look and feel | Apache-2.0 |
| [ZXing](https://github.com/zxing/zxing) | QR codes | Apache-2.0 |
| [Gson](https://github.com/google/gson) | Reading Tailscale status | Apache-2.0 |
| [jSerialComm](https://fazecast.github.io/jSerialComm/) | Talking to Betaflight over USB | Apache-2.0 / LGPL-3.0 |
| [Eclipse Temurin](https://adoptium.net) Java runtime (bundled by jpackage) | Runtime | GPL-2.0 with Classpath Exception |

The installer installs [Tailscale](https://tailscale.com) from Tailscale's own signed package, and the setup wizard downloads this project's build of DroidDrone Flight (upstream DroidDrone Flight 2.3.0, GPL-3.0, with the changes in [`flight-app/`](flight-app)) from this project's GitHub releases. Neither is bundled.
FFmpeg's source is available from ffmpeg.org; the exact build used comes from the JavaCPP Presets project.
