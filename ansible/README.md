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

`monitoring_binaries` in `group_vars/all.yml` pins each binary by version and sha256. Prometheus and
`blackbox_exporter` come from their GitHub release archives. `node_exporter` comes from the Debian package
that Docker Hardened Images builds from the same tagged commit (`deb:`). An upstream archive keeps the Go
toolchain and `golang.org/x` modules it was built with until the next release. `node_exporter` 1.12.1,
built with Go 1.26.5, had fifteen high findings in them four months later, and no release had replaced it.
DHI rebuilds it with the current toolchain and publishes a new revision of the package each time;
revision `1.12.1-9` has none of those findings. The package holds one static binary and depends on nothing. The role unpacks it with
`dpkg-deb` and installs the binary like the others, so `dpkg` never sees it.

To move it, find the newest `node-exporter` entry in
`https://dhi.io/deb/debian/main/dists/trixie/main/binary-amd64/Packages` and pin its `Filename` (under
`https://dhi.io/deb/debian/main/`) and `SHA256`. That repository is the one DHI's images install from,
not a documented download. If it ever goes, drop `deb:` and pin the upstream archive's sha256 instead.

## Converge

From a checkout, signed in to Azure as someone who may run commands on the VM, with Azure CLI 2.90.0
or later (see [terraform/README.md](../terraform/README.md#prerequisites)):

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
