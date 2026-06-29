<p align="center">
  <img src="src/main/resources/com/fabianrodas/images/logo.png" alt="EncryptDrive logo" width="180">
</p>

<h1 align="center">EncryptDrive</h1>

<p align="center">
  A local-first desktop application for managing protected personal storage spaces.
</p>

---

## Overview

EncryptDrive is a JavaFX desktop application designed around the idea of local and portable protected storage.

The long-term goal is to allow a user to select or create a protected directory in locations such as:

```text
E:\EncryptDrive Vault
C:\Users\User\Desktop\EncryptDrive Vault
C:\Users\User\OneDrive\EncryptDrive Vault
```

Inside that directory, multiple users will be able to register and access their own protected content. Each user will have a separate account, password, and private storage area.

The project is being developed as a local-first application. It does not depend on a cloud service, a remote database, or an HTTP backend for its core functionality.

At its current stage, EncryptDrive includes local user registration, secure password storage, JSON-based account persistence, and a custom registration-success popup.

---

## Current Features

The current version includes:

* JavaFX desktop interface built with FXML and CSS.
* Custom undecorated application window.
* Window dragging, minimizing, and closing controls.
* Login and registration screens.
* Password visibility toggle buttons.
* Registration validation.
* Local account storage using `users.json`.
* Duplicate username validation.
* Password hashing using PBKDF2 with HMAC-SHA256.
* Unique random salt generated for every password.
* Passwords are not stored in plain text.
* Custom registration-success popup.
* Popup behavior:

  * Closing the popup with `X` keeps the user on the registration screen.
  * Selecting `Go to Log In` redirects the user to the login screen.

---

## Current Development Status

| Feature                                   | Status      |
| ----------------------------------------- | ----------- |
| JavaFX application structure              | Implemented |
| Login interface                           | Implemented |
| Registration interface                    | Implemented |
| Local user registration                   | Implemented |
| JSON user storage                         | Implemented |
| Password hashing and salt                 | Implemented |
| Registration success popup                | Implemented |
| Login authentication against `users.json` | In progress |
| Redirect to main dashboard after login    | Planned     |
| Folder or vault selection                 | Planned     |
| File encryption                           | Planned     |
| Multi-user encrypted storage              | Planned     |
| Portable vault support for USB drives     | Planned     |

---

## Project Goal

EncryptDrive is intended to become a local multi-user encrypted storage system.

A future EncryptDrive vault may have a structure similar to this:

```text
EncryptDrive Vault/
│
├── .encryptdrive/
│   ├── users.json
│   ├── vault.json
│   ├── accounts/
│   └── backups/
│
└── storage/
    ├── user-identifier-1/
    │   ├── encrypted-file-1.edf
    │   └── encrypted-file-2.edf
    │
    └── user-identifier-2/
        ├── encrypted-file-3.edf
        └── encrypted-file-4.edf
```

Each registered user will eventually have:

* A unique identifier.
* A username and full name.
* A protected password hash.
* A unique password salt.
* A private encrypted storage area.
* Access only to their own protected files.

Other users may see that encrypted files exist, but they should not be able to read or decrypt content that does not belong to them.

---

## Local-First Design

EncryptDrive is being designed to work without a traditional remote database.

Instead of requiring users to install MySQL, PostgreSQL, or another database system, the application stores its local user information in a JSON file.

Current local storage location:

```text
data/users.json
```

The application currently creates or uses this file from the project working directory:

```text
EncryptDrive/
├── data/
│   └── users.json
├── src/
├── pom.xml
└── README.md
```

A new file starts with:

```json
[]
```

After registering an account, it will contain user information similar to this:

```json
[
  {
    "id": 1,
    "fullName": "Fabian Rodas",
    "username": "Fabian",
    "passwordHash": "stored-password-hash",
    "salt": "unique-random-salt"
  }
]
```

The original password is never written to `users.json`.

---

## Password Security

EncryptDrive currently protects account passwords using:

```text
PBKDF2WithHmacSHA256
```

The password process works as follows:

```text
User password
      ↓
Random salt generated
      ↓
PBKDF2WithHmacSHA256 hash generated
      ↓
Hash and salt saved in users.json
```

The application stores:

* `passwordHash`
* `salt`

The application does not store:

* The original password.
* Reversible password information.
* Plain-text account credentials.

Every user receives a different random salt. This means that two users with the same password would still have different stored hashes.

Important: password hashing is used to validate a login. File encryption will be implemented separately in a future stage using an encryption method such as AES-GCM.

---

## Architecture

EncryptDrive follows a lightweight Model-View-Controller structure.

