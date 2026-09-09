# The agent configuration contract — what a coding-agent session runs as

**Status: the contract.** Every coding-agent session this platform launches is configured by one
stored record, keyed by the *surface* the session was started from. This page is the model behind
that record: what a surface is, what its configuration holds, how the configuration reaches a
container, and where the code deliberately differs from what a reader would guess. Where a service's
behaviour and this page disagree, this page is the bug report.

The pieces live in three repositories and none of them is a service you deploy for this:

- **`qits-coding-agents`** — the shared harness library both daemons embed. It renders the launch,
  reads the configuration document, and reports what a harness can be configured with.
  `eu.wohlben.qits:qits-coding-agents`, a released CalVer.
- **`qits-projects-service`** — the store, the editor's API, the resolved document, the external MCP
  catalog and the harness-capability cache.
- **`qits-workspaces-service`** and **`qits-projects-service`'s own agent-container factory** — the
  two hosts that create containers and hand them the document.

Before this, every one of those knobs was a constant, a `switch` arm or a Java text block, in two
diverged copies of the harness. Changing how the tickets desk was steered meant editing a text
block, releasing a daemon, rebuilding an image and recreating a container — and there was nowhere to
look up what a session would run as.

---

## 1. A session surface

A **session surface** is *where in the product a session was started from*. Nothing else. It is not
a permission, not a scope, not a container, not a tab id — it is the answer to "which button did a
person press, or which machine path started this".

The vocabulary as it stands is eight keys:

| Surface | Where it is | Host daemon |
| --- | --- | --- |
| `project.epics` | the refinement agent on a project's epics overview | projects |
| `project.tickets` | the triage agent on a project's tickets overview | projects |
| `epic.chat` | the refining route's chat tab | workspace |
| `epic.agent` | the refining route's agent tab (interactive) | workspace |
| `workspace.chat` | the workspace detail route's chat tab | workspace |
| `workspace.agent` | the workspace detail route's agents tab (interactive) | workspace |
| `epic.autonomous` | the composed task-prompt run nobody presses a button for | projects |
| `ticket.dispatch` | the agent a ticket dispatch starts in a freshly cut workspace | workspace |

`AgentSurface` is a **record over a string key, not an enum**, because the vocabulary stays open
(§7). Open does not mean unchecked: `AgentSurface.of` refuses a key outside `AgentSurface.KNOWN`
with an `InvalidCommandRequestException`, which a daemon's API reports as a 400 — the same way an
unknown `AgentMcpScope` is refused. A misspelled surface that silently fell through to a default
would be a misconfigured caller that looks like a working one.

### It sits beside `AgentMcpScope`, and the two axes cross freely

This is the distinction to hold on to, because the two are easy to conflate and neither can do the
other's job:

- **`AgentMcpScope` is *addressing*.** How narrow the MCP server urls are — `PROJECT`,
  `REPOSITORY`, `ACTIONS`. It is a runtime choice of the caller, it needs the container's own
  project/repository/workspace ids, and it stays a **request parameter**. It is deliberately not in
  the stored record.
- **`AgentSurface` is *what the session is for*.** Which prompt steers it, which model, which
  servers it attaches at all.

A tickets desk can be narrowed to one repository; narrowing to a repository must not quietly change
what the agent is steered at. Both axes cross freely, and the code keeps them apart on purpose.

### Why the surface had to exist before anything could be configured

The four workspace-daemon surfaces sent **byte-identical launch requests**. `epic.chat` and
`workspace.chat` differed only in which container the request reached; the daemon could not tell
them apart, and neither could anything downstream. The one surface distinction that did exist — the
tickets desk — was carried in a *display string*: the frontend sorted a project's sessions by
matching `" (tickets desk)"` inside `CommandDto.actionName`. A cross-repo contract living in a
label, where renaming the label silently moved every ticket session into the epics list.

So the surface travels: a named key on the launch request, recorded on the launched `Command`, and
reported back in the answer.

---

## 2. What a surface configuration holds

One record per surface, platform-wide. `AgentSurfaceConfiguration` in the library,
`AgentSurfaceConfigurationDto` in qits-projects-service — two copies rather than a shared type,
because that service depends on no daemon library and the library reads no service. **The field
names are the wire contract.**

