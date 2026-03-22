# MyScreen CUT&USE Ploter PRO 11" v2 - Reverse Engineering Findings

## Device Hardware
- **SoC**: Rockchip RK3126C (ARM Cortex-A7 quad-core)
- **OS**: Android 7.1 (build: EM3128-6004-ANDROID7.1-V18-20220629)
- **USB ID**: VID_2207 PID_0006, Serial: MN21K395AF
- **Screen**: 1024x600 pixels
- **ADB**: Enabled, device accessible as `MN21K395AF`

## Serial Communication
- **Port**: `/dev/ttyS1` (internal UART, RK serial driver)
- **Baud Rate**: 38400
- **Config**: 8N1 (cs8, no parity, 1 stop bit), no flow control, raw mode
- **Permissions**: `crw-rw-rw-` (world read/write)
- **Secondary port**: `/dev/ttyS0` also exists (unused by app)
- **JNI Library**: `libserial_port.so` - standard `android_serialport_api`
- **Open flags**: `0x2` (O_RDWR)

## Firmware
- **Version**: V1.3220916C (reported via serial query)
- **Version key for protocol branching**: `f20045x` = `"3220916C"` (substring after "V1.")
  - Compared against `"230215"`: `"3220916C" > "230215"` → uses newer data packet format
- **MCU**: Separate microcontroller connected via UART to RK3126C
- **Firmware suffix "C"**: Indicates SHA-256 checksum mode is active (`u1()` returns true)

## Plotter App (Original)
- **Package**: `com.plotter.pea.lamelplotterapp`
- **App Version**: v6.4.6-build120
- **Main Activity**: `com.lamel.cutanduse.activities.ActivityStartup`
- **APK Path**: `/data/app/com.plotter.pea.lamelplotterapp-1/base.apk`
- **APK Size**: ~72 MB
- **Database**: `/data/user/0/com.plotter.pea.lamelplotterapp/databases/cutsettings.db`
- **CU-ID**: MN21K395AF (matches USB serial)
- **SerialPortSDK class**: `l0.s` (obfuscated) - singleton, manages all serial communication
- **Initialization**: `s.I1("hsznqmji", 2000)` called from `MyApplication.onCreate()`

## Serial Protocol

### Packet Structure
```
[HEADER][TYPE][CMD_HI CMD_LO][LEN_HI LEN_LO][DATA...][CHK1 CHK2][0D 0A]
 5AA5    XX    XX     XX       XX     XX       ...      XX   XX   0D  0A
```

### Header
- Always `5AA5` (2 bytes)

### Command Types
- `AA` = Query (read a value from device)
- `BB` = Set (write a value to device)
- `CC` = Cut/Data command (send cut data, stop cut, etc.)

### Checksum Calculation (SHA-256 based - CRITICAL!)

**The MCU silently rejects packets with incorrect checksums.** The checksum is 2 bytes:

1. Build int array of command bytes: `[type, cmdHi, cmdLo, lenHi, lenLo, data...]`
2. Append ASCII bytes of secret key `"hsznqmji"` (8 bytes: `68 73 7A 6E 71 6D 6A 69`)
3. SHA-256 hash the concatenated byte array → 32 bytes
4. `chk1 = sum(hash[0..15]) & 0xFF`
5. `chk2 = sum(hash[16..31]) & 0xFF`

**Example**: Version query `[0xAA, 0x00, 0x21, 0x00, 0x00]` + `"hsznqmji"` → SHA-256 → chk = `00 16`
Full packet: `5AA5 AA 0021 0000 0016 0D0A`

This applies to AA (query) and BB (set) commands, and also to the **cut header packet** (CC 0130).
Data chunk packets (CC xx30) use a **simple byte sum** checksum instead (see Cut Data Protocol below).

### Known Query Commands (Type AA)

All queried successfully with valid SHA-256 checksums.

| Cmd | Response Data (hex) | Interpretation |
|-----|-------------------|----------------|
| 0x10 | `00 00 64 00` | 100 - pressure/force setting |
| 0x11 | `00 00 A0 00` | 160 - media width (mm?) |
| 0x12 | `00 00 D0 07 D0 07` | 2000, 2000 - working area XY (HPGL units, 40u/mm = 50x50mm) |
| 0x13 | `00 00 36 FF D4 05 4D 46 39 34 12 79 08 43 00 00 00 00` | Device info string |
| 0x18 | `00 00 01` | Auto mode flag |
| 0x19 | `00 00 CF 00` | 207 - print width setting |
| 0x20 | `00 00` | Status/mode |
| 0x21 | `00 00 56 31 2E 33 32 32 30 39 31 36 43 00` | **"V1.3220916C"** firmware |
| 0x22 | `00 00 00` | 0 |
| 0x23 | `00 00 32 32 32 32` | "2222" - calibration values |
| 0x25 | `00 00 00 00 00 00 00 00 00 00` | Current position (all zeros = home) |
| 0x34 | `00 00 13 01` | 19, 1 - speed setting |
| 0x36 | `00 00 19 00 01` | 25, 0, 1 - cut mode settings |
| 0x38 | `00 00 01 00` | Media sensor status |
| 0x39 | `00 00 02` | 2 |
| 0x3A | `00 00 00` | 0 |
| 0x3B | `00 00 28 00` | 40 - cut distance |
| 0x3C | `00 00 06 00` | 6 - blade offset |
| 0x3E | `00 00 1E 00 46 14` | 30, 0, 70, 20 - wide format settings |
| 0x4C | `00 04` | 4 |
| 0x53 | `00 04` | 4 |

