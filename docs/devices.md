# Compatible devices

The phone check reports requirements and optional measurements. The owner
assigns **Verified** after the functional pass on a named physical phone.
Record the exact model or regional variant and the Android version when known.
The [build sheet](build-sheet.md#open-decisions-tracked) defines the compatibility tiers.

## Verified

| Device | Android (SDK) | RAM / primary free | SD card | Device check | Evidence |
| --- | --- | --- | --- | --- | --- |

## Expected

These two phones were ordered on 2026-11-26. Memory and capacity are from the
ordered configurations. Run Phone check on arrival to confirm the requirements.

| Device | Android (SDK) | RAM / primary free | SD card | Device check | Evidence |
| --- | --- | --- | --- | --- | --- |
| Samsung Galaxy S24+ (256 GB) | Not measured | 12 GB advertised / not measured | no SD slot | Expected, pending Phone check | Not tested on physical hardware |
| Google Pixel 9a (256 GB) | Not measured | 8 GB advertised / not measured | no SD slot | Expected, pending Phone check | Not tested on physical hardware |

## Not supported

Record the failed requirement in the Device check column.

| Device | Android (SDK) | RAM / primary free | SD card | Device check | Evidence |
| --- | --- | --- | --- | --- | --- |

## Emulator evidence

| Device | Android (SDK) | RAM / primary free | SD card | Device check | Evidence |
| --- | --- | --- | --- | --- | --- |
| Google sdk_gphone64_x86_64 | 17 (37) | 4.10 GB (8.00 GB advertised) / 1.72 GB | volume 1: 0.53 GB | emulator — not a phone | Tested on emulator (device facts) |

This test read the emulator’s reported capabilities and storage. The emulator
advertises translated ARM support. Phone performance and compatibility
require a physical-device pass.

## Requirements

| Requirement | Where BASTION checks it |
| --- | --- |
| Android 10 or later (SDK 29) | [APK minimum SDK](../android/app/build.gradle.kts) and [phone check](../android/app/src/main/java/com/bastion/terminal/hw/DeviceCheck.kt) |
| 64-bit ARM (`arm64-v8a`) | [APK ABI splits](../android/app/build.gradle.kts) and [phone check](../android/app/src/main/java/com/bastion/terminal/hw/DeviceCheck.kt) |
| GPS provider and Bluetooth LE | [Capability and verdict checks](../android/app/src/main/java/com/bastion/terminal/hw/DeviceCheck.kt) |
| Free phone storage for the model and its first-load cache | [Phone check](../android/app/src/main/java/com/bastion/terminal/hw/DeviceCheck.kt) uses the first-load reserve in [LocalAi](../android/app/src/main/java/com/bastion/terminal/ai/LocalAi.kt). With an imported model, the phone check evaluates the reserve for that file. With no imported model, it uses the reference E2B model (2.59 GB) and its reserve (0.86 GB). Leave additional storage for the references and maps you plan to carry. |
| At least 8 GB RAM (12 GB target for Gemma 4 E4B) | [Memory verdict](../android/app/src/main/java/com/bastion/terminal/hw/DeviceCheck.kt) uses advertised RAM when Android reports it and otherwise uses reported total RAM. |

OpenCL loadability is a capability check. The model run shows whether a
backend works. A microSD slot is optional.

## Verified functional pass

1. Open **Home → Phone check**. Record its facts and requirement verdict.
2. Import the intended local model. Confirm that it loads and answers a
   representative question with its source available offline. Record model
   and backend, load time, prompt and decode tokens per second, and peak app
   RAM. Record battery change per hour with the workload and timed interval.
3. Open an offline ZIM, search it, and open a result. Record the ZIM and search
   time.
4. Open the offline map, check a real GPS fix, and compute and follow a route
   on a state pack. Record the pack and route computation time.
5. Share a text message to Meshtastic and confirm that an incoming message
   appears in BASTION’s inbox. Record the physical radios and firmware used.
6. Install and open ATAK-CIV, and check the device-owner setup on the dedicated
   phone using the [USB provisioning workflow](../tools/provision.ps1).
7. On a device with a slot, mount a card and verify that BASTION can read a map
   pack or ZIM placed on it. For the Galaxy S24+ and Pixel 9a, record
   **no SD slot**.

## Export a row

On the device, open **Home → Phone check**. Run **Measure** if you need the
optional timings, then use **Save report**. Connect one authorized USB device
or local emulator and run this from the repository root.

```powershell
.\tools\device-report.ps1
```

Use `-AdbPath` if platform-tools is outside the usual SDK directories and
off PATH. The command reads the saved JSON and prints a row for the list.
Keep the summary text for measurements and checklist evidence.
