# Deployment settings — where a per-installation value lives

A repository no longer declares its configuration in `.config/qits/configuration.yml`, and nothing
reads such a file: qits-configuration is retired (epic qits-765), and the deployer takes an
application's environment from two places only.

## What the code can derive, it derives

An address on qits-net is `<environment>-<application>`, and the deployer injects
`QITS_ENVIRONMENT` into every container it starts. So a peer address is a code default —
`http://${QITS_ENVIRONMENT:dev}-qits-artifacts:8080` — and not a value any deployment has to hand
over. Prefer that to a setting whenever the value follows from facts the container already has.

## Per-installation values are deployer settings

What only an installation knows — a secret, an external url, a switch an operator flips — is a
**setting** in qits-deployments' own store: one value per application, environment and key, where
the key is the environment variable's bare name (`API_TOKEN`, not `env.API_TOKEN`).

    qits deployments settings list    --application <app> [--environment <env>]
    qits deployments settings set     --application <app> --environment <env> --key <KEY> [--secret] < value.txt
    qits deployments settings delete  --application <app> --environment <env> --key <KEY>
    qits deployments settings preview --application <app> --environment <env>

- `set` reads the value on **stdin**, never from a flag; one trailing line break is taken off.
  `--secret` stores it as a secret: no answer, listing or preview ever sends its value back.
- A value reaches the application on its **next deployment**. `preview` shows what that deployment
  would add, remove or change against the running container.

The doors, under `/deployments/api/settings`:

| Door | Roles |
| --- | --- |
| `GET /settings?application=&environment=` | `qits:admin`, `qits:admin-agent`, `qits:desk-admin`, `qits:system`, `qits:agent` |
| `PUT /settings/{application}/{environment}/{key}` `{"value": …, "secret": …}` | `qits:admin`, `qits:system` |
| `DELETE /settings/{application}/{environment}/{key}` | `qits:admin`, `qits:system` |
| `GET /settings/preview/{application}/{environment}` | the read set above |

## Deployment shape lives in `.config/qits/deployments.yml`

Volumes, host binds, published ports and network aliases are not settings. They are part of the
repository's `.config/qits/deployments.yml`, released with the code:

```yaml
volumes: private:data:/data, shared:qits_shared_dot_claude:/claude-home:ro
binds: /var/run/docker.sock:/var/run/docker.sock
publishes: ${QITS_PORT}:8080, 443:8443
aliases: registry.${QITS_ENVIRONMENT}.localhost, githost.${QITS_ENVIRONMENT}.internal
```

`publishes:` takes `${QITS_PORT}` and `aliases:` takes `${QITS_ENVIRONMENT}`, which the deployer
fills per environment.

## Not here

External MCP server credentials for coding agents are not deployer settings either: they live in
qits-projects (`qits agent-mcp credentials …`, see `agent-configuration.md` §6).
