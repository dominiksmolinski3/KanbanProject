#!/usr/bin/env bash
#
# Pairs the backend state key with the -var-file, which is the one thing nothing else does.
#
# The environments are separated by state key alone, and the key lives in a flag rather than in
# providers.tf. Passing `key=env/prod/terraform.tfstate` next to `-var-file dev.tfvars` produces a
# plan that reads as a legitimate rename - destroy the prod resource group, create the dev one -
# and there is no workspace, no CI check and no assertion that catches it. Here both come from the
# same argument, so they cannot disagree.
#
#   ./tf.sh dev plan
#   ./tf.sh prod plan -out=prod.tfplan
#   ./tf.sh prod apply prod.tfplan
#
# `init -reconfigure` runs first every time: switching environments without it leaves the previous
# environment's backend configured, which is the other half of the same mistake.
#
# ---------------------------------------------------------------------------------------------
# The local overrides are paired here too, and they used not to be.
#
# Secrets that must not be committed - the ACS connection string, the captcha secret, the alert
# address, a workstation's own IP for the Key Vault firewall - live in a gitignored file beside the
# committed per-environment tfvars. That file was named `<env>.local.auto.tfvars`, and **Terraform
# loads every *.auto.tfvars in the working directory on every single run**, whatever -var-file is
# passed. So the pairing this script exists to guarantee had a hole straight through it.
#
# The committed var-file wins for every variable it sets, which is why this was invisible: `env`,
# the SKUs, the retention days all came out right. What it does not set is precisely the secret-
# bearing set, because those have no committed value - so a prod plan run from a workstation
# holding dev's local file took dev's ACS connection string into the prod Key Vault secret, dev's
# captcha secret with it, dev's alert address onto the prod alerts, and a developer's home IP onto
# the prod vault firewall. Measured, not reasoned: with `prod.tfvars` setting `env` and a
# `dev.local.auto.tfvars` setting both `env` and a second variable, `terraform console` answers
# "prod" for the first and the dev value for the second.
#
# So the file is now `<env>.local.tfvars` - no `.auto.` - and it is passed here, after the
# committed one so that it still overrides it. A file for an environment you are not running is
# now simply not read. Any leftover `*.local.auto.tfvars` stops this script outright rather than
# being quietly loaded alongside, because the whole point is that it is no longer a file anything
# picks up by itself.
#
# ---------------------------------------------------------------------------------------------
# ---------------------------------------------------------------------------------------------
# The pinned image, and the way it goes stale.
#
# `app_image_tag` is required, has no default, and must be an immutable commit SHA - a mutable tag
# renders a byte-identical container template on every apply, which produces no diff and therefore
# no revision, so the environment reports itself converged while serving whatever it was serving
# before. That is written down, it is why the variable is required, and it is not the failure that
# actually happened next.
#
# What happened next is that the pin was set correctly, once, and then nobody moved it. It sat on
# phase 01's commit from 15 Sep while roughly 25 pull requests merged; the apply that was meant to
# ship the STOMP broker relay rolled the Terraform faithfully and left the container running code
# from before any of it. Nothing was wrong with the tag, the plan, the apply or the revision - the
# deployment simply described a commit nobody had looked at in a week, and every instrument here
# agreed the environment matched its configuration, because it did.
#
# So an apply now refuses a pin that is not a commit on `origin/main`, and refuses one that is
# behind its tip unless it is told to. It is a refusal rather than a warning for the same reason
# the `.auto.tfvars` check above is: a warning printed before `terraform plan`'s own output is a
# warning nobody reads, and this failure's whole character is that everything looked fine.
#
# `--allow-stale-image` is the acknowledgement, and it has a real use rather than being an escape
# hatch: CD needs a few minutes to build and push the tip's image, so a merge and an apply in
# quick succession legitimately want the commit before it. Deliberately deploying an older commit
# is the other one. Both are decisions; neither is something to arrive at by not noticing.
#
# The comparison is against the local `origin/main`, without fetching - an apply should not be
# making network calls to decide what it is about to do, and a stale remote-tracking ref can only
# understate the gap, never invent one.
#
# ---------------------------------------------------------------------------------------------
# An apply that changes nothing observable is exactly the failure deployed-contract.yml was
# written for: an `app_image_tag` pinned to `latest` once made the container template
# byte-identical on every apply, so Terraform reported the environment converged while it served
# code from eight days and fifteen merges earlier, and the only instrument that would have caught
# it was asking the running origin a question this checkout already knows the answer to. That
# sweep otherwise runs once a day on a cron - which means the same gap this script exists to close
# for `*.auto.tfvars` (a hole invisible until somebody goes looking) stood open here for up to a
# day after every apply. So `apply` (never `destroy`, and never `plan`) runs it immediately
# afterward, against this apply's own `container_app_url` output rather than the
# `DEPLOYED_ORIGIN` repository variable the workflow reads - the two name the same origin, but
# this one cannot be stale relative to the state that was just written. A `destroy` has no origin
# left to ask, and a `plan` changed nothing to go check.

