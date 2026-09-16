#!/usr/bin/env bash
set -euo pipefail

: "${CERTIFIER_PASSWORD:?Set the raw demo password outside this script}"
: "${ACCOUNTS_PASSWORD:?Set the raw demo password outside this script}"
: "${MANAGER_PASSWORD:?Set the raw demo password outside this script}"

base_url="${BASE_URL:-http://localhost:8080}"

ready="$(curl --fail --silent --show-error "$base_url/api/v1/health/ready")"
printf 'readiness: %s\n' "$ready"

for role in certifier accounts manager; do
  case "$role" in
    certifier) password_var=CERTIFIER_PASSWORD ;;
    accounts) password_var=ACCOUNTS_PASSWORD ;;
    manager) password_var=MANAGER_PASSWORD ;;
  esac
  password="${!password_var}"
  projects="$(curl --fail --silent --show-error --user "$role:$password" "$base_url/api/v1/projects")"
  project_id="$(printf '%s' "$projects" | cut -d '"' -f 6)"
  test -n "$project_id"
  milestones="$(curl --fail --silent --show-error --user "$role:$password" "$base_url/api/v1/projects/$project_id/milestones")"
  printf '%s projects: %s\n' "$role" "$projects"
  printf '%s milestones: %s\n' "$role" "$milestones"
done

test "$(curl --silent --output /dev/null --write-out '%{http_code}' "$base_url/api/v1/projects")" = 401
test "$(curl --silent --output /dev/null --write-out '%{http_code}' --user 'certifier:invalid' "$base_url/api/v1/projects")" = 401
printf '%s\n' 'missing and invalid credentials correctly returned 401'
