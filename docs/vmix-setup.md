# vMix setup guide

StreamCam is a video-only RTSP source. vMix pulls it as a normal stream input.

## 1. Enable vMix Web Controller (for the bus border)

The red/green border in StreamCam reads vMix's bus state via its Web Controller API.

1. In vMix: **Settings → Web Controller**.
2. Enable it; leave the port at **8088** (StreamCam hardcodes this).
3. Make sure the PC firewall allows inbound TCP 8088 on the LAN.

This is optional. Without it, streaming still works; you just lose the border.

## 2. Add the StreamCam input in vMix

1. **Add Input → Stream/SRT** (or "Stream" depending on vMix version).
2. Type: **RTSP** (RTSP over TCP preferred; UDP also works).
3. URL: `rtsp://<phone-ip>:8554/stream` — the IP and port are shown in the StreamCam UI.
4. vMix should connect and show ~29.97 fps at ~8 Mb/s.

StreamCam uses video-only H.264 Baseline 4.0, 1920×1080, 30 fps, 1 s GOP. TCP is smoother in vMix in our testing.

## 3. Optional: bus border

1. In StreamCam, open the **Stream** tab.
2. Enter the PC's IP (e.g. `192.168.0.100`) into **vMix host (Web Controller :8088)**.
3. Start streaming. When the input is on **Program/Live**, the preview gets a **red** border; on **Preview**, a **green** border. No border otherwise.

The match is by input title (vMix names RTSP inputs after the URL by default). If no border appears, check the logcat line `vmix host=… reachable but no input matched candidates=… inputs=…` — it lists the real input titles so you can see why.

## 4. Network notes

- Phone and PC must be on the same LAN. StreamCam halts on Wi-Fi loss and auto-restarts when Wi-Fi returns.
- 5 GHz Wi-Fi is recommended; the phone holds a `FULL_LOW_LATENCY` Wi-Fi lock while streaming.
- The phone's DHCP lease can change; re-check the URL in the StreamCam UI after a reconnect.
- Do not leave `adb reverse tcp:8554 tcp:8554` active while starting the stream — it squats the device port (`EADDRINUSE`).

## 5. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `EADDRINUSE` on start | Port 8554 already bound (often by an adb reverse). Run `adb reverse --remove-all`, then start again. |
| vMix shows no video / connection refused | Wrong IP or port, or phone not on the same LAN. Check the URL in the StreamCam UI. |
| vMix says "Invalid Stream Control URI" | Upstream RTSP-Server bug; the vendored copy in StreamCam fixes it. Use the supplied build. |
| Stream stops when you background the app | By design: `onStop` stops transport and closes the camera (no foreground service in MVP). |
| Focus hunts during a live shot | Switch to **Manual focus** and lock the distance, or use tap-to-focus and unlock when done. |
| No bus border | Empty vMix host, Web Controller disabled, or input title doesn't contain the URL. Check logcat. |
| Dropped frames | Check Wi-Fi RSSI/link speed in the `METRICS` line; thermal degrade reduces bitrate at MODERATE/SEVERE. |

## 6. Measured limits

- Single video stream, 1080p30, ~8 Mb/s. Wi-Fi 65 Mbps link has ~8× headroom.
- Capture-to-encode latency ~122 ms p50 (device-side; not glass-to-glass).
- Phone stays awake while foregrounded; pressing power sleeps it (stream stops).