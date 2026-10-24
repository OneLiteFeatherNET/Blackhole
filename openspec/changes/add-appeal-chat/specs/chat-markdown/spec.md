# Spec Delta

## Purpose
Defines the Markdown subset of the appeal chat, the sanitiser contract and the one parser that portal, panel and Discord relay share.

## ADDED Requirements

### Requirement: Supported subset
The system SHALL support exactly these constructs in a message: paragraphs with line breaks, bold, italic, strikethrough, inline code, fenced code blocks, bullet and numbered lists, block quotes and links. Every other Markdown or HTML construct MUST be shown as literal text and MUST NOT cause an error. Nesting deeper than 3 levels or more than 500 nodes MUST turn the remainder into literal text.

#### Scenario: Formatting is parsed
- **WHEN** a message contains `**bold**`, `*italic*`, `~~gone~~` and `` `code` ``
- **THEN** the block tree contains `strong`, `em`, `del` and `code` nodes with those texts

#### Scenario: Unsupported syntax stays text
- **WHEN** a message contains `# heading`, a table, `<b>x</b>` or `![alt](https://example.org/a.png)`
- **THEN** the text is shown literally, no heading, table, element or image node exists, and the URL inside the image syntax is not a link

#### Scenario: Too deep
- **WHEN** a message nests quotes 50 levels deep
- **THEN** the first 3 levels are quotes and the rest is literal text

### Requirement: Links are http or https only
A link SHALL be accepted only when its target, after trimming, has at most 2048 characters, no control character, space, backslash, `<`, `>` or `"`, scheme `http` or `https` (any case), a non-empty host, no user info and a valid port. The tree MUST carry the normalised absolute URL. A target that fails any rule MUST leave the whole link source as literal text. The system MUST NOT fetch, resolve or preview any link.

#### Scenario: Dangerous schemes
- **WHEN** a message contains `[x](javascript:alert(1))`, `[x](JaVaScRiPt:alert(1))`, `[x](data:text/html;base64,AAAA)`, `[x](vbscript:x)`, `[x](file:///etc/passwd)` or `[x](//evil.example)`
- **THEN** no `link` node is produced and the source is shown as text

#### Scenario: Encoded tricks
- **WHEN** the scheme is split by a tab, a newline, an entity (`&#106;avascript:`) or a percent escape
- **THEN** no `link` node is produced

#### Scenario: User info
- **WHEN** a message contains `https://trusted.example@evil.example/`
- **THEN** no `link` node is produced

#### Scenario: Accepted forms
- **WHEN** a message contains `[docs](https://example.org/a?b=1)`, `<https://example.org>` or a bare `https://example.org/path`
- **THEN** each becomes a `link` node with `href` set to the normalised URL

#### Scenario: Trusted host
- **WHEN** a link points to a host in `blackhole.chat.links.trusted-hosts`
- **THEN** the node has `external: false`; every other link has `external: true`

### Requirement: Source is stored, output is derived
The system SHALL store the message exactly as written and derive every rendering from it on read. A renderer or parser change MUST NOT change any stored row. The conversation answer MUST carry both the source `text` and the sanitised `blocks`.

#### Scenario: Read after edit
- **WHEN** a staff member edits a message
- **THEN** the new source is stored as a new event and the next read returns the new `text` and the `blocks` parsed from it

#### Scenario: Old messages
- **WHEN** a message written before this change contains `*x*`
- **THEN** it is rendered as italic and its stored text is unchanged

### Requirement: One parser for every output
Portal, panel and Discord relay SHALL get their structure from the same `markdown` module; the portal and the panel MUST draw the tree with component code that builds text nodes and elements and MUST NOT insert HTML strings. A conformance corpus in that module SHALL define source, expected tree, expected Discord text and expected plain text.

#### Scenario: Hostile tree in a client
- **WHEN** a client component receives a tree with a `link` whose `href` is `javascript:alert(1)`, a `text` node containing `<script>`, or an unknown node type
- **THEN** no element with that `href`, no script element and no event handler attribute is created, and the text is displayed as text

