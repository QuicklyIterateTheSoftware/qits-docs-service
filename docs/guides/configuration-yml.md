# `.config/qits/configuration.yml` — a repository declares its own configuration

**Status: the contract.** This page is what the migration wave follows. Where a service's behaviour
and this page disagree, this page is the bug report.

A service repository declares the environment keys it reads, with their types and their defaults, in
a file it carries itself. qits-deployments reads that file at the released tag and hands it to
qits-configuration before it schedules anything; qits-configuration keeps one declaration per
`(application, version)` and serves a resolved map per environment, the declared defaults sitting
underneath whatever an operator has overridden.

The point is not the file. The point is **where the default lives**. Today a service's defaults live
in `ComposeTemplate.java` inside qits-bootstrap — one file, in another repository, that has to know
every application's env block, and that rewrites the whole deployment's environment every time it
runs. A default that lives there is a fact about an application written down somewhere the
application cannot see, cannot test, and cannot change in the same commit as the code that reads it.
That is the whole motivation, and everything below is downstream of it.

---

## 1. The file

The path is exact, and it is not a pattern:

    .config/qits/configuration.yml

**Exact-path, deliberately.** qits-ci discovers its pipelines by the `ci-event-*` prefix in the same
directory, so anything matching that prefix becomes a trigger file the CI daemon will try to parse as
a pipeline. `configuration.yml` does not match it and never will. Nothing here is discovered by
shape: the deployer asks the git host for this one path, gets the bytes or a 404, and that is the
entire lookup.

The top level is **closed** and holds exactly one key:

```yaml
# .config/qits/configuration.yml
keys:
  QITS_DOCS_PAGE_SIZE:
    type: number
    default: 50
    description: How many versions one catalog page returns.
```

`keys:` may be empty (`keys: {}`), and that is a meaningful thing to commit — it says "this
application has been through the migration and reads no configurable environment", which is a
different statement from having no file at all. Any other top-level key is a parse failure, not a
warning: an unrecognised key is far more likely to be a typo in a key the author believed was doing
something than a forward-compatible extension, and a silently ignored declaration is the failure mode
this whole mechanism exists to remove.

### What the file does NOT carry

**No `deployment_target`.** It is tempting — the address of a peer depends on whether the peer is a
platform-plane or an environment-plane service, so the renderer needs that fact — but the fact is
already written down, once, in `deployments.yml` at the same tag, and the deployer is already reading
that file to decide whether the repository deploys at all. So the deployer reads it there and passes
it alongside the seed. Two files stating one fact is two files that can disagree, and the one that
would be wrong is the new one nobody has been maintaining for a year.

**No environment names, no per-environment values.** A declaration is a statement about the
*application*, identical in every environment. The per-environment part is an operator override, and
it lives in qits-configuration where it can be changed without a release.

**No secrets.** See §7 — secrets stay in bootstrap, and this file is fetched from the git host by
path at a tag, which is to say it is as public as the repository is.

---

## 2. Per-key shape

Each entry under `keys:` is a mapping:

```yaml
keys:
  <ENV_KEY_NAME>:
    type: string | boolean | number | serviceAddress | packageVersion
    default: <literal>        # some types take one, some refuse one
    description: <free text>  # optional, and worth writing anyway
```

The type vocabulary is **closed**. An unknown `type:` is a parse failure for the same reason an
unknown top-level key is: the alternative is a key that declares itself into existence with no
semantics attached, and the person who typo'd `boolean` as `bool` finds out at the next incident
rather than at the seed.

`description:` is optional and is the only field here that exists purely for a human. Write it. The
resolved-configuration screen shows it beside the value, and it is the difference between an operator
knowing what they are about to change and an operator guessing from the key name.

### `string`

The unremarkable one. `default:` is optional; a key with no default is declared-but-unset, which is a
legitimate state (the application must cope with absence, and now says so out loud).

```yaml
  QITS_DOCS_DEFAULT_SITE:
    type: string
    default: "@qits/ui-components"
    description: The site the reading room opens on when the URL names none.
```

### `boolean`

