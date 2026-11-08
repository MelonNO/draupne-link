# Third-party components

DRAUPNE Link is licensed under the GPL-3.0 (see [LICENSE](LICENSE)). The release builds include these components:

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
| RadioMaster TX16S MKII CAD model by YahyaELone (GrabCAD, STEP from Autodesk Inventor), tessellated into `src/main/resources/model/tx16s-mkii.bin.gz` | The 3D radio in the calibration | Redistributed with permission (confirmed by the maintainer, 2026-11-05) |
| FPV drone 3D model (OBJ + textures), converted into `src/main/resources/model/fpv-drone.bin.gz` and `fpv-drone-0.jpg` / `fpv-drone-1.jpg` by `tools/model/export_fpv_drone.py` | The 3D drone in Drone setup > Orientation | Redistributed with permission (confirmed by the maintainer, 2026-11-07) |
| BTR model: cadnav.com (Models_H1205A034, OBJ + textures), converted into `src/main/resources/model/btr.bin.gz` and `btr-0.jpg` .. `btr-3.jpg` by `tools/model/export_btr.py` | The vehicle in the tour's virtual overwatch scene | cadnav.com's terms: free; may be used as part of a project, freely modified; no separate resale or redistribution of the model on its own; credit cadnav.com. Bundled as part of this program with the maintainer's OK (2026-11-08); the original files are not in the repository |
| Sounds from [freesound.org](https://freesound.org), cut, levelled and looped into `src/main/resources/sound/*.wav` (16 kHz mu-law) by `tools/sound/prepare_sounds.py`: [Military Vehicle Humming](https://freesound.org/s/182793/) by qubodup (`btr-idle`), [truck large diesel or military tank APC ... pull away](https://freesound.org/s/405086/) by kyles (`btr-drive`), [Tiny Hawk 2 short flight](https://freesound.org/s/677833/) by Sadiquecat (`fpv-spinup`), [FPV Drone Flight 3](https://freesound.org/s/854466/) by qubodup (`fpv-flight`) | The virtual fleet's BTR and FPV motors | CC0 1.0 (public domain dedication) |

The installer installs [Tailscale](https://tailscale.com) from Tailscale's own signed package, and the setup wizard downloads this project's build of DRAUPNE Flight (upstream DroidDrone Flight 2.3.0, GPL-3.0, with the changes in [`flight-app/`](flight-app)) from this project's GitHub releases. Neither is bundled.
FFmpeg's source is available from ffmpeg.org; the exact build used comes from the JavaCPP Presets project.
