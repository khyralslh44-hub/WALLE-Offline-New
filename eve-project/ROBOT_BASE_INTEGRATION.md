# EVE mobile-base add-on (Bluetooth LE)

This is a future-ready hardware interface contract. It is **not** a claim of compatibility with every commercial Bluetooth robot base. A base must implement the GATT UUIDs and packet format below, or a model-specific adapter must be added after its vendor protocol is known.

## What the phone does locally

- Sends bounded wheel commands over Bluetooth LE only after a compatible service is discovered.
- Reads fresh, checksummed obstacle, floor-edge, bumper, emergency-stop, motor-state, battery, and fused odometry telemetry.
- Runs a fail-closed navigation policy: it stops on a detected/possible drop, stale sensors or pose, missing required sensors, a person in the current camera view, bumper press, base fault, emergency stop, or low battery.
- Uses short motor-command leases; base firmware must independently stop motors when a lease expires or the phone link is lost.
- Builds a bounded local occupancy grid from measured ranges and the base's fused pose; associates cautious room hypotheses with map cells and chooses nearby unexplored frontiers. This is an exploration aid, not a claim of full SLAM.

No movement command should be accepted without fresh sensor and pose telemetry. The user must explicitly say **"استكشفي المنزل بالقاعدة"** to enable movement. **"أوقفي القاعدة"** disables movement immediately. A cliff/fault stop is latched and requires an explicit safe-reset command after every sensor reading is current and safe.

## Required hardware before enabling wheels

A compatible base must contain:
- independently addressable left and right drive motors;
- front, rear, left, and right distance sensors;
- two down-facing cliff/drop sensors near the left and right sides;
- bumper input and a physical emergency-stop input;
- battery and motor-controller fault reporting;
- wheel encoders plus an IMU (or equivalent fused odometry) that reports a stable local (x,y,heading) pose;
- a firmware watchdog that brakes/stops the motors if a valid command is not renewed within 300 ms.

The phone cannot guarantee edge avoidance from camera pixels alone. If a cliff sensor, required distance sensor, or fresh pose is missing or invalid, the app refuses to move. Do not test near stairs or a tabletop edge until the base firmware, physical stop, cliff sensors, and watchdog have each been validated independently.

## GATT contract

UUIDs:
- Service: `6f8e0001-7d5b-4d7a-ae11-2e9b00000001`
- Phone command characteristic (write with response): `6f8e0002-7d5b-4d7a-ae11-2e9b00000001`
- Base telemetry characteristic (notify/indicate): `6f8e0003-7d5b-4d7a-ae11-2e9b00000001`
- Fused odometry pose characteristic (notify/indicate): `6f8e0004-7d5b-4d7a-ae11-2e9b00000001`

All multibyte sensor distances are unsigned little-endian millimetres. Sensor values `0xFFFF` mean invalid and must not be interpreted as clear space.

### Phone command frame (8 bytes)

| Byte | Meaning |
|---|---|
| 0 | Magic `0xE7` |
| 1 | Protocol version `1` |
| 2 | `0x10` STOP, `0x11` DRIVE, `0x12` REQUEST_STATUS |
| 3 | Rolling sequence number (0–255) |
| 4 | Left wheel percent, signed int8 (−100…100) |
| 5 | Right wheel percent, signed int8 (−100…100) |
| 6 | Lease in 100 ms units (0…5); DRIVE never uses a lease above 500 ms |
| 7 | XOR of bytes 0–6 |

The base must reject invalid magic/version/length/checksum and unknown command types. It must clamp speed values, apply a watchdog independently of Android, and treat STOP as a brake/zero-wheel request.

### Base telemetry frame (20 bytes, one notification)

| Byte(s) | Meaning |
|---|---|
| 0 | Magic `0xE7` |
| 1 | Protocol version `1` |
| 2 | Telemetry type `0x80` |
| 3 | Rolling sequence number (0–255) |
| 4 | Sensor flags: bit 0 front valid, 1 rear valid, 2 left valid, 3 right valid, 4 left cliff valid, 5 right cliff valid, 6 bumper pressed, 7 emergency stop pressed |
| 5 | Battery percent 0–100 |
| 6–7 | Front range (mm) |
| 8–9 | Rear range (mm) |
| 10–11 | Left range (mm) |
| 12–13 | Right range (mm) |
| 14–15 | Left down-facing/cliff range (mm) |
| 16–17 | Right down-facing/cliff range (mm) |
| 18 | Status: bit 0 motor driver ready, bit 1 base fault |
| 19 | XOR of bytes 0–18 |

### Fused pose frame (16 bytes, one notification)

| Byte(s) | Meaning |
|---|---|
| 0 | Magic `0xE7` |
| 1 | Protocol version `1` |
| 2 | Pose type `0x81` |
| 3 | Rolling sequence number |
| 4–7 | Signed little-endian (x) position in millimetres |
| 8–11 | Signed little-endian (y) position in millimetres |
| 12–13 | Signed little-endian heading in centidegrees (−18000…18000) |
| 14 | Bit 0 means the fused pose is valid |
| 15 | XOR of bytes 0–14 |

Map convention: (0°=+X), with positive heading counter-clockwise. The base must keep the pose origin stable during a mapping session and publish pose and sensor frames at least 10 Hz. The app rejects wrong size/version/checksum frames and considers telemetry or pose unsafe after 600 ms without a fresh frame. The grid is bounded and held locally for the current connected exploration session; it does not yet promise a persistent map across app restarts, reconnects, or base reboots.

A detected drop is latched until a safe-reset command is accepted; reconnection alone never clears it.

## Connection and voice commands

No connection scan runs at app launch. The user can say:
- **"اتصلي بالقاعدة"** — search for a compatible base over BLE.
- **"استكشفي المنزل بالقاعدة"** — request connection if needed, then enable guarded autonomous movement once camera, link, sensors, and pose are ready.
- **"أوقفي القاعدة"** — stop and disable movement.
- **"افصلي القاعدة"** — stop and disconnect.
- **"استعادة الحركة"** — clear a latched stop only if the current telemetry passes all safety checks.
- **"ما اسم الغرفة"** — report the latest cautious visual room hypothesis.

Android Bluetooth permissions are requested only when the optional feature is invoked. When the app is backgrounded, it stops issuing wheel commands.

## Room recognition and map limitations

Room type is inferred locally from repeated whole-image/object labels—for example, bed plus pillow may support a bedroom hypothesis, while a fridge plus oven/microwave may support a kitchen hypothesis. A single cup or table is not enough. Room labels remain hypotheses, not certainties. Narrow passages, stairs, glass, reflective surfaces, dynamic obstacles, or miscalibrated sensors require hardware-specific tuning and supervised validation. This is not a production SLAM system.

## Test status

The protocol decoder and navigation policy have unit tests; the map/frontier policy has local unit tests. **No physical base has been connected to this project yet.** Before hardware use, implement this firmware contract and bench-test with wheels lifted, then validate emergency stop, cliff sensors, watchdog, and obstacle avoidance in a slow supervised floor test. Never test the first build near a real drop.