```text
com.fabianrodas/
│
├── encryptdrive/
│   ├── App.java
│   ├── LoginController.java
│   ├── RegisterController.java
│   └── SuccessPopupController.java
│
├── controllers/
│   └── UserController.java
│
├── models/
│   └── User.java
│
└── security/
    └── PasswordHasher.java
```

### Main Components

| Component                     | Responsibility                                                       |
| ----------------------------- | -------------------------------------------------------------------- |
| `App.java`                    | Starts the JavaFX application and loads FXML views.                  |
| `LoginController.java`        | Handles login screen behavior.                                       |
| `RegisterController.java`     | Validates registration data and creates accounts.                    |
| `SuccessPopupController.java` | Controls the registration-success popup behavior.                    |
| `UserController.java`         | Reads and writes user data in `users.json`.                          |
| `User.java`                   | Represents a registered EncryptDrive user.                           |
| `PasswordHasher.java`         | Generates password salts, hashes passwords, and validates passwords. |

---

## User Registration Flow

The current registration process follows these steps:

```text
User fills in registration form
      ↓
Application validates all fields
      ↓
Application validates username length
      ↓
Application validates password length
      ↓
Application checks password confirmation
      ↓
Application checks whether username already exists
      ↓
Password is hashed with a random salt
      ↓
New user is saved in users.json
      ↓
Success popup is shown
```

Registration validation currently includes:

* Full name cannot be empty.
* Username cannot be empty.
* Username must contain at least 3 characters.
* Password cannot be empty.
* Password must contain at least 8 characters.
* Password confirmation must match.
* Username cannot already exist.

---

## Registration Success Popup

After a successful registration, EncryptDrive displays a custom popup window.

The popup provides two possible actions:

```text
X button
→ Closes the popup
→ Keeps the user on the registration screen

Go to Log In button
→ Closes the popup
→ Redirects the user to the login screen
```

This behavior allows the user to decide whether to register another account or continue to login.

---

## Technologies Used

* Java 21
* JavaFX 21
* Maven
* FXML
* CSS
* Gson
* JSON local storage
* PBKDF2WithHmacSHA256
* Java Cryptography Architecture

---

## Dependencies

EncryptDrive uses Gson to read and write user information in JSON format.

Example Maven dependency:

```xml
<dependency>
    <groupId>com.google.code.gson</groupId>
    <artifactId>gson</artifactId>
    <version>2.14.0</version>
</dependency>
```

The Java module configuration must include Gson support:

```java
module com.fabianrodas.encryptdrive {
    requires javafx.controls;
    requires javafx.fxml;
    requires com.google.gson;

    opens com.fabianrodas.encryptdrive to javafx.fxml;
    opens com.fabianrodas.models to com.google.gson;

    exports com.fabianrodas.encryptdrive;
}
```

---

## Running the Project

### Requirements

Before running EncryptDrive, make sure that the following tools are installed:

* JDK 21
* Maven
* JavaFX 21
* A Java IDE such as Apache NetBeans, IntelliJ IDEA, or Eclipse

### Run with Maven

From the project directory, run:

```bash
mvn clean javafx:run
```

When using Apache NetBeans, the project can also be run directly through the IDE.

---

## Project Resources

```text
src/main/resources/
│
└── com/fabianrodas/
    │
    ├── css/
    │   ├── login.css
    │   ├── register.css
    │   └── success-popup.css
    │
    ├── encryptdrive/
    │   ├── login.fxml
    │   ├── register.fxml
    │   └── success-popup.fxml
    │
    └── images/
        └── logo.png
```

---

## Future Development

The next development stages for EncryptDrive are expected to include:

1. Login authentication using the local `users.json` file.
2. Redirecting authenticated users to a main dashboard.
3. Session handling for the currently logged-in user.
4. Selecting or creating a protected vault directory.
5. Creating a vault configuration file.
6. Creating separate protected storage folders for each user.
7. Encrypting files locally before storing them.
8. Supporting portable vaults stored on USB drives.
9. Adding encrypted backup support.
10. Adding safe file import, export, deletion, and recovery features.
11. Improving protection against accidental file replacement or corruption.

---

## Important Security Note

The current version protects account passwords through hashing, but it does not yet encrypt files.

The future file protection layer should use authenticated encryption, such as:

```text
AES/GCM/NoPadding
```

This will allow EncryptDrive to protect file confidentiality and detect unauthorized modifications.

The application is also intended to remain local-first. A future cloud synchronization option may be considered, but only after local encryption is implemented so that files remain encrypted before leaving the user’s device.

---

## Development Notes

The local `users.json` file should not contain real production credentials in a public repository.

For development purposes, the file may be ignored through `.gitignore`:

```gitignore
data/users.json
```

The application can recreate an empty JSON file automatically when necessary.

---

## Author

Developed by Fabián Rodas.

Copyright © 2026 Fabián Rodas. All rights reserved.
