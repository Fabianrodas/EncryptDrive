### Downloads

- `EncryptDrive-<version>-Setup.exe` — installer for the current Windows user.
- `EncryptDrive-<version>-Windows-Portable.zip` — unzip anywhere (also a USB
  drive) and run `EncryptDrive\EncryptDrive.exe`.
- `SHA256SUMS.txt` — checksums of both files.

Neither edition needs Java. Installing or uninstalling EncryptDrive never
creates, moves, or deletes a vault: you choose vault locations inside the app.

### Verify your download

In PowerShell, in the download folder:

    Get-FileHash .\EncryptDrive-<version>-Setup.exe -Algorithm SHA256

The hash must equal the line for that file in `SHA256SUMS.txt` (letter case
does not matter). `certutil -hashfile <file> SHA256` works too.

### Unknown publisher warning

This release is not code-signed. Windows SmartScreen may say "Windows protected
your PC" and show an unknown publisher. Check the SHA-256 hash first; then
choose **More info → Run anyway**.
