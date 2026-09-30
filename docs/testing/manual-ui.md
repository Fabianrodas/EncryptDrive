# Manual UI checklist

Run the app with `mvn javafx:run`. Repeat every section twice: once at the
minimum window size (1000x600, the size the app opens with) and once
maximized (title bar `□` button). `UiLayoutTest` covers the same screens
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
