# Monitoring VM configuration

`site.yml` configures the Prometheus VM that `terraform/modules/monitoring_vm` creates. The VM applies
it to itself: nothing connects to it over SSH, because nothing can.

| Role | What it does |
| --- | --- |
| `base` | Masks `sshd`, allows only 443 through `ufw`, keeps unattended upgrades on, mounts the data disk at `/var/lib/prometheus` |
| `tls_material` | Creates a CA and a server certificate on the data disk once, publishes the CA certificate to `MONITORING-CA-CERT`, and reads the push and read credentials from Key Vault as the VM's identity |
| `prometheus_stack` | Checksum-pinned Prometheus, `node_exporter` and `blackbox_exporter` on loopback, with the rules and scrape targets from `observability/prometheus` (see [Pinned binaries](#pinned-binaries)) |
| `edge_proxy` | nginx on 443: the push credential may only `POST` the OTLP path, the read credential may only query, everything else is a 404 |

## Pinned binaries

`monitoring_binaries` in `group_vars/all.yml` pins each binary by version and sha256. All three come
from the Debian packages that Docker Hardened Images builds from the same tagged upstream commit
(`deb:`). An upstream release archive keeps the Go toolchain and `golang.org/x` modules it was built
with until the next release, and those collect high findings long before one ships. DHI rebuilds each
package with current dependencies and publishes a new revision each time. A package holds the static
binaries and depends on nothing. The role unpacks it with `dpkg-deb` and installs the binaries, so
`dpkg` never sees it.

To move one, find the newest entry for its package (`prometheus-<major.minor>`, `node-exporter`,
`blackbox-exporter`) in `https://dhi.io/deb/debian/main/dists/trixie/main/binary-amd64/Packages` and
pin its `Filename` (under `https://dhi.io/deb/debian/main/`) and `SHA256`. That repository is the one
DHI's images install from, not a documented download. If it ever goes, drop `deb:` and pin the upstream
archive's sha256 instead; the role falls back to the GitHub release.

## Converge

From a checkout, signed in to Azure as someone who may run commands on the VM:

```bash
ansible/scripts/converge.sh <commit>          # defaults to origin/main
ansible/scripts/converge.sh <commit> --check  # report what would change
```

Through `az vm run-command` it clones the repository at that exact commit, installs the collections
pinned in `requirements.yml` and runs the playbook locally, so the VM only ever applies something that is
on GitHub. A second converge must report `changed=0`. The full log is
`/var/log/kanban-converge.log` on the VM.

`monitoring-converge.yml` runs it on every push to `main` that touches `ansible/` or `observability/`, then
checks that a second converge changes nothing. `monitoring-drift.yml` still checks nightly for changes made
by hand.

## Test

`molecule test` (run from this directory, in CI by `observability-ci.yml`) converges the same roles twice
in a systemd container, with throwaway credentials instead of Key Vault, and checks which credential
reaches which path. CI runs ansible-core 2.16, the line Ubuntu 24.04's apt installs on the VM, with the same pinned
collections.
