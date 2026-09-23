#!/usr/bin/env bash

# Usage: ./tf.sh dev plan | ./tf.sh dev plan -out=dev.tfplan && ./tf.sh dev apply dev.tfplan
set -euo pipefail

ENVIRONMENTS="dev uat prod"

usage() {
  echo "usage: $0 <${ENVIRONMENTS// /|}> <terraform-subcommand> [args...]" >&2
  exit 2
}

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

legacy=$(ls ./*.local.auto.tfvars 2>/dev/null || true)
if [ -n "$legacy" ]; then
  echo "error: Terraform auto-loads these on every run, whatever environment you asked for:" >&2
  echo "$legacy" | sed 's/^/  /' >&2
  echo "rename each to <env>.local.tfvars (drop the '.auto.'); this script passes the one that matches." >&2
  exit 1
fi

var_file_args=(-var-file="$var_file")
if [ -f "$local_var_file" ]; then
  var_file_args+=(-var-file="$local_var_file")
  echo "==> ${env_name}: state ${state_key}, vars ${var_file} + ${local_var_file}"
else
  echo "==> ${env_name}: state ${state_key}, vars ${var_file}"
fi


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

  local file found
  for file in "$var_file" "$local_var_file"; do
    [ -f "$file" ] || continue
    found=$(sed -n 's/^[[:space:]]*app_image_tag[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' "$file" | tail -1)
    [ -n "$found" ] && tag=$found
  done
  printf '%s' "$tag"
}

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

if [ "$subcommand" = "apply" ] && ! { [ $# -gt 0 ] && [ -f "$1" ]; }; then
  check_pinned_image_is_current "$@"
fi

terraform init -reconfigure -backend-config="key=${state_key}"

case "$subcommand" in
  plan|refresh|import|console)
    exec terraform "$subcommand" "${var_file_args[@]}" "$@"
    ;;
  apply|destroy)
    if [ $# -gt 0 ] && [ -f "$1" ]; then
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