| Field | What it is |
| --- | --- |
| `harness` | `CLAUDE` or `KIMI` |
| `model` | the id or alias to pass; empty means the harness's own default |
| `effort` | Claude's `--effort` level; empty means none. Kimi has no effort concept at all |
| `remoteControl` | on/off — see §5, which is not what you think |
| `permissionMode` | `SKIP_PERMISSIONS` or `PROMPT` |
| `activityTracking` | whether the turn-boundary activity hooks are wired, **per surface** rather than per daemon |
| `systemPrompt` | free text appended to the harness's own |
| `initialPrompt` | a turn pushed at session start, templated over the container's ambient facts |
| `mcpServers` | the platform's own servers this surface attaches, in render order, each with its narrowing |
| `externalMcpServers` | catalog entries this surface attaches (§6) |

### Empty is a value, not an absence

`model`, `effort`, `systemPrompt` and `initialPrompt` are never null. **`project.epics` steers with
an empty system prompt on purpose** — the epics desk has always run with nothing appended, and that
is a decision somebody made, not a row nobody filled in. The seed carries it as an empty string and
the render must produce byte-for-byte what it produced when the prompt was a missing `switch` arm.
An editor that treated empty as "unset" and substituted something would change six months of
behaviour on the day the store shipped.

The same reading applies to `model`: empty is "let the harness choose", which is different from a
model this platform picked and pinned.

### The seed is the migration

`AgentSurfaceDefaults` in qits-projects-service carries every surface's shipped values, read out of
the two daemons' literals — the tickets desk text block verbatim, the projects-side and
workspace-side repository tool lists, `skipPermissions()` on all three render paths, `CLAUDE` as the
harness nobody configured otherwise. Turning the store on changes nothing, and that equivalence is
asserted rather than assumed.

`seedIfAbsent` is **insert-if-absent, never upsert**. An operator's edit has to survive every
subsequent boot.

### Narrowing crosses the host seam

The document says *which* servers attach, in which order, with which pre-approval, and whether they
are read-only fenced — policy, edited in one place, travelling with the container. The **host** says
how a server key becomes a url at a given scope — addressing, needing ids the library deliberately
does not have. So a configured attachment is looked up in what the host offers for the launch's
scope, through `AgentMcpServers.serverFor(key, scope, narrowing)`.

Two consequences worth knowing:

- **A configuration naming a server the host does not serve at that scope refuses the launch.** A
  session missing a server it was configured with looks entirely normal and simply cannot do half
  its job.
- **A host that has not adopted the narrowing seam is flagged, not silent.** The default
  implementation ignores the narrowing and answers the scope's own mapping;
  `AgentMcpServers.honoursNarrowing()` is then false and each such launch records a note saying its
  addressing was the host's rather than the document's.

### What is deliberately not in the record

`AgentMcpScope` (a request parameter, §1), session lineage (resume/fork is not configuration), and
the built-in servers' pre-approval lists — those stay shipped constants keyed by server, seeded per
surface and not operator-editable. `allowedTools` **is** editable on an external catalog entry, for
the plain reason that the platform ships no pre-approval constant for a server it has never heard
of.

### Each launch records what it ran with

`AgentLaunchRecord` goes on the launched command: surface, harness, model, effort, permission mode,
remote control (and the session name it was asked for under), activity tracking, the attached
servers with their fences, a `configured` flag that is false when the launch rendered the library's
shipped constants, and `notes` for whatever the harness could not render (a Kimi effort level, a
Kimi system prompt). External servers are recorded **by key**, never by header value.

That record is what makes §3's "recreate only" rule safe rather than opaque: the store can be edited
at any time, so a session that behaved oddly last week is unreadable off anything else.

---

## 3. How a document reaches a container

A container is created carrying the resolved configuration for **every** surface it might serve, as
one JSON document, and reads it once at boot. `GET /projects/api/agent-configuration`
(`qits:admin` or `qits:system`) is the door that answers it — every surface, not the caller's guess
at its own, because which surfaces a container will serve is the caller's knowledge and it changes
as the product grows. The document is a handful of kilobytes.