`default:` must be the literal `true` or `false` — not `"true"`, not `yes`, not `1`. YAML is
generous about spelling booleans and that generosity is exactly what makes a config file rot: three
spellings of one value in one estate, and eventually a parser somewhere that only knows two of them.

```yaml
  QITS_DOCS_METRICS_ENABLED:
    type: boolean
    default: false
```

### `number`

`default:` must parse as a decimal number. No units, no suffixes, no `10s` and no `512M` — a key
whose value carries a unit is a `string` whose description names the unit, because the moment a
number type starts accepting `512M` it has to have an opinion about whether that is 512 000 000 or
536 870 912, and it should not have opinions.

```yaml
  QITS_DOCS_CACHE_SECONDS:
    type: number
    default: 300
    description: How long a resolved version redirect stays cacheable. Seconds.
```

### `serviceAddress`

The interesting one, and the reason this file kind is worth having at all.

```yaml
  QITS_EVENTS_URL:
    type: serviceAddress
    service: qits-events
    port: 8080
```

- **No `default:`.** The value is not the repository's to state — it is derived per environment, at
  read time, from the platform's own knowledge of where that application runs. A declared default
  here would be a hard-coded peer address, which is the thing being deleted.
- **No path.** The declaration renders `http://<addr>:<port>` and stops. If your application needs
  `${QITS_EVENTS_URL}/events/api/publish`, the `/events/api/publish` half is **your code's**, composed
  app-side against the address. A path in the declaration is your peer's API surface written down in
  your repository, where it cannot be updated when the peer moves it.
- **`service:` names an APPLICATION**, not a repository and not a container. `qits-events`, not
  `qits-events-platform-service` — the same name `deployments.yml` puts in its `application:` line,
  which is the identity the platform actually routes on.
- **Rendering is per environment**, and the shape is decided by *the target's own recorded
  `deployment_target`*, not by the caller's: a platform-plane application renders bare
  (`http://qits-events:8080`), anything else renders `<env>-`-prefixed
  (`http://dev-qits-observability:8080`). That is the same rule `ComposeTemplate` spells today as
  `${ENV_NAME}-qits-<app>` for an environment service and the bare name for a platform one — it is
  now derived rather than typed, which means an application that changes plane fixes every consumer's
  address by changing its own `deployments.yml`.
- **System-rendered: an operator write to a `serviceAddress` key is rejected.** Not accepted and
  overridden, not accepted and warned about — rejected, with a 4xx. There is no operator intent that
  a peer address override expresses which is not better expressed by moving the peer, and an override
  here is a silent, permanent divergence between what the topology says and what one environment
  actually dials.

### `packageVersion`

```yaml
  QITS_DOCS_IMAGE_VERSION:
    type: packageVersion
    package:
      type: docker            # docker | binary
      name: qits/qits-docs
```

- **No `default:`.** A version is not a property of the code that reads it; it is **estate state**.
  The repository declares *which package's version this key carries*, and the platform supplies the
  version.
- **Written by the release listener**, on every matching release: qits-configuration watches
  `SoftwareRelease` and, for every declared key whose `package` matches the released artifact
  identity, writes the new version. That is the mechanism by which "the newest release is deployed"
  stops being a manual pin.
- **An operator pin is a transient intervention.** You can still set one, and it holds — until the
  next matching release, which supersedes it. This is the one place in this document where an
  operator override does *not* win permanently, and it is deliberate: a pin is how you hold an
  estate still during an incident, not how you opt out of releases. If you want to stop consuming a
  package's releases, that is a change to the declaration, not a value someone has to remember to
  un-pin.
- **The key belongs to the CONSUMING application.** `qits-deployments` declaring the version of
  `qits/qits-docs` is right if qits-deployments is what deploys that image. qits-docs declaring its
  own image version is not — nothing in qits-docs reads it.

---

## 3. Seed semantics — how the declaration reaches the config service

The sequence, per deployment:

1. qits-deployments resolves the version it is about to deploy and fetches
   `.config/qits/configuration.yml` from the git host at `refs/tags/<version>`. **At the tag**, never
   at `main`: the declaration must describe the tree being deployed, and `main` has moved on by
   definition — a deployment that seeds `main`'s declaration and runs the tag's code is a deployment
   configured for software it is not running.
