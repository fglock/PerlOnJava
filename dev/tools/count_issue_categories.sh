#!/usr/bin/env bash
set -euo pipefail

state=open
case "${1:-}" in
  '') ;;
  --all) state=all ;;
  -h|--help) printf 'Usage: %s [--all]\n' "$0"; exit 0 ;;
  *) printf 'Usage: %s [--all]\n' "$0" >&2; exit 2 ;;
esac

gh issue list --state "$state" --limit 1000 --json labels |
  jq -r '
    [.[] | .labels[]?.name | select(startswith("area:"))]
    | group_by(.)
    | map({name: .[0], count: length})
    | sort_by(-.count, .name)
    | .[] | "\(.count)\t\(.name)"'