#### Scenario: No HTML injection API
- **WHEN** the portal or panel sources are scanned
- **THEN** none uses `v-html`, `innerHTML`, `outerHTML` or `insertAdjacentHTML`

### Requirement: Hostile corpus in CI
The module SHALL ship a hostile corpus (dangerous and encoded schemes, HTML, data URIs, nested and unbalanced markup, deep nesting, adversarial and very long inputs, control, bidirectional and zero-width characters, Discord mentions) and a seeded random-input test. Every case MUST produce no non-http(s) `link`, no raw HTML in any output, no exception and a bounded output; work MUST be linear in the input, asserted with step counters, not a clock.

#### Scenario: Adversarial input
- **WHEN** the parser reads 100000 characters made of `[`, `*`, `_` and backticks
- **THEN** it finishes within a counted step budget proportional to the input and returns a tree within the node limit

### Requirement: External links go through an interstitial
The portal and the panel SHALL open every link with `external: true` through a page `/leave` that receives the target in the URL fragment, validates it again, shows the full target as text and offers "Go back" and "Continue". The page MUST NOT redirect by itself. "Continue" MUST open in a new context with `rel="noopener noreferrer nofollow ugc"`. An invalid target MUST show an error and no Continue.

#### Scenario: External link
- **WHEN** a reader selects an external link
- **THEN** the interstitial shows the target and waits for the reader's choice

#### Scenario: Forged interstitial URL
- **WHEN** someone opens `/leave#` followed by an encoded `javascript:` URL
- **THEN** the page shows an error and offers only "Go back"

#### Scenario: Link text is not trusted
- **WHEN** the link text itself looks like a URL for another host
- **THEN** the target host is shown next to the text

### Requirement: Protocol and transcript keep the source
The appeal protocol (text and JSON), the player copy and the admin transcript SHALL contain the stored Markdown source, not a rendering. The JSON entry MUST carry `format: "markdown"` and the JSON `schemaVersion` MUST be 2. An attachment MUST appear as a marker line with type, size, the first 12 characters of its SHA-256 and its sanitised name; a share and a revoke MUST appear as marker lines without content. File bytes and excerpt text MUST NOT be in the protocol.

#### Scenario: Same events, same file
- **WHEN** a protocol is generated twice from the same events
- **THEN** the two files are byte identical apart from `generatedAt`

#### Scenario: Redacted message
- **WHEN** a message with attachments was redacted
- **THEN** its entry is a redaction marker with no text, name or hash

### Requirement: Discord mapping
The Discord renderer SHALL map the tree to Discord text and the bot SHALL map Discord text to a source in the subset. Links MUST be written as `text <url>` so no embed appears. Literal mentions MUST be neutralised and every post MUST use empty allowed mentions. From Discord, underline, spoiler and heading markers MUST be removed keeping the text, and mentions MUST become `@user`, `@role` and `#channel`, so no Discord id is stored. Messages over 2000 characters MUST be split at block boundaries.

#### Scenario: Mention injection
- **WHEN** a player writes `@everyone look at this`
- **THEN** the Discord post contains no working mention

#### Scenario: Round trip
- **WHEN** a supported-subset source is rendered to Discord text and mapped back
- **THEN** the resulting tree equals the original tree

#### Scenario: Discord ids
- **WHEN** a Discord message contains `<@123456789012345678>`
- **THEN** the stored source contains `@user` and no snowflake

### Requirement: Input checks apply to the source
The existing message rules (length, control characters, `PiiGuard` for UUIDs and IP addresses, the Ban ID check) SHALL apply to the whole Markdown source, including link targets and code blocks.

#### Scenario: Ban ID in a link
- **WHEN** a message contains a link whose target contains a Ban ID
- **THEN** the answer is HTTP 422 and nothing is stored
