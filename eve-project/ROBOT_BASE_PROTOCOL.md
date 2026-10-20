# EVE Mobile Base: BLE/GATT Hardware Contract (v1)

This is the integration contract for a future wheeled base for the Android EVE app. The app already contains the BLE client, wheel-command protocol, safety gate, local occupancy/room map, and frontier-heading planner. **It is not a generic driver for arbitrary Bluetooth toys.** The base firmware must implement this exact contract, or a future adapter must be written for the hardware you buy.

The phone is the high-level brain. The base MCU remains responsible for motor timing and physical emergency stops.

## Minimum hardware before autonomous motion

Required for the app to allow motion:
- Differential-drive base with independently controlled left and right wheels.
- Wheel encoders plus IMU (or equivalent fused odometry) to provide local x/y/heading.
- Four obstacle range measurements: front, rear, left, right.
- Two downward floor/cliff sensors at the left and right front edges.
- Bumper input, physical latching emergency-stop input, motor-ready and fault status.
- Battery monitoring.
- Motor-driver watchdog on the base itself: stop both motors when the drive lease expires, on BLE loss, MCU reset, or brownout. A phone command is never the only emergency stop.

The cliff sensors must be mounted and calibrated so a normal floor gives a distance below 145 mm and a missing floor/drop gives an invalid reading or at least 145 mm. Verify this on the actual base before putting it near stairs. A camera-only phone app is not a safety-rated cliff detector.

## BLE/GATT service

Advertise the service UUID below. EVE scans specifically for this UUID; device name alone is not used to authorize movement.

| Attribute | UUID | Required properties |
|---|---|---|
| EVE Base service | `6f8e0001-7d5b-4d7a-ae11-2e9b00000001` | Primary service |
| Command | `6f8e0002-7d5b-4d7a-ae11-2e9b00000001` | Write **or** Write Without Response |
| Safety telemetry | `6f8e0003-7d5b-4d7a-ae11-2e9b00000001` | Notify or Indicate |
| Fused pose | `6f8e0004-7d5b-4d7a-ae11-2e9b00000001` | Notify or Indicate |

Notification characteristics require the standard Client Characteristic Configuration Descriptor (`0x2902`). After both telemetry and pose notifications are enabled, the app requests a status frame and transmits STOP before it considers the link ready. Readiness does **not** itself start the wheels.

## Command frame: 8 bytes

All multi-byte fields are little-endian. Unsigned sequence wraps at 255. Byte 7 is the XOR of bytes 0 through 6.

| Byte | Meaning |
|---:|---|
| 0 | Magic `0xE7` |
| 1 | Protocol version `0x01` |
| 2 | Type: STOP=`0x10`, DRIVE=`0x11`, REQUEST_STATUS=`0x12` |
| 3 | Sequence number (0–255) |
| 4 | Left wheel target, signed percent -100..100 |
| 5 | Right wheel target, signed percent -100..100 |
| 6 | Drive lease in 100 ms units, 0..5 (maximum 500 ms) |
| 7 | XOR checksum |

Firmware requirements:
- Treat STOP, malformed frames, unknown versions/types, invalid checksums, and expired leases as zero wheel target.
- DRIVE uses a differential-drive convention: positive moves forward; negative reverses. Verify motor polarity during commissioning at a raised-wheel or otherwise safely immobilized bench test.
- For every DRIVE command, stop within the lease timeout unless a newer valid DRIVE arrives. The app normally sends short leases; this is a hard watchdog, not an optional convenience.
- REQUEST_STATUS returns current telemetry and pose notifications.

## Safety telemetry frame: 20 bytes

All distances are unsigned millimetres, little-endian. `0xFFFF` means unavailable. A sensor's valid flag must be cleared whenever that sensor is not trustworthy.