2. It reads `deployment_target` from the same tag's `deployments.yml`.
3. It **POSTs the declaration synchronously to qits-configuration**, with the target alongside, and
   waits.
4. Only then does it schedule the deployment.

Synchronous and before, both load-bearing. The container's environment is composed from the resolved
map; a declaration that arrives after the container has started is a declaration that did not affect
this deployment, and "the config lands eventually" is indistinguishable at 3am from "the config never
landed".

### The absent file is not an error

**No file at the tag = not yet migrated = the deployment proceeds exactly as it does today.** This is
what makes the migration a wave rather than a flag day. Every repository in the estate is currently in
this state; each one leaves it on its own schedule, and until it does, nothing about its deployment
changes.

Do not read this as permission for the file to be optional forever. It is a migration affordance with
an end date attached to the wave, not a supported mode.

### The failing seed refuses the deployment, and says which failure it was

A seed that does not succeed **stops the deployment**. Deploying software whose declared configuration
was rejected means running it against a resolved map that does not match what it declared, which is
the failure this mechanism exists to prevent — so it is a refusal, not a warning.

What matters is that the refusal text tells the two cases apart, because they have different people
and different fixes:

- **"Your `configuration.yml` is broken"** — qits-configuration answered `422` (the file did not
  parse, or a key's shape is wrong) or `409` (a different declaration is already recorded for this
  version; see §4). The fix is a commit in the service repository and a new tag. Nobody should be
  paged.
- **"The config service is down"** — qits-configuration answered `5xx`, or did not answer, **after
  the deployer had been patient about it**. Retried, backed off, and still nothing. The fix is
  operational and has nothing to do with the repository being deployed. This one is worth a page.

One message for both cases sends the on-call engineer to read a YAML file that is fine, or sends the
service author to investigate an outage they cannot fix. Distinguish them in the text, not just in a
status code buried in a log line.

---

## 4. Immutability — one declaration per `(application, version)`

A version is a tag, and **a tag is immutable**. The declaration recorded against one is too.

- **Same content, re-POSTed: a no-op, `200`.** Content-hashed, so this is cheap and exact. Releases
  replay, deployments get retried, and the same version gets deployed to five environments — every
  one of those re-POSTs the same bytes, and every one of them must succeed. This is the ordinary path,
  not the edge case.
- **Different content for a version already recorded: `409`.** Something is wrong upstream and it is
  worth stopping for. Either the tag moved (which it must not) or two things disagree about what
  version they are deploying. The declaration is not overwritten and the deployment is refused.
- **Unparseable: `422`**, with the parse error. Nothing is recorded.
- **A system-only `DELETE` exists, for tag recovery.** Not an operator door — the case it serves is
  a tag genuinely rebuilt during an incident, where the recorded declaration is now a lie about a tree
  that no longer exists and the `409` above is standing between the estate and a working deployment.
  It is system-authenticated, it is auditable, and reaching for it should feel like reaching for it.

The reason this is strict rather than last-write-wins: the declaration is what an operator's override
is *interpreted against*. If a version's declaration can change under it, an override that was valid
this morning becomes an orphan this afternoon with no event anyone can point at.

---

## 5. Precedence — what wins

For an ordinary key, lowest to highest:

    declared default  <  imported (bootstrap /import)  <  operator override

- **Declared default** — this file, at the deployed version. The floor. Present for every declared
  key that has a `default:`; absent for those that do not, and for `serviceAddress` and
  `packageVersion`, which are rendered and written rather than defaulted.
- **Imported** — what bootstrap pushed through `POST /configuration/api/import`. This is the layer
  that carries an estate's existing state into the new mechanism, and it sits *above* the declared
  default on purpose: during the migration, the value bootstrap has been supplying is the value the
  estate is actually running with, and a declaration whose default happens to differ must not silently
  change behaviour the moment the file lands.
- **Operator override** — `PUT /configuration/api/applications/{application}/entries/{key}`. The top,
  and it stays the top. An operator who set a value expects that value.