- **qits-projects-service** builds it in-process for its own agent container. The store lives there;
  there is no fetch.
- **qits-workspaces-service** fetches it once at provision and stores it on the `workspace` row, so
  every creation path — ticket dispatch, manual, refining — is covered without threading a body
  through each caller.

### It is not a mounted file, and cannot be

The epic settled on a mounted file. **That is not expressible on this estate's container wire.**
qits-containers' `ContainerSpec` admits named volumes, shared volumes and a `hostDockerSocket`
boolean — and no host path at all, deliberately: *the shape is the security boundary*. There is no
free-form argv field and no bind mount, and the one bind the service will ever make is a boolean
rather than a path precisely so no caller can choose what gets mounted. Neither host that creates a
container holds a docker socket either, so neither can materialize host content.

So the resolved document rides as **two environment variables** — the bytes and the path — and the
**daemon** writes the file at boot before it starts anything, then hands the path to the library:

    QITS_PROJECTS_DAEMON_AGENT_CONFIGURATION        the JSON document
    QITS_PROJECTS_DAEMON_AGENT_CONFIGURATION_PATH   where the daemon is to write it

    QITS_WORKSPACE_DAEMON_AGENT_CONFIGURATION       the same pair, workspace side
    QITS_WORKSPACE_DAEMON_AGENT_CONFIGURATION_PATH

Each daemon reads its own environment and calls `AgentConfigurationDocument.readFrom(path)`, so the
library stays one contract with one spelling. The names carry each daemon's own prefix; what must
not diverge is the **shape**, and it does not.

**Both variables or neither.** A path naming a file nothing wrote must fail the daemon at boot
rather than read as "this container was given no configuration" — which is a real and different
state that has to stay distinguishable.

The path is `/tmp/qits/agent-configuration.json`
(`qits.projects.agent-configuration-path` / `qits.workspace.agent-configuration-path`). The three
places it is not are each a defect avoided: `/etc/qits` is root-owned in the image while the
container runs as the host uid, `/workspace` is the git checkout and a file there shows up in
`git status`, and `/claude-home` is shared by **every** container on the platform, so a
per-container document written there would be overwritten by the next container to boot. `/tmp` is
world-writable and dies with the container, which is exactly the document's lifetime.

### Absent is quiet; malformed is loud

The estate's usual absent-vs-broken rule, and it is the reason the document is read at boot rather
than at the first launch:

- **No path, or no file at that path** → `Optional.empty()`, and the launch falls back to the
  constants the library still ships (`AgentSurfaceConfigurations.shipped()`). That is the state every
  container created before this shipped is in, and it is permanent, not transitional.
- **A file that does not parse, or carries a harness, a permission mode or a reserved-key collision
  nobody understands** → `InvalidAgentConfigurationException` at boot, **naming the offending key**.
  A bad edit discovered as a weird agent three hours later is the failure this shape removes.

The document carries a `version` and a `generatedAt`. Version is **2** — a surface entry is
`{configuration, externalMcpServers}` rather than the configuration record flat, because the
rendered external servers carry credentials and had nowhere honest to sit inside a record the editor
also reads. Version 1 is still read: a container created between those two releases keeps what it
was born with for its whole life. A daemon reading a *newer* version refuses at boot rather than
mis-rendering a launch later.

### Why an edit applies to the next container

A running container keeps the configuration it was created with. An edit applies to the next one.
Nothing pushes, nothing polls, and **there is no staleness flag in the UI** — that is not an
omission, it is the decision.

The trade bought is that the launch path inside a container stays a pure local render with no
runtime dependency on qits-projects, which is worth more than immediacy because a container's life
is already scoped to a piece of work. What makes it safe rather than opaque is the launch record
(§2): each session is readable after the fact as *what it actually ran with*, even though the store
has moved on.

The mechanism, from the other side: the document's bytes are part of the container **spec**, and a
spec that differs from the running container's is a `Recreate.ifChanged` **replacement**. So an edit
changes the bytes and the next wake replaces the container. This is also why qits-workspaces stores
the document on the workspace row instead of fetching it on every ensure — a fetch per ensure would
replace every workspace's container whenever the store was edited, and (since the document carries
its own `generatedAt`) on *every* ensure regardless. For the same reason qits-projects stamps the
document with the store's last change rather than with now.

