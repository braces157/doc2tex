# doc2TeX for Windows x64

This ZIP includes the built interface and Eclipse Temurin 21.0.12.1+1 JRE. You do not need Java, Node, Maven, administrator rights, or a frontend build to run it. Use a 64-bit Windows desktop and its built-in Windows PowerShell 5.1. This is a portable ZIP launcher, not an MSI installer. It is unsigned; verify the ZIP's published SHA-256 and obtain it from the project release/artifact page. Do not bypass organization policy to run it.

1. Extract the **entire** ZIP into a new app folder. Do not launch from inside the ZIP.
2. Read the network requirements below, then double-click **Install tools.cmd**. Type `YES` to download the pinned converters and fonts. Cancel starts no downloads. You can launch without them to inspect Setup diagnostics.
3. Double-click **Launch doc2TeX.cmd**. It opens `http://127.0.0.1:18749` in your browser. A second launch reopens the existing app. Keep this browser origin for access to existing drafts.
4. Try the sample, convert, inspect Review, and download the ZIP. Build PDF is a separate action.
5. Run **Stop doc2TeX.cmd** before upgrading, backing up or removing the app. Closing the browser alone keeps the local server running.

## Downloads and offline use

Launch starts no downloads. The explicit tool installer fetches Pandoc 3.12.1 (GPL-2.0-or-later), Tectonic 0.17.0 (MIT), and Noto fonts/licenses (OFL-1.1) from pinned GitHub URLs, verifies SHA-256, and stores them under your data folder. These binaries/fonts are separately downloaded, not redistributed in this ZIP. Read `legal/THIRD_PARTY_NOTICES.md` and the upstream terms before redistributing an installation containing them.

The first PDF build can separately fetch TeX packages from Tectonic servers. Connect before building and allow those downloads through your proxy/firewall. Conversion and source/ZIP export do not require TeX package downloads after tool installation. Previously cached packages do not guarantee that a new document works offline. Network/cache failures have separate recovery guidance from LaTeX source errors. No fully offline installation claim is made.

## Projects, settings and backups

The default data folder is `%LOCALAPPDATA%\doc2TeX`: `jobs` contains original DOCX files, immutable source/assets, versions and review/layout settings; `.tools` contains downloaded converters/fonts; `settings.json` retains the local port; `logs` contains startup output. Back up this entire folder while the server is stopped. Browser drafts remain in that browser's local storage and are not part of a filesystem backup; download unsaved text first.

For a different data location, set `DOC2TEX_HOME` to an absolute directory **outside** the app installation, consistently for launch/install/stop. Leave its value unchanged across upgrades. Do not run two servers against one data folder. The Java process holds a lifetime file lock before opening projects, including if launched directly. A launcher lock also prevents concurrent startup/tool installation. The package binds to `127.0.0.1`; it supplies no LAN/hosted service.

To migrate a development checkout, stop both servers, back up both locations and copy the existing `.data/jobs` contents into the packaged `jobs` folder. Never overwrite an existing destination job. No automatic migration or deletion of a development checkout is performed.

## Upgrade and uninstall

Stop the app and back up the data folder. Extract the new ZIP into a separate app folder, then run its launcher using the same data folder and port. Projects, versions, review/layout choices, downloaded tools/fonts and `settings.json` remain outside the replaced installation. Inspect Saved versions and export a project before deleting the old app folder. Do not downgrade a data format without a compatible backup. There is no automatic updater or silent data migration.

Uninstall means stopping the app and deleting the extracted app folder. This **retains all projects, versions, settings and downloaded tools** in Local AppData, and retains browser drafts. Remove that data folder and browser site storage yourself only after backing up anything needed. No uninstall script deletes projects.

## Recovery

- Missing/unusable converter or font: stop the app, rerun Install tools.cmd, then relaunch and open **Setup diagnostics**. This re-extracts checksum-verified converters even when a broken executable already exists; fonts are checked/replaced by checksum. For a partially failed download, reconnect and rerun; do not delete project storage. Development checkouts can use `scripts/setup-tools.ps1 -Repair`.
- Port busy: stop the existing app using its app/data folder. If another program owns the port, choose a free integer 1024–65535 in `settings.json` while stopped. A changed browser origin has separate drafts; preserve unsaved text first.
- Startup failure: inspect `logs/startup.log` and `logs/startup-error.log`. Never delete `.instance.lock` while a server is running; a stale instance JSON file is verified against the live API rather than trusted by PID alone.
- PDF network/cache failure: reconnect/check proxy, retry Build PDF, then inspect the compiler log if needed. A genuine TeX error requires editing the saved source. Neither failure removes source or ZIP exports.

Runtime notices remain under `runtime/legal`; corresponding upstream OpenJDK source is included in `legal/OpenJDK21U-jdk-sources_21.0.12.1_1.tar.gz`. The runtime archive is checksum-pinned and extracted without modifying its contents. Other notices are under `legal/` and in the application JAR.

## Clean-machine verification

In a fresh x64 Windows VM with neither Java nor Node installed, open Windows PowerShell **in the extracted app folder** and run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\verification\scripts\test-windows-package.ps1 -PackageDirectory . -EvidenceDirectory "$env:TEMP\doc2tex-vm-check" -DownloadTools
```

Use a new evidence directory if that one already exists. This explicitly downloads tools/fonts, creates isolated synthetic projects and app copies, checks launch/conversion/export/history/replacement/uninstall retention, then stops the synthetic app. It does not delete existing folders. Copy the evidence folder out of a disposable VM/Sandbox before closing it. Its result verifies the package behavior; record the clean VM identity/Windows version separately and also perform the manual sample PDF/offline-first-build check. A restricted PATH test on a development computer alone does not establish clean-machine support.