Two amendments to the simple picture:

- **`serviceAddress` has no ladder.** It is rendered, per environment, and operator writes are
  rejected outright (§2). There is nothing to precede and nothing to override.
- **`packageVersion` inverts once.** The release listener's write supersedes an operator pin on the
  next matching `SoftwareRelease`. This is the single documented exception to "the operator override
  wins", and §2 argues why.

Resolution happens per environment, at read time — `GET /configuration/api/applications/{application}/resolved`
is what the deployer composes a container's environment from. Nothing is baked at seed time except
the declaration itself.

---

## 6. Orphans — an entry the declaration no longer states

An entry whose key is not stated by the governing declaration is an **orphan**. This happens
constantly and legitimately: a key gets renamed, a feature gets removed, an environment gets rolled
back to a version whose declaration is older and narrower than the one it was running.

The rule is:

- **Orphans are kept.** The value is not deleted, not hidden, and not moved.
- **Orphans are flagged at read time.** The resolved map and the entries listing both mark them, so
  the fact is visible wherever someone is already looking, rather than in a report nobody runs.
- **Orphans are swept deliberately**, by a person who has looked at them, and never automatically.

Auto-deletion is the tempting design and it is wrong for one specific reason: **a rollback makes
orphans out of perfectly good values.** Deploy `v9`, an operator sets an override on a key `v9`
introduced, something breaks, roll back to `v8` — whose declaration never mentioned that key. Under
auto-deletion the rollback silently destroys operator intent, and rolling forward again to `v9` comes
back up with the default. The value has to survive the round trip, so the orphan has to survive it.

An orphan is a question, not garbage.

---

## 7. Authoring checklist — migrating one application

You are translating one application's `EXTRAS` env block out of `ComposeTemplate.java` and into that
application's own repository. Work key by key. Every key in the block gets one of these four answers,
and "I'm not sure" means it stays in bootstrap for now.

### 7.1 Peer URLs → `serviceAddress`

Anything spelled with an `${ENV_NAME}-qits-X` or `${ALIAS_X}` interpolation is a peer address:

```yaml
# was:  QITS_OBSERVABILITY_URL: http://${ENV_NAME}-qits-observability:8080
QITS_OBSERVABILITY_URL:
  type: serviceAddress
  service: qits-observability
  port: 8080

# was:  QITS_EVENTS_URL: http://${ALIAS_EVENTS}:8080
QITS_EVENTS_URL:
  type: serviceAddress
  service: qits-events
  port: 8080
```

Note what disappeared: the `${ENV_NAME}-` prefix and the alias indirection both go, because the
renderer derives the shape from the *target's* recorded `deployment_target` (§2). You do not state
which plane your peer is on — you state which application it is, and get the right address in every
environment including the ones that do not exist yet.

**If the value carried a path, the path moves into your code.** `http://${ALIAS_EVENTS}:8080/events/api`
becomes a `serviceAddress` declaration plus an `/events/api` your client composes. This is a real code
change and it is the only part of the migration that is not mechanical — budget for it.

### 7.2 Flags, sizing and paths → typed literals

Everything that is a constant with a value: `boolean` for the on/off switches, `number` for pool
sizes, timeouts and limits, `string` for paths, names, modes and anything with a unit in it. Carry
the value across verbatim — the migration is not the moment to also change a default. If a default is
wrong, change it in a separate commit that says so.

### 7.3 Pin keys → `packageVersion`, on the consuming application

A key whose value is a version of something gets a `packageVersion` declaration in the repository of
the application **that reads it**, naming the package it tracks (§2). Get the direction right: this is
the most commonly reversed one in review.

### 7.4 What must STAY in `ComposeTemplate.java`

Not "for now" — these are structurally wrong for a declaration file, and none of them is a candidate
for a later wave:

- **Secrets.** `*_PASSWORD`, `*_SECRET`, `*_TOKEN`, and anything else that is one. The declaration
  file is fetched by path from the git host at a tag; it is exactly as readable as the repository. A
  declared default that is a secret is a secret in git.
