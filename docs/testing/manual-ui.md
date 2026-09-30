# Manual UI checklist

Run the app with `mvn javafx:run` (or the portable build). Repeat every section
twice: once at the minimum window size (1000x600, the size the app opens with)
and once maximized (title bar `□` button). `UiLayoutTest` checks every screen
automatically at 1000x600 and 1920x1040 when a desktop session is available.

## Window chrome (every screen)

- [ ] Drag the window by the title bar.
- [ ] `□` maximizes; `□` again restores the previous size.
- [ ] While maximized, dragging the title bar restores the window under the cursor.
- [ ] `−` minimizes; restoring from the taskbar shows the same screen.
- [ ] `×` closes the app. Reopening a vault afterwards works (the vault lock was released).

## Vault selection

- [ ] A fresh start opens Vault Selection (not Login), in **Open vault** mode.
- [ ] **Create vault** / **Open vault** switch the form; exactly one mode is highlighted.

### Create

- [ ] Empty name, invalid name (`a:b`, `CON`, `name.`), missing location, a password
      under 12 characters, and mismatched passwords each show an error and create nothing.
- [ ] **Browse**, then cancel the folder dialog: the location stays unchanged.
- [ ] **Create vault** shows the warning "EncryptDrive cannot recover a lost vault
      password. If you lose it, the vault cannot be unlocked."
- [ ] **Cancel** (or `×` / Esc) closes the warning, creates no folder, and keeps the form filled.
- [ ] **Continue** creates `<location>/<name>` containing only `.encryptdrive/` and
      `storage/`, then opens Register.
- [ ] Creating into a non-empty folder with the same name shows an error and changes nothing.

### Open

- [ ] Opening without a folder or without a password shows an error.
- [ ] A folder that is not a vault shows "The selected folder is not an EncryptDrive vault."
- [ ] A wrong vault password shows the generic "The vault could not be unlocked" message.
- [ ] The correct password opens Login.
- [ ] With the vault open in one EncryptDrive process, opening it from a second process
      shows "This vault is already open in another EncryptDrive process."

## Register and log in

- [ ] Both screens show the open vault's name with a **Close vault** link that returns to
      Vault Selection and closes the vault.
- [ ] Register shows "EncryptDrive cannot recover a lost account password."
- [ ] Registration errors (empty fields, username under 3 characters, password under 8,
      mismatch, username taken in any letter case) are shown and create nothing.
- [ ] After registering, the popup's `×` stays on Register; **Go to Log In** opens Login.
- [ ] A wrong password or unknown username shows exactly "Invalid username or password."
- [ ] If the vault data was restored from a backup, Login shows the recovery notice once.

## Workspace

- [ ] The sidebar lists Overview, Files, Trash, Personal Profile, Vault Settings, then the
      user card, **Log Out**, and **Close Vault**; the current view is highlighted.
- [ ] Overview shows the vault path and real counts: files, plaintext size, encrypted
      storage, items in trash. **Open Files** opens Files.
- [ ] Files: **New Folder** (Cancel creates nothing; duplicate or invalid names are
      rejected), double-click a folder to enter it, breadcrumbs go back.
- [ ] Files: **Import Files** with several files shows progress, disables the toolbar and
      sidebar while running, and leaves the original files in place. Cancelling the file
      dialog does nothing.
- [ ] Files: double-clicking a file only suggests Export; no temporary copy is created.
- [ ] Files: **Export** first shows "Exported files are not encrypted by EncryptDrive at the
      selected destination." Cancel there, or in the file/folder dialog, writes nothing.
      Exporting into a folder with existing items asks before replacing them.
- [ ] Files: **Move to Trash** removes the items from the folder view.
- [ ] Trash: **Restore** puts items back (into the top-level folder if the original folder
      is gone, renamed if the name is taken). **Permanently Delete** names the items, says
      recovery will no longer be possible, and does nothing on Cancel.
- [ ] Personal Profile: wrong current password, short or mismatched new password, and a
      successful change each show the expected message.
- [ ] Vault Settings: location, vault ID, format version, created date, the synced-folder
      warning, vault password change (12+ characters), and **Close Vault**.
- [ ] **Log Out** returns to Login with the vault still open; **Close Vault** returns to
      Vault Selection.

## Acceptance matrix

Automated coverage runs in `mvn clean verify`; the rest needs a person or hardware.

| # | Check | Covered by |
|---|---|---|
| 1 | Create a vault in a local folder | `VaultServiceTest`; the Create form itself is manual |
| 2 | Create a vault on a removable USB drive | Manual |
| 3 | Vault in a OneDrive folder: only ciphertext syncs | At-rest scans in `VaultServiceTest`, `AuthServiceTest`, `FileServiceTest`; sync itself is manual |
| 4 | Register accounts A and B | `AuthServiceTest` |
| 5 | B cannot list, export, or decrypt A's file | `FileServiceTest.anotherUserCannotListExportOrDecryptTheFile` |
| 6 | A exports a file byte-for-byte | `FileServiceTest`, `StreamingFileCryptoServiceTest` (SHA-256) |
| 7 | Account password change keeps file access; old password fails | `AuthServiceTest` |
| 8 | Vault password change keeps accounts and files; old password fails | `VaultServiceTest` |
| 9 | Trash, restore, permanent delete | `FileServiceTest`, `UiFlowTest` |
| 10 | Tampered blob: export fails, no plaintext output | `FileServiceTest`, `CorruptionIntegrationTest` |
| 11 | Second process is refused as busy | `VaultLockServiceTest` (separate JVM) |
| 12 | Close Vault: login impossible until reopened | `UiFlowTest` |
| 13 | Screens at 1000x600 and maximized | `UiLayoutTest` (1000x600 and 1920x1040) |
| 14 | Portable app runs without installed Java | `scripts/verify-portable-package.ps1 -Launch` locally; a clean Windows VM is manual |