set -euo pipefail

ENVIRONMENTS="dev uat prod"

usage() {
  echo "usage: $0 <${ENVIRONMENTS// /|}> <terraform-subcommand> [args...]" >&2
  exit 2
}

# Pulled out of the arguments before the subcommand is read, because it is this script's flag and
# not Terraform's - passing it through would stop `terraform apply` with an unrecognised argument.
allow_stale_image=0
args=()
for arg in "$@"; do
  if [ "$arg" = "--allow-stale-image" ]; then
    allow_stale_image=1
  else
    args+=("$arg")
  fi
done
set -- ${args+"${args[@]}"}

[ $# -ge 2 ] || usage

env_name=$1
shift

case " $ENVIRONMENTS " in
  *" $env_name "*) ;;
  *) echo "error: unknown environment '$env_name'" >&2; usage ;;
esac

cd "$(dirname "$0")"

var_file="${env_name}.tfvars"
local_var_file="${env_name}.local.tfvars"
state_key="env/${env_name}/terraform.tfstate"

[ -f "$var_file" ] || { echo "error: $var_file not found" >&2; exit 1; }

# Refuse rather than load it as well: see the note at the top of this file. A `.auto.` file is
# read by Terraform itself on every run, so leaving one in place would mean the rename bought
# nothing and the leak was still available.
legacy=$(ls ./*.local.auto.tfvars 2>/dev/null || true)
if [ -n "$legacy" ]; then
  echo "error: Terraform auto-loads these on every run, whatever environment you asked for:" >&2
  echo "$legacy" | sed 's/^/  /' >&2
  echo "rename each to <env>.local.tfvars (drop the '.auto.'); this script passes the one that matches." >&2
  exit 1
fi

var_file_args=(-var-file="$var_file")
if [ -f "$local_var_file" ]; then
  # After the committed file, so it still overrides it - the later -var-file wins.
  var_file_args+=(-var-file="$local_var_file")
  echo "==> ${env_name}: state ${state_key}, vars ${var_file} + ${local_var_file}"
else
  echo "==> ${env_name}: state ${state_key}, vars ${var_file}"
fi


# Best-effort and deliberately not `set -e`-fatal on its own absence: no `container_app_url`
# output (a `destroy`d environment, or a root module that predates it) and no working python3/
# python on PATH both skip with a warning rather than failing an apply that already succeeded. A
# claim the deployment fails to answer is a different matter - that exit code is left to
# propagate, because it is the one thing on this path that would otherwise wait a day for the cron
# to say so.
#
# `command -v` is not enough on its own, and this was found by running it rather than by reading
# it: Windows ships a `python3.exe`/`python.exe` "app execution alias" on PATH ahead of any real
# interpreter even when Python was never installed, which `command -v` reports as present. Running
# it prints a message telling you to install Python from the Store and exits non-zero - so the
# first apply of this hook treated a no-op `terraform apply` as a failed one. Each candidate is
# therefore actually run (`--version`, discarded) before being trusted, and the loop tries the
# next name rather than stopping at the first `command -v` hit.
check_deployed_contract() {
  local origin python_bin candidate
  origin=$(terraform output -raw container_app_url 2>/dev/null || true)
  if [ -z "$origin" ]; then
    echo "==> skipping the deployed-contract check: no container_app_url output" >&2
    return 0
  fi

  python_bin=""
  for candidate in python3 python; do
    if command -v "$candidate" >/dev/null 2>&1 && "$candidate" --version >/dev/null 2>&1; then
      python_bin="$candidate"
      break
    fi
  done
  if [ -z "$python_bin" ]; then
    echo "==> skipping the deployed-contract check: no working python3/python on PATH" >&2
    return 0
  fi

  echo "==> checking the deployed contract against ${origin}"
  "$python_bin" ../.github/scripts/deployed_contract_check.py "$origin"
}

# The effective `app_image_tag`, in Terraform's own precedence: a `-var` on the command line beats
# both files, and the gitignored local file beats the committed one. Empty means nothing sets it,
# which Terraform itself refuses a moment later with "No value for required variable" - a better
# message than anything here would be, so this says nothing and lets it.
effective_image_tag() {
  local arg next="" tag=""
  for arg in "$@"; do
    case "$arg" in
      -var=app_image_tag=*) tag=${arg#-var=app_image_tag=} ;;
      app_image_tag=*)      [ "$next" = "var" ] && tag=${arg#app_image_tag=} ;;
    esac
    case "$arg" in -var) next="var" ;; *) next="" ;; esac
  done
  if [ -n "$tag" ]; then
    printf '%s' "$tag"
    return 0
  fi

  # The later file wins, which is the order they are passed to Terraform below.
  local file found
  for file in "$var_file" "$local_var_file"; do
    [ -f "$file" ] || continue
    found=$(sed -n 's/^[[:space:]]*app_image_tag[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' "$file" | tail -1)
    [ -n "$found" ] && tag=$found
  done
  printf '%s' "$tag"
}

# Refuses an apply whose pinned image is not a commit on the trunk, or is behind its tip without
# `--allow-stale-image`. See the note at the top of this file for why it refuses rather than warns.
check_pinned_image_is_current() {
  local tag tip behind
  tag=$(effective_image_tag "$@")

  if [ -z "$tag" ]; then
    return 0
  fi

  if ! printf '%s' "$tag" | grep -Eq '^[0-9a-f]{40}$'; then
    echo "error: app_image_tag is '${tag}', which is not a commit SHA." >&2
    echo "  The tag is the only part of the container template that changes between releases, so a" >&2
    echo "  mutable one ('latest') renders an identical template, computes no diff and rolls no" >&2
    echo "  revision - the environment reports itself converged while serving the previous image." >&2
    echo "  Pin the 40-character SHA of the commit CD built and promoted." >&2
    exit 1
  fi

  if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    echo "==> skipping the pinned-image check: not a git checkout, so there is no trunk to compare against" >&2
    return 0
  fi

  tip=$(git rev-parse --verify --quiet origin/main || git rev-parse --verify --quiet main || true)
  if [ -z "$tip" ]; then
    echo "==> skipping the pinned-image check: no origin/main or main in this checkout" >&2
    return 0
  fi

  if ! git cat-file -e "${tag}^{commit}" 2>/dev/null; then
    echo "error: app_image_tag ${tag:0:12} names a commit this checkout does not have." >&2
    echo "  Either it is not a commit at all, or this clone is behind - try \`git fetch origin\`." >&2
    exit 1
  fi

  if ! git merge-base --is-ancestor "$tag" "$tip" 2>/dev/null; then
    echo "error: app_image_tag ${tag:0:12} is not an ancestor of ${tip:0:12} (origin/main)." >&2
    echo "  CD builds and promotes on the default branch only, so a tag off it is either an image" >&2
    echo "  that was never pushed or a commit that has since been rewritten. Neither is something" >&2
    echo "  this environment should be asked to run." >&2
    exit 1
  fi

  behind=$(git rev-list --count "${tag}..${tip}")
  if [ "$behind" -eq 0 ]; then
    echo "==> app_image_tag is origin/main's tip (${tag:0:12})"
    return 0
  fi

  if [ "$allow_stale_image" -eq 1 ]; then
    echo "==> app_image_tag ${tag:0:12} is ${behind} commit(s) behind origin/main, allowed by --allow-stale-image"
    return 0
  fi

  echo "error: app_image_tag ${tag:0:12} is ${behind} commit(s) behind origin/main (${tip:0:12})." >&2
  echo "  This apply would roll the Terraform and leave the containers on that older commit, and" >&2
  echo "  every check here would agree the environment matches its configuration - because it" >&2
  echo "  would. That is how ~25 merged pull requests once sat undeployed for a day." >&2
  echo >&2
  echo "  Either move the pin to the tip:" >&2
  echo "      app_image_tag = \"${tip}\"" >&2
  echo "  in ${local_var_file} (or ${var_file}), once CD has pushed that image - or, if this is" >&2
  echo "  deliberate (a rollback, or a merge whose image is still building), say so:" >&2
  echo "      $0 ${env_name} ${subcommand} --allow-stale-image" >&2
  exit 1
}


subcommand=$1
shift

# Before `terraform init`, which reaches the remote backend: refusing an apply after a round trip
# to Azure is slower and no more correct.
if [ "$subcommand" = "apply" ] && ! { [ $# -gt 0 ] && [ -f "$1" ]; }; then
  check_pinned_image_is_current "$@"
fi

terraform init -reconfigure -backend-config="key=${state_key}"

# apply and destroy take a saved plan file rather than -var-file; passing both is an error.
case "$subcommand" in
  plan|refresh|import|console)
    exec terraform "$subcommand" "${var_file_args[@]}" "$@"
    ;;
  apply|destroy)
    if [ $# -gt 0 ] && [ -f "$1" ]; then
      # A saved plan carries its own variable values, so the files above are not consulted and
      # there is nothing here to read the pin out of. Said out loud rather than passed over: a
      # check that did not do its work must not look like one that passed.
      [ "$subcommand" = "apply" ] && echo "==> skipping the pinned-image check: applying a saved plan, which carries its own app_image_tag" >&2
      terraform "$subcommand" "$@"
    else
      terraform "$subcommand" "${var_file_args[@]}" "$@"
    fi
    if [ "$subcommand" = "apply" ]; then
      check_deployed_contract
    fi
    ;;
  *)
    exec terraform "$subcommand" "$@"
    ;;
esac