- **The idp client registry.** `QITS_IDP_CLIENTS`, every `QITS_IDP_CLIENT_*_SECRET`, `_ROLES`,
  `_CLAIMS_*`. This is not one application's configuration — it is the estate's identity topology,
  authored as a whole, and it has to be consistent across services that are released independently.
- **Audiences.** `QITS_IDP_CLIENT_*_AUDIENCES` and the `${IDP_AUDIENCES}` set they come from: same
  argument, one estate-wide fact.
- **Machine and OIDC switches.** The keys that decide *how a service authenticates at all*. A service
  that mis-declares one of these cannot be reached to have the mistake corrected.
- **Tier names.** `QITS_ENVIRONMENT`, `QITS_EDGE_ENVIRONMENTS`, `QITS_EDGE_DEFAULT_ENVIRONMENT` and
  the edge's other env-naming keys. These say which environment this *is*. A declaration is a
  statement about the application, identical everywhere (§1) — an environment's own name is the one
  thing it definitionally cannot be.
- **Mounts, publishes, groups and aliases.** Not environment at all — compose topology that happens to
  live in the same template.
- **Boot-sequencing keys.** The deployer's extras-url, the self-update keys: the ones read *in order
  to be able to fetch configuration*. A key whose value is needed to reach the config service cannot
  be stored in the config service. Bootstrapping is not a layer you can eliminate, only one you can
  keep small.
- **Initial pin values at cold boot.** The first version of anything, before a `SoftwareRelease` has
  ever been observed. `packageVersion` is estate state written by the release listener, and at cold
  boot the listener has heard nothing.

### 7.5 The two-step cold-boot rule, per repository

**An application's env block may only leave `ComposeTemplate.java` after a declaration-carrying tag is
that repository's newest tag. Never in one commit.**

Step one: land `.config/qits/configuration.yml` and release the repository. The declaration is now
seeded on every deployment of that version, and it is doing nothing yet — bootstrap is still
supplying the same values, and the `imported` layer sits above the declared default (§5), so nothing
observable changes. That is the point: step one is provably inert.

Step two: only once that tag is the newest, delete the env block from `ComposeTemplate.java`.

The failure the rule prevents is specific and it is a cold boot. Do both in one change and a fresh
estate brings the application up from the newest *tag*, which — because the tag was cut before the
bootstrap change, or because the release is still in flight — may not carry the declaration. Bootstrap
has stopped supplying the keys and the declaration is not there to replace them: the application comes
up with nothing. On a running estate you would never see it, because the previous deployment's
resolved entries are still in place. It only bites on the one path that has no previous state, which
is the path you take when you are already having a bad day.

---

## 8. A complete example

```yaml
# .config/qits/configuration.yml — qits-docs
keys:
  # A peer, addressed. No path: DocsPaths composes /docs/api/... app-side.
  QITS_ARTIFACTS_URL:
    type: serviceAddress
    service: qits-artifacts
    port: 8080

  QITS_OBSERVABILITY_URL:
    type: serviceAddress
    service: qits-observability
    port: 8080

  # Typed literals, carried across verbatim from the template's env block.
  QITS_DOCS_CATALOG_PAGE_SIZE:
    type: number
    default: 50
    description: Versions per page in the catalog API. Raising it costs the store a wider listing.

  QITS_DOCS_REDIRECT_CACHE_SECONDS:
    type: number
    default: 300
    description: Cache lifetime of a version redirect. Seconds; a published version is immutable.

  QITS_DOCS_STRICT_ENTRYPOINT:
    type: boolean
    default: false
    description: Refuse a bundle with no index.html rather than serving its first file.
```

---

## 9. Frequently reversed

- The address renders with **no path** — the path is your code's. (§2)
- `service:` is the **application** name, not the repository name. (§2)
- A `packageVersion` key belongs to the application that **reads** it, not the one that publishes the
  package. (§7.3)
- An operator override beats a declared default **always**, except `packageVersion` against a release,
  **once**. (§5)
- An orphan is **kept**, and a rollback is why. (§6)
- Declaration first, template deletion second, **in two releases**. (§7.5)