| Byte(s) | Meaning |
|---:|---|
| 0 | Magic `0xE7` |
| 1 | Version `0x01` |
| 2 | Type `0x80` |
| 3 | Sequence number |
| 4 | Flags: bit0 front valid, bit1 rear valid, bit2 left valid, bit3 right valid, bit4 left-cliff valid, bit5 right-cliff valid, bit6 bumper pressed, bit7 emergency stop pressed |
| 5 | Battery percent 0..100 |
| 6–7 | Front range (mm) |
| 8–9 | Rear range (mm) |
| 10–11 | Left range (mm) |
| 12–13 | Right range (mm) |
| 14–15 | Left downward cliff-sensor floor distance (mm) |
| 16–17 | Right downward cliff-sensor floor distance (mm) |
| 18 | Status: bit0 motor driver ready, bit1 base fault |
| 19 | XOR checksum of bytes 0 through 18 |

Publish fresh telemetry preferably at 10 Hz or faster; the app rejects telemetry older than 600 ms. Report a missing or faulted sensor as invalid, not as a safe distance.

## Fused pose frame: 16 bytes

The app's local occupancy map uses this frame; it does not infer metric movement from phone pixels.

| Byte(s) | Meaning |
|---:|---|
| 0 | Magic `0xE7` |
| 1 | Version `0x01` |
| 2 | Type `0x81` |
| 3 | Sequence number |
| 4–7 | Signed 32-bit x position in millimetres, little-endian |
| 8–11 | Signed 32-bit y position in millimetres, little-endian |
| 12–13 | Signed 16-bit heading in centidegrees, little-endian |
| 14 | Validity flags; bit0 must be set |
| 15 | XOR checksum of bytes 0 through 14 |

Pose frame conventions:
- The firmware resets the origin to (0,0) when a new exploration session starts.
- Heading 0° points along +X; positive angles rotate counter-clockwise.
- Heading must be within -180°..+180°. Coordinates are local to the current map frame and must not jump or rewind during a session.
- Publish at 10 Hz or faster. The phone rejects pose readings older than 600 ms.

## Safety policy implemented by the app

Movement is blocked when any of these apply: missing/stale telemetry or pose; missing any required range/cliff sensor; floor distance at either cliff sensor at or above 145 mm; bumper or emergency stop active; motor driver not ready; base fault; battery at or below 8%; person detected in the forward camera path; camera unavailable; map has no safe unexplored frontier; or BLE link is not ready.

The app uses short drive leases, slow wheel targets, range-based retreat/turn behaviour, a bounded 250 mm occupancy grid, and room hypotheses from the phone camera. It can suggest that an area looks like a kitchen, bedroom, bathroom, living room, dining area, office, or hallway when visual evidence supports that guess. This is not full SLAM and not a guarantee of room recognition or collision-free motion.

## Commissioning checklist

1. Test BLE discovery and notifications with the wheels disconnected.
2. Verify command checksums and each telemetry flag with injected values.
3. Verify each cliff sensor reports unsafe when lifted over a real edge; do not begin with a staircase.
4. Test hardware emergency stop, bumper, low battery, MCU reset, and BLE disconnection independently of the phone.
5. Raise or immobilize the wheels for polarity and lease-watchdog tests.
6. Start real motion in a taped, level test area with a person holding the physical emergency stop.
7. Only then try slow autonomous movement; supervised testing is required before use around furniture, pets, people, or stairs.

## Voice commands

- “اتصلي بالقاعدة” — scan/connect only; wheels remain stopped.
- “استكشفي المنزل بالقاعدة” — request sensor-gated autonomous movement after a compatible base is connected.
- “أوقفي القاعدة” — stop wheel commands.
- “افصلي القاعدة” — stop, then disconnect.
- “حالة القاعدة” — read connection status.
- “استعادة الحركة” — attempts to clear a latched stop only when telemetry is fresh and safe.

The phone-side integration can be developed before the hardware is purchased. Real movement, cliff response, braking distance, odometry quality, and battery behaviour cannot be validated until the actual base and firmware exist.