### The two failure policies differ, on purpose

- **qits-workspaces**: a container that cannot get its document is created **without** one and runs
  on the library's shipped defaults. Refusing would trade a configuration outage for a work outage.
  But a silent fallback nobody can see is how green-while-dead happens, so the reason is recorded on
  `workspace.agent_configuration_error` and answered on the record — logged is not enough.
- **qits-projects**: the build reaches a database this service already cannot run without, and the
  one way it fails is a catalog entry somebody attached with a credential that is not there — a
  configuration error a person made and a person can undo. So the failure reaches
  `AgentContainers.ensure` and the container is reported `FAILED` with the reason.

---

## 4. Harness capabilities: what fills the model and effort dropdowns

The valid models and effort levels belong to the **harness binary in the image**, not to this
platform. They differ per harness and they change when the image is rebuilt. So the library has a
second abstraction beside "render a launch": `HarnessCapabilities`, produced by
`HarnessCapabilityService`.

### The two harnesses answer very differently, which is the whole reason it is an abstraction

- **Claude Code** has `--effort`, and `claude --help` enumerates its levels — the real report parses
  them out of the binary's own help text. It has **no command that lists models**: `claude` has
  `agents`, `auth`, `mcp`, `plugin`, `project`, `doctor`, `install`, and nothing that prints a
  catalogue. Its models are therefore a **shipped alias set** (`opus`, `sonnet`, `haiku`, `fable`),
  and every Claude report carries `modelsEnumerated: false` whether or not the probe succeeded.
- **Kimi Code** has `kimi provider list --json`, a genuinely machine-readable model catalogue — and
  **no effort concept at all**, so a Kimi surface must show *no* effort control, not a disabled one
  carrying Claude's values.

Three booleans each mean something an empty list does not:

| Flag | What false/true says |
| --- | --- |
| `modelsEnumerated` false | this is a shipped alias set, not a catalogue the binary printed — the editor leads with the free-text escape |
| `effortSupported` false | this harness has no effort concept; **the absence is the fact** |
| `probeFailed` true | these lists are the shipped fallback rather than a reading of the binary |

A probe that fails or answers nothing still yields a usable report, flagged as what it is: an empty
dropdown is worse than a slightly stale one.

### Where the report is produced, and why it is cached

The values can only be discovered by **running the binaries**, and the binaries live in the
container image. The editor is a platform-wide route with no container in front of it and must
never shell out or wait on one. So:

1. the library probes each harness **once, at container start**, off the request path;
2. the daemon answers the report on its existing `GET /agents/available`, beside the harness list
   and the default it already served — the body gains `imageVersion`, `reportedBy` and a
   `capabilities[]` array of `HarnessCapabilities.toJson()`;
3. qits-projects relays that body **unreshaped** into `PUT /projects/api/agent-capabilities` when a
   container's daemon says Hello over the control socket. A relay that reshapes is a third place the
   contract can drift;
4. the editor reads `GET /projects/api/agent-capabilities`.

The cache is keyed by **harness and image version**, and the union across image versions is
**refused on purpose**: two containers can be running two builds at once, and merging their answers
would offer a model set no single binary has. The newest report wins whole, names its image version,
and the losers are listed beside it rather than folded in — newest *by arrival*, because an image
version is a name and a report is an event and only the second has a time.

A cache that has never been written answers the library's shipped fallback, flagged, so the editor
works on a fresh estate before any container has started. That fallback reports `authenticated:
false`, because nobody has looked.

### The report says what it *enumerated*, not what is *legal*

This is the sentence to keep. Nothing in the report is a validator. The launch renders whatever
string the configuration holds, so a full model id the harness never listed still works — pinning
one is exactly what somebody comes to that field for — and an effort level this report never saw
renders too. The editor's control is therefore a dropdown of what was reported **plus a free-text
escape**.

Every probe command sits beside its parser with a fixture of the binary's real output, so a harness
upgrade that changes its help text fails a test rather than quietly emptying the dropdowns.

### Authentication is part of the same report

