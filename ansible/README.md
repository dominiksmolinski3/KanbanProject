# Monitoring VM configuration

`site.yml` configures the Prometheus VM that `terraform/modules/monitoring_vm` creates. The VM applies
it to itself: nothing connects to it over SSH, because nothing can.

| Role | What it does |
| --- | --- |
| `base` | Masks `sshd`, allows only 443 through `ufw`, keeps unattended upgrades on, mounts the data disk at `/var/lib/prometheus` |
| `tls_material` | Creates a CA and a server certificate on the data disk once, publishes the CA certificate to `MONITORING-CA-CERT`, and reads the push and read credentials from Key Vault as the VM's identity |
| `prometheus_stack` | Checksum-pinned Prometheus, `node_exporter` and `blackbox_exporter` on loopback, with the rules and scrape targets from `observability/prometheus` |
| `edge_proxy` | nginx on 443: the push credential may only `POST` the OTLP path, the read credential may only query, everything else is a 404 |

## Converge

From a checkout, signed in to Azure as someone who may run commands on the VM:

```bash
ansible/scripts/converge.sh <commit>          # defaults to origin/main
ansible/scripts/converge.sh <commit> --check  # report what would change
```

It runs `ansible-pull` through `az vm run-command` at that exact commit, so the VM only ever applies
something that is on GitHub. A second converge must report `changed=0`. The full log is
`/var/log/kanban-converge.log` on the VM.

`monitoring-converge.yml` runs it on every push to `main` that touches `ansible/` or `observability/`, then
checks that a second converge changes nothing. `monitoring-drift.yml` still checks nightly for changes made
by hand.

## Test

`molecule test` (run from this directory, in CI by `observability-ci.yml`) converges the same roles twice
in a systemd container, with throwaway credentials instead of Key Vault, and checks which credential
reaches which path. Ansible itself is pinned to 9.2.0, the version Ubuntu 24.04's apt installs on the VM.
