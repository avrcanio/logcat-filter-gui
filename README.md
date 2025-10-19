# Logcat Filter GUI

A Kotlin/Compose for Desktop application that makes it easy to browse, filter, and manage Android logcat output without touching the command line. Connect any ADB-accessible device or emulator, stream logs in real time, filter them on the fly, and export or act on packages directly from the UI.

## Features

- **Device picker** with persisted preference for the last-used device.
- **Live logcat streaming** with automatic scroll, 1 000-line rolling buffer, and colored severity levels.
- **Powerful filtering** by minimum log level, tag, message text, and PID.
- **PID utilities** – fetch the PID for a package from the device shell.
- **Clipboard-friendly log list** – right-click > Select All support plus copy fallback.
- **CSV export** of the currently filtered logs.
- **Package actions** targeting the selected device: `pm clear`, `adb uninstall`, and APK install with an optional `-r` (reinstall) toggle. The app remembers your last APK path to speed up repeated installs.
- **Logcat maintenance shortcuts** – start/stop streaming and `logcat -c`.
- **Configuration** stored at `~/.logcat-filter/config.json` for easy editing or syncing.

## Requirements

- JDK 17 (the project targets JVM toolchain 17).
- Android SDK Platform Tools (`adb`) available on your system.
- macOS, Windows, or Linux with a desktop environment supported by Compose for Desktop.

## Getting Started

1. **Clone the project**
   ```bash
   git clone https://github.com/your-org/logcat-filter-gui.git
   cd logcat-filter-gui
   ```
2. **Ensure ADB is reachable**
   - Add `adb` to your `PATH`, or
   - Set `LOGCAT_ADB` (and optionally `ADB_SERVER_SOCKET`) as environment variables or JVM system properties.  
     The app also looks for common Windows installation paths automatically.
3. **Build**
   ```bash
   ./gradlew build
   ```
   *(Use `gradlew.bat build` on Windows.)*
4. **Run**
   ```bash
   ./gradlew run
   ```
   This launches the Compose Desktop application. Packaging tasks (`./gradlew package`, native bundles, etc.) are also available via the Compose plugin.

## Usage

1. **Pick a device**
   - On launch, the Device Picker lists `adb devices -l`.  
   - Select a device and press **Use this device (Save)** to store the choice in `~/.logcat-filter/config.json`.
2. **Stream logs**
   - Inside the Logcat screen, click **Start** to begin streaming.  
   - Use **Stop** and **Clear** as needed, or enable/disable **Auto-scroll**.
3. **Filter output**
   - Adjust the minimum level, tag, message, and PID filters.  
   - Use **Fetch PID** to populate the PID field from a package name.
4. **Export & copy**
   - Right-click the log list to select/copy everything.  
   - Use **Export CSV** to save the currently filtered log lines.
5. **Package tools**
   - Enter a package name and use **Clear data** (`adb shell pm clear`) or **Uninstall**.
   - Browse for an APK to install; enable **Reinstall (-r)** to keep existing app data. The file picker remembers the last APK you chose.

## Tips

- If the app cannot find `adb`, it will show an error in the picker along with the raw command output.
- Exported CSV files include the raw logcat line fields (timestamp, PID, TID, level, tag, message).
- The log buffer keeps a rolling 1 000 lines to stay responsive. Export before clearing if you need a longer history.

## Contributing

Pull requests and issue reports are welcome! Please run `./gradlew build` locally before opening a PR to ensure the project compiles.

## License

MIT or your preferred license. Update this section to match the licensing for your project.
