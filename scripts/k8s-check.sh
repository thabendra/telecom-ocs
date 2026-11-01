#!/usr/bin/env bash
# K8-6: checks the Kubernetes files with no cluster, only Docker: kustomize builds each overlay, kubeconform checks
# what comes out against the Kubernetes schemas, and the rule of K8-3 is worked out for each. The ExternalSecret has
# no schema in kubeconform, so it isn't checked. Exit code 0 only when all of it holds.
#
# Usage: scripts/k8s-check.sh   (from the project root)
set -uo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash on Windows: don't rewrite the container paths below

KUBECTL=registry.k8s.io/kubectl:v1.31.4
KUBECONFORM=ghcr.io/yannh/kubeconform:v0.6.7
ROOT="$(pwd -W 2>/dev/null || pwd)"
FAILED=0

fail() { echo "FAIL  $1"; FAILED=1; }

for target in base overlays/dev overlays/prod; do
  echo "=== k8s/$target"
  rendered="$(docker run --rm -v "$ROOT/k8s:/k8s:ro" "$KUBECTL" kustomize "/k8s/$target")" || { fail "kustomize $target"; continue; }

  echo "$rendered" | docker run --rm -i "$KUBECONFORM" -strict -summary -ignore-missing-schemas \
    -kubernetes-version 1.31.0 || fail "kubeconform $target"

  pool="$(echo "$rendered" | sed -n 's/^ *DB_POOL_SIZE: "\([0-9]*\)".*/\1/p' | head -1)"
  instances="$(echo "$rendered" | sed -n 's/^ *MAX_INSTANCES: "\([0-9]*\)".*/\1/p' | head -1)"
  max="$(echo "$rendered" | sed -n 's/^ *maxReplicas: \([0-9]*\).*/\1/p' | head -1)"
  sessions="$(echo "$rendered" | sed -n 's/^ *telecom.example\/oracle-sessions-for-backend: "\([0-9]*\)".*/\1/p' | head -1)"
  if [ -z "$pool" ] || [ -z "$instances" ] || [ -z "$max" ] || [ -z "$sessions" ]; then
    fail "a number for K8-3 is missing in $target"
    continue
  fi
  [ "$instances" = "$max" ] || fail "MAX_INSTANCES is $instances, maxReplicas is $max"
  if [ $((pool * max)) -le "$sessions" ]; then
    echo "K8-3  pool $pool x $max copies = $((pool * max)) <= $sessions Oracle sessions; MAX_INSTANCES $instances"
  else
    fail "$pool x $max is more than $sessions Oracle sessions"
  fi

  if [ "$target" = overlays/prod ] && echo "$rendered" | grep -q REPLACE_ME; then
    fail "a placeholder secret is still in prod"
  fi
done

[ "$FAILED" = 0 ] && echo "All good." || echo "Some checks failed."
exit "$FAILED"
