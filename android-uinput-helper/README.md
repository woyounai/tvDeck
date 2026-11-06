# TVADB Virtual Mouse Helper

This directory contains TVADB's install-free Android virtual-mouse helper. The
helper is pushed to `/data/local/tmp` and runs only for the active ADB session.
It first tries `/dev/uhid` with a standard HID mouse report descriptor so the
kernel handles it like a physical HID mouse. If UHID is missing or denied, it
falls back to a relative mouse through `/dev/uinput`.

The UHID device publishes the stable physical port `tvadb:virtual-mouse`.
On Android 15 and later, the companion `MouseDisplayAssociation` app-process
helper associates this port with the primary display before the device is
created. Without that association, Android may recognize the mouse but route
its cursor to `DisplayId -1`.

The helper is intentionally libc-free and uses only Linux syscalls. This keeps
the bundled files small and avoids dependencies on a television's Android or
bionic version.

It reads a newline-delimited control protocol from standard input:

```text
M <dx> <dy>   relative movement
C [button]    click (1 left, 2 right, 4 middle)
D <button>    button down
U <button>    button up
W <amount>    vertical wheel
H <x> <y>    home and slowly calibrate to the raw center offsets; emits TVADB_MOUSE_CENTERED
Q             stop
```

TVADB opens a duplex ADB exec service running the bundled helper directly,
reads its READY line, then writes these reports to standard input. No FIFO or
background keep-alive process is needed for the bundled helper. This matters
on Sony Android 9, whose shell can use UHID but cannot create the old FIFO.
The separate AOSP system uinput compatibility backend retains its legacy FIFO
transport. CLI probes must use a duplex command such as `adb shell -T`; a
read-only `adb exec-out` probe does not demonstrate working input delivery.

Build all supported Android ABIs:

```sh
./scripts/build-android-uinput-helper.sh
```

The generated files are placed in `iosadb/Resources/AndroidUInput/`.