Signed in or not, per harness, off the shared credential volume — and **fail-closed**: `false` when
nobody looked. A launch against a harness nobody has signed in **refuses**
(`AgentNotSignedInException`) rather than quietly becoming the sign-in terminal it used to return.
`launchLogin` stays as a deliberate door; what went is the substitution.

---

## 5. Remote control — the one a reader gets backwards

**Remote control is not interactive-only, and nothing validates it against the launch shape.** The
epic said the opposite and the owner overruled that line on 2026-09-09; the code is the contract.

One knob, two mechanisms, the opposite way round from the intuition:

- an **interactive** launch takes `claude --remote-control "<name>"`, because that is a real
  interactive session;
- a **chat** launch runs `--print --input-format stream-json`, where the harness parses the flag and
  drops it on the headless branch — so the chat transport asks for the bridge over the **SDK control
  channel** instead, once the session announces itself (`StreamJsonChatProtocol`).

**When the knob is on, the flag is set.** There is no refusal of any combination and no inert
checkbox: the knob turns on whichever mechanism the launch shape has. An operator who does not want
a bridge turns the knob off.

Which is why the seed reads as surprising and is correct: **remote control is seeded *on* for the
chat surfaces and *off* for the interactive ones**, because that is what the daemons were doing on
the day the store shipped. Seeding the intuition rather than the behaviour would have turned six
sessions' remote bridge off.

### The session name

Sessions are named **`qits <surface> <branch>`** (`AgentRemoteControl.sessionName`; a container that
does not know its branch yet is named by its surface alone). Left to itself the harness derives the
name from the hostname, so every session this platform started would be indistinguishable in the
claude.ai session list — a list whose whole job is telling sessions apart. `qits` leads because that
list is not this platform's.

### Do not reach for `remoteControlAtStartup`

