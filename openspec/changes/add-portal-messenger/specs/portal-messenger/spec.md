## ADDED Requirements

### Requirement: Messenger layout
The portal conversation SHALL show the thread in a scrolling area of its own with the composer below it, and SHALL keep the newest message in view unless the player scrolled up.

#### Scenario: A reply arrives at the end of the thread
- **WHEN** a message arrives while the player is at the end of the thread
- **THEN** the thread shows the new message

#### Scenario: The player scrolled up
- **WHEN** a message arrives while the player is further up
- **THEN** the scroll position does not change

#### Scenario: The player sends
- **WHEN** the player sends a message from further up
- **THEN** the thread shows the new message at its end

### Requirement: Compact composer
The composer SHALL offer text, attach and send in one compact area. Enter SHALL send on a device with a fine pointer and SHALL add a line on a touch device.

#### Scenario: Enter with a mouse and keyboard
- **WHEN** the player presses Enter on a device with a fine pointer
- **THEN** the message is sent, and Shift+Enter adds a line instead

#### Scenario: Enter on a phone
- **WHEN** the player presses Enter on a touch device
- **THEN** a line is added and nothing is sent

### Requirement: Files on the appeal form
The appeal form SHALL let the player pick files under the checks of the chat, and SHALL send them as a chat message once the appeal is filed, without further action.

#### Scenario: Filing with a file
- **WHEN** the player files an appeal with one accepted file
- **THEN** the file is uploaded after the appeal is open and appears as a message in the conversation

#### Scenario: A file fails after filing
- **WHEN** the appeal is filed and the upload of a file is refused
- **THEN** the appeal stays filed, the other files are sent, and the composer shows the refused file with its reason

#### Scenario: A file is refused on picking
- **WHEN** the player picks a file of a type the chat does not accept
- **THEN** the form shows the reason and filing is still possible