### Known Set Commands (Type BB)

| Cmd | Data Len | Decompiled Method | Description |
|-----|----------|-------------------|-------------|
| 0x10 | 2 | `n0(int, k)` | Set pressure/force |
| 0x11 | 2 | `l(m<Integer>)` | Query/set param |
| 0x12 | 4 | `j(Point, k)` | **Set working area XY dimensions** (NOT move!) |
| 0x18 | 1 | `C(boolean, k)` | Set auto mode on/off |
| 0x19 | 2 | `c0(int, k)` | Set print width |
| 0x1B | 0 | `j0(k)` | **Reset knife** (home the blade) |
| 0x22 | 1 | `E(boolean, k)` | Set auto detection |
| 0x23 | 4 | `h0(int,int,int,int,k)` | Set calibration (4 values) |
| 0x24 | 1 | `C(boolean, k)` | Enable/disable auto (value 1=on, 2=off) |
| 0x25 | 2 | `L(int, k)` | Set blade offset |
| 0x26 | 0 | `p0(k)` | Go to home position |
| 0x34 | 2 | `Q(int, k)` | Set auto speed |
| 0x36 | 3 | `o0(int,int,int,k)` | Set cut mode |
| 0x38 | 2 | | Set media sensor param |
| 0x3A | 1 | | Set param |
| 0x3B | 2 | `f(int, k)` | Set cut distance |
| 0x3C | 2 | `p(int, k)` | Set cut margin |
| 0x3E | 4 | | Set wide format |
| 0x47 | 0 | `g0(k)` | Unknown (init/reset?) |
| 0x53 | 4 | | Set param |

### Cut/Data Commands (Type CC)

| Cmd | Description |
|-----|-------------|
| 0x0040 | **Stop/cancel cut** |
| 0x0130 | **Cut data header** (initiates a cut job, 20 bytes payload) |
| 0xXX30 | **Cut data chunk** (carries HP-GL payload, variable length) |

## Cut Data Protocol (Sending Cut Jobs)

Cutting is a multi-packet sequence:

### Step 1: Header Packet (CC 0130)
```
5AA5 CC 0130 1400 [data_len_LE32 (4 bytes)] [pad (16 bytes)] [SHA256_chk (2 bytes)] 0D0A
```
- `CC` = cut type, `0130` = header command, `1400` = 20 bytes of data
- `data_len_LE32`: total HP-GL payload length as little-endian 32-bit integer
- `pad`: 16 bytes, first 4 are ASCII `"test"`, rest are `0x00`
- Checksum: **SHA-256 based** (same as AA/BB commands)
- MCU responds with `5AA5CC01300200...` (2-byte status, 0x00 = OK)

### Step 2: Data Chunk Packets (CC xx30)
```
5AA5 CC [SEQ] 30 [chunk_len_LO] [chunk_len_HI] [data...] [simple_chk] 0D0A
```
- `SEQ`: sequence number - `02` for first chunk, incrementing, `00` for LAST chunk
- `30`: sub-command indicating data chunk
- `chunk_len`: byte length of the chunk as little-endian 16-bit
- `data`: raw HP-GL bytes encoded as hex
- Checksum: **simple byte sum** of `CC + SEQ + 30 + lenLo + lenHi + data`, masked to 0xFF
  - (Only 1 byte, NOT SHA-256 - this applies when firmware version >= "230215")
- Chunk size: up to 512 bytes (1024 hex chars) per packet
- MCU responds to each chunk

### HP-GL Command Format

The cut data payload is an HP-GL (Hewlett-Packard Graphics Language) string:

```
IN;PA;VS<speed>;FS<force>;PU<x>,<y>;PD<x>,<y>;PD<x>,<y>;...PU<x>,<y>;
```