The user-settings key would do the same job and is the **wrong door**: a launch points `HOME` at the
shared credential volume, so writing that setting would switch Remote Control on for **every session
on the platform at once** — the exact opposite of a per-surface knob. (It is also ignored when set
in a project's checked-in `.claude/settings.json`.)

### The four variables that would switch it off silently

`DISABLE_TELEMETRY`, `DO_NOT_TRACK`, `CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC`,
`DISABLE_GROWTHBOOK` — the feature-flag evaluation Remote Control depends on. The workspace image
sets none of them today (only `DISABLE_AUTOUPDATER=1`). `AgentRemoteControl.disabledBy` checks the
launch environment and then the daemon's own, and a launch **reports rather than refuses**: a
session without a bridge is still a working session. A later "turn off telemetry" change would
otherwise break this with nothing in any answer to say why.

---

## 6. External MCP servers, and the credential that is a reference

Beside the three platform servers a surface toggles, qits-projects holds a catalog of **external**
MCP servers: key, display name, url, an optional header name plus the qits-configuration key holding
its value, and the tools pre-approved on it. Defined once platform-wide, attached per surface by key
alone — a server is not re-entered with its token for each of eight surfaces.

Four constraints come from the render path rather than from taste:

- **Url transport only.** Kimi carries servers protocol-native over ACP as `(key, url, tools)` with
  no place for a stdio command, and a stdio server would need its binary in the workspace image
  anyway.
- **`repository`, `actions` and `observability` are reserved** — checked at the store's write door,
  again at boot, and again at render, because a document can reach a container from an older
  service. An external entry under one of those names does not attach twice: it **displaces** the
  platform's own server in the one key-to-config object both harnesses render, and the session looks
  entirely normal while talking to somebody else's.
- **`--strict-mcp-config` stays.** The rendered set is the whole set and the shared `/claude-home`
  volume's own MCP entries stay ignored. Attaching a server goes through the catalog, never through
  the volume.
- **Skip-permissions is the amplifier.** An attached third-party server's tools are auto-approved
  inside a container holding platform credentials. This is the first real reason for the
  permission-mode knob to be anything but "skip"; the two belong in the same conversation.

Servers arrive in the document **fully rendered** — url and header value already resolved — so a
container needs no second lookup. A header value is never stored back: the command keeps the script
with each header value replaced (`AgentLaunchMetadata.redact`), the launch record names attached
servers by key, `AgentExternalMcpServer.toString` redacts, and the editor's answers never carry a
header value at all. An unresolvable reference **fails the whole document build**, naming the key and
the surface — never a partially-resolved document, because a server rendered without its credential
401s on the agent's first tool call and surfaces as a confused agent hours later with nothing
pointing back at the store. A delete is refused while any surface attaches the entry, naming them
(which is why the attachment table carries no foreign key: an FK would make that message unreachable
and a 500 inevitable).

### The credential is a key reference whose class will change under it

The catalog stores **a qits-configuration key**, not material. Today it resolves to an ordinary
`plain` entry, because qits-configuration's `secret` class does not exist yet. That is accepted on
purpose — **the reference is the point**. When secrets management lands there, the same
`(application, key)` pair is served as a secret and nothing in this model changes: no field moves,
no editor changes, only the class of the entry behind the name.

### The namespace: `env.<VAR>` under the reserved application `qits-agent-mcp`

The epic left this open ("pick a non-colliding namespace, or an operator-class entry, and write down
which"). It is settled in `control/AgentMcpCatalog`, and the reasoning is short:

- **It cannot be a new key prefix.** qits-configuration addresses a value by `(env, application,
  key)` and its key grammar is **closed** — `ConfigurationKeys.requireKey` accepts `env.<VAR>` and
  the four indexed families (`mounts[i]`, `publishes[i]`, `groups[i]`, `aliases[i]`) and answers 400
  to everything else. A `secrets.` or `mcp.` key could not be *written* over there, and widening
  that grammar is another repository's change.
- **So it is the application segment** — the axis that is open (`requireApplication` checks a dns
  label and nothing more), and, better, the axis the concern is actually about. The worry was never
  the three characters `env`; it was that the key would be an environment variable **of an
  application**, injected into that application's container by the deployer. Under `qits-agent-mcp`,
  which **nothing deploys**, it is not: no deployer renders these keys anywhere, and no
  application's declaration can collide with them. That is precisely an operator-class entry,
  spelled with the one grammar the store has.
- **The application name is a constant, deliberately not configurable.** A per-deployment namespace
  would be a per-deployment place for a credential to hide.
- **The cost, stated rather than hidden:** `qits-agent-mcp` has no declaration, so its entries read
  as `orphaned` in qits-configuration's own listing. That flag means "no declaration accounts for
  this key", which is true and harmless — nothing is written or removed on the strength of it.

The read itself takes `qits:admin` or `qits:system` and has **no header fallback**, unlike the ci and
maintenance hops: a forwarded `X-Qits-*` pair from a machine-driven document build carries neither
role honestly, so with the named oidc client off the read is not attempted and the document fails
naming the key. Nothing in that package logs a value at any level — a log line names the key, the
status code or the exception, and never interpolates the value into any message, including an
exception's.

---

## 7. Adding a surface

Adding a ninth surface is an **additive change**: a constant and a shipped default. No migration, no
schema change, no enum widening. Three places must learn the key, and it is the same string,
character for character, in all three:

1. **`AgentSurface`** in qits-coding-agents — a `public static final AgentSurface` constant and a
   line in `KNOWN`. Optionally a shipped system prompt in `AgentSurfaceConfigurations`, which is the
   one thing the library genuinely ships per surface.
2. **`AgentSurfaceDefaults`** in qits-projects-service — a constant, a line in `SURFACES`, and its
   shipped default row. `seedIfAbsent` writes it at boot; a surface with no shipped default reads as
   `neutralDefault` rather than 404ing.
3. **The caller that launches it** — the frontend (or daemon path) that sends `surface` on the
   launch request, and the daemon that reads it off the body and carries it onto the command.

`AgentSurface.KNOWN` and `AgentSurfaceDefaults.SURFACES` are **copies, not a shared type**, and they
are in the same order deliberately: qits-projects-service depends on no daemon library and the
library reads no service, so what crosses between them is the key. **A rename on either side is a
wire break neither repository's suite would notice.** Add, do not rename.

Two forgiving edges make it possible to ship the three out of step:

- the store answers an **unknown surface** with its neutral default rather than a 404, so a daemon
  that knows a surface the store has not been told about still launches;
- `AgentSurfaceConfiguration` **keeps** a surface key outside `AgentSurface.KNOWN` as it was read
  rather than refusing it, so a document written by a newer service reaches an older daemon and the
  daemon simply never looks that entry up.

Neither of those is a licence to guess at the launch door: a *missing* surface resolves to what the
request's shape implies, and that guess is a dated migration crutch rather than a contract.

---

## 8. "Released" and "in effect" are two different moments

Two mechanics, neither a bug and neither surfaced in the UI:

- **A daemon runs the library version it was built against.** `qits-coding-agents` is a released
  artifact on a pinned maven CalVer, so a library fix is a two-step: release the library, then bump
  and release each daemon. There is no way to change shared harness behaviour and a daemon in one
  commit. The consequence to plan for: **the library's own suite is where harness behaviour is
  proven**, because a daemon can no longer prove it in the same commit — a harness change that
  arrives without a test there arrives untested, and the daemon bump that follows will not catch it.
- **A container carries the document it was created with.** A store edit takes effect on the next
  container, via the spec-hash replacement described in §3.

Add the image to that list when reading §4: the capability catalogue refreshes the first time a
container on a rebuilt image starts.

---

## 9. Out of scope of this model

Named plainly, because each of these looks like it might be in and is not:

- **Skills and subagents.** The editor's sub-route is *reserved and empty*. The feature is not
  designed; reserving the place is the whole scope.
- **Plugin installation and the marketplace** (`AgentPluginService`) — unchanged, though it moved
  into the library with everything else harness-shaped.
- **Per-project and per-user configuration.** One configuration per surface for the whole platform.
  Per-project overrides are a later epic; nothing here makes them impossible, and nothing here
  designs for them.
- **Editing the built-in servers' pre-approval lists.** Shipped constants keyed by server. Only an
  external catalog entry's `allowedTools` is operator-editable.
- **stdio / local-command MCP servers.** Url transport only (§6).
- **Live reconfiguration of a running container.** Recreate only (§3).
- **Building secrets management in qits-configuration.** The catalog references a key and takes
  whatever class that key has; the `secret` class arrives on its own schedule (§6).
- **Harness installation and version pinning** — the workspace image's business. This model reads
  what the installed harness reports; it does not choose which harness is installed.
- **`.qits-config.yml`'s agent section**, which keeps working as-is. The resolution order is request
  parameter → checkout → stored surface configuration → shipped default.

---

## 10. Frequently reversed

- A **surface** is where a session was started from; a **scope** is how narrow its MCP urls are. The
  two cross freely and neither is the other. (§1)
- An **empty system prompt is a value.** `project.epics` steers with nothing, deliberately. (§2)
- The document is **not a mounted file** — the wire cannot express one. Two environment variables,
  the bytes and the path, and the **daemon** materializes the file at boot. **Both or neither.** (§3)
- **Absent is quiet, malformed is loud.** No document means shipped constants; a bad one fails at
  boot naming the key. (§3)
- **An edit applies to the next container**, and that is not flagged anywhere. Read a session off its
  launch record, not off the store. (§2, §3)
- **Remote control is not interactive-only.** Chat sessions carry it over the SDK control channel,
  interactive sessions over `--remote-control '<name>'`. Nothing validates the combination, and the
  seed has it **on for chat, off for interactive**. (§5)
- Never `remoteControlAtStartup` — `HOME` is the shared credential volume, so it would switch the
  feature on platform-wide. (§5)
- The capability report says what it **enumerated**, not what is **legal**. A model the harness never
  listed is still settable. (§4)
- Claude has effort levels and **no model listing**; Kimi has a model listing and **no effort
  concept**. A Kimi surface shows no effort control, not a disabled one. (§4)
- The external-server credential is a **qits-configuration key reference**, `env.<VAR>` under the
  reserved application `qits-agent-mcp`. Its class changes under it without the model changing. (§6)
- Adding a surface is **additive**; renaming one is a wire break nobody's suite would notice. (§7)
- A library fix needs a **daemon release**; a store edit needs a **container recreate**. (§8)
