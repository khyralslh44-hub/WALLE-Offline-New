# EVE mobile-base add-on (Bluetooth LE)

This is a future-ready interface contract for a mobile base. It is **not** a claim of compatibility with every commercial Bluetooth robot base. A base must implement the GATT UUIDs and packet format below, or a model-specific adapter must be added after its vendor protocol is known.

## What the phone does locally

- Sends bounded wheel commands over Bluetooth LE only after a compatible service is discovered.
- Reads fresh, checksummed obstacle, floor-edge, bumper, emergency-stop, motor-state, and battery telemetry.
- Runs a fail-closed navigation policy: it stops on a detected/possible drop, stale telemetry, missing required sensors, a person in the current camera view, bumper press, base fault, emergency stop, or low battery.
- Uses short motor-command leases; the base firmware must independently stop motors when a lease expires or the phone link is lost.
- Can combine the phone's on-device object/image labels with repeated evidence to suggest room types. These are hypotheses, not a metric map or guaranteed room recognition.

No movement command should be accepted by the base without fresh sensor telemetry. The user must explicitly say **"استكشفي المنزل بالقاعدة"** to enable movement. **"أوقفي القاعدة"** must disable movement immediately. A safety latch requires an explicit safe-reset command after all sensor readings are current and safe.

## Required hardware before enabling wheels

A compatible base must contain:
- independently addressable left and right drive motors;
- front, rear, left, and right distance sensors;
- two down-facing cliff/drop sensors, near the left and right sides;
- bumper input and a physical emergency-stop input;
- battery and motor-controller fault reporting;
- a firmware watchdog that stops/ brakes the motors if no valid command is renewed within 300 ms.

The phone cannot guarantee edge avoidance from camera pixels alone. If a cliff sensor is missing, invalid, or out of range, the app refuses to move. Do not test near stairs or a tabletop edge until the base firmware, cliff sensors, physical stop, and watchdog have each been validated independently.

## GATT contract

UUIDs:
- Service: `6f8e0001-7d5b-4d7a-ae11-2e9b00000001`
- Phone command characteristic (write with response): `6f8e0002-7d5b-4d7a-ae11-2e9b00000001`
- Base telemetry characteristic (notify/indicate): `6f8e0003-7d5b-4d7a-ae11-2e9b00000001`

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

The base should notify at 10 Hz or faster. The phone rejects frames with wrong size, version, type, or checksum and considers telemetry unsafe after 600 ms without a fresh frame. A detected drop is latched until a safe-reset command is accepted; reconnection alone must never clear it.

## Connection and voice commands

No connection scan runs on app launch. The user can say:
- **"اتصلي بالقاعدة"** — search for a compatible base over BLE.
- **"استكشفي المنزل بالقاعدة"** — request connection if needed, then enable guarded autonomous motion once the link and sensors are ready.
- **"أوقفي القاعدة"** — stop and disable base movement.
- **"افصلي القاعدة"** — stop and disconnect.
- **"استعادة الحركة"** — clear a latched stop only if the current telemetry passes every safety check.

Android Bluetooth permissions are requested only when this optional feature is invoked.

## Not provided by a generic BLE protocol

Room recognition can return cautious labels such as kitchen/bedroom/bathroom/living room when visual evidence supports them. A reliable persistent map of the entire home needs a real odometry source (wheel encoders or equivalent), repeatable localization, and calibrated sensors. Those are not inferred from an ordinary Bluetooth connection and must be added for the specific purchased base.

## Test status

The protocol decoder and navigation policy are covered by local unit tests. No physical mobile base has been connected to this project yet. Before use with hardware, a base-specific firmware implementation and bench tests with wheels lifted, emergency-stop checks, cliff-sensor checks, and then slow supervised floor tests are mandatory.