| Command | Meaning |
|---------|---------|
| `IN;` | Initialize |
| `PA;` | Plot Absolute mode |
| `VS<n>;` | Velocity Set (cutting speed, 1-10) |
| `FS<n>;` | Force Set (blade pressure, 1-10) |
| `PU<x>,<y>;` | Pen Up - move WITHOUT cutting (also feeds paper to reach Y position) |
| `PD<x>,<y>;` | Pen Down - move WITH cutting |

### Coordinate System
- **Units**: 0.025mm per unit (40 units = 1mm), standard HP-GL resolution (1016 DPI)
- **HP-GL X axis** (in firmware): Paper roller (feed direction)
- **HP-GL Y axis** (in firmware): Cutting head (horizontal movement, left-right)
- **Axis swap required**: Standard HPGL files use X=horizontal, Y=vertical. This plotter's
  firmware interprets X=roller, Y=head. The software must swap X/Y coordinates before sending.
- **Roller direction**: Negative X = pulls paper inward (loads from back of device).
  Positive X = pushes paper outward. The feed offset is applied as negative X.
- **Paper insertion**: Paper is inserted from the **back** of the device. The roller pulls
  it inward (toward the front) during loading and cutting.
- **Trailing PU0,0**: Standard HPGL files end with `PU0,0;` (return to origin) which would
  eject the paper. The software strips this before sending.
- **A4 paper**: 8400 x 11880 units (210mm x 297mm)
- **Default working area from query 0x12**: 2000 x 2000 units (50mm x 50mm)
- **Do NOT send BB 0x12** (set working area) — this command corrupts MCU state and causes
  subsequent cut headers to fail. The plotter accepts coordinates beyond the reported area.

### Important: Command 0x12 (BB type) is NOT a move command!
`BB 0012 0400 [X_LE16] [Y_LE16]` **sets the working area dimensions**, not the head position.
Sending this with large values will NOT feed paper - it reconfigures the cutting area and can
cause the head to move erratically. Use HP-GL PU commands within cut data to position the head.

## SDK Class Map (Decompiled)

| Obfuscated | Original | Purpose |
|------------|----------|---------|
| `l0.s` | `SerialPortSDK` | Singleton serial port manager |
| `l0.s.m` | Reader thread | Background thread reading serial responses |
| `l0.k` | Callback | Success/error callback interface |
| `l0.m<T>` | ValueCallback | Callback with typed return value |
| `l0.p` | PacketBuilder | Builds command strings with SHA-256 checksum |
| `l0.d` | PlotCommand | HP-GL command (PU/PD/VS/FS) with coordinate lists |
| `B.C0517a` | HexUtils | Hex encode/decode, string chunking, DES encryption |
| `B.C0519c` | SHA256 | `MessageDigest.getInstance("SHA-256")` wrapper |

### Key SerialPortSDK Methods

| Method | Command | Description |
|--------|---------|-------------|
| `M(m<String>)` | AA 0021 | Query firmware version |
| `j0(k)` | BB 001B | Reset knife to home |
| `j(Point, k)` | BB 0012 | Set working area (NOT move) |
| `p0(k)` | BB 0026 | Go to home position |
| `Q(int, k)` | BB 0034 | Set auto speed |
| `c0(int, k)` | BB 0019 | Set print width |
| `f(int, k)` | BB 003B | Set cut distance |
| `h(k)` | CC 0040 | Stop/cancel current cut |
| `D(...)` / `K(...)` | CC 0130+chunks | Send cut data (HP-GL) |
| `k1()` | (internal) | Init: starts reader thread + queries version |
| `B1(String)` | (internal) | Writes hex string packet to serial port |

## Custom Control App (PlotterCtl)

Built at `C:\cutter\plotterctl\`:
- Connects to `/dev/ttyS1` at 38400 baud using the original `libserial_port.so`
- Implements SHA-256 checksum protocol correctly
- Features: Connect, Query Version, Reset Knife, Query All Params, **Cut Circle on A4**
- Cut Circle: feeds paper via HP-GL PU command, then cuts 80mm diameter circle
- Build: `cd /c/cutter/plotterctl && bash build.sh`
- Deploy: `adb push plotterctl.apk /sdcard/ && adb shell pm install -r /sdcard/plotterctl.apk`
- Keystore: `plotterctl/debug.keystore` (reused across builds to avoid reinstall)

## Important Notes
- SELinux is in **permissive** mode (allows serial port access from any app)
- The MCU controls the stepper motors and knife; Android SoC sends high-level commands
- The original app requires POS registration/login (cloud-based licensing) - our custom app bypasses this
- Serial port baud rate must be set via proper termios ioctl (the JNI native library handles this)
- `InputStream.available()` returns 0 on Android serial FileInputStream - use blocking `read()` on a timeout thread instead
- The MCU does NOT auto-send data on port open; the app must explicitly query after connecting
