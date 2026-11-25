#!/usr/bin/env bash
# Steps shared by the jobs of the "Fork Release" workflow, see FORK.md.
#
# Usage: fork-release.sh <merge|google-services|report|close-report>
# Environment: UPSTREAM_REPOSITORY, UPSTREAM_TAG, REPORT_TITLE and the default GitHub Actions
# variables. `report` and `close-report` also need GH_TOKEN, `report` needs RUN_URL.
set -euo pipefail

REPORT="$RUNNER_TEMP/report.md"

# Merges the upstream release into the checked out branch. Nothing is pushed: every job redoes the
# same merge of the same two commits, so they all work on the same tree.
merge() {
  git config user.name "github-actions[bot]"
  git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
  git fetch --no-tags "https://github.com/$UPSTREAM_REPOSITORY.git" \
    "refs/tags/$UPSTREAM_TAG:refs/upstream/$UPSTREAM_TAG"
  if ! git merge --no-edit "refs/upstream/$UPSTREAM_TAG"; then
    {
      echo "### Merge conflict with upstream $UPSTREAM_TAG"
      echo
      echo "Merge \`$UPSTREAM_TAG\` of $UPSTREAM_REPOSITORY into \`$GITHUB_REF_NAME\` locally, resolve the conflicts and push."
      echo
      echo '```'
      git diff --name-only --diff-filter=U
      echo '```'
    } > "$REPORT"
    exit 1
  fi
}

# Writes app/google-services.json from the GOOGLE_SERVICES secret when there is one.
# Without a Firebase project of its own the fork is built with placeholder values: everything works
# but the push notifications delivered through Firebase. The upstream application IDs are kept next
# to the ones of the fork for the unit tests.
google_services() {
  local application_id
  application_id="$(grep -E '^homeAssistantAndroidApplicationId=' gradle.properties | cut -d= -f2)"
  if [[ -n "${GOOGLE_SERVICES:-}" ]]; then
    echo "$GOOGLE_SERVICES" > app/google-services.json
  else
    jq --arg id "${application_id:-io.homeassistant.companion.android}" \
      '.client += [.client[] | .client_info.android_client_info.package_name |= sub("^io\\.homeassistant\\.companion\\.android"; $id)]' \
      .github/mock-google-services.json > app/google-services.json
  fi
}

open_report_issue() {
  gh issue list --repo "$GITHUB_REPOSITORY" --state open --search "in:title \"$REPORT_TITLE\"" \
    --json number --jq '.[0].number' 2> /dev/null || true
}

# Reports why nothing was published: in the run summary and, as a best effort, in an issue (issues
# are disabled by default on a fork).
report() {
  [[ -f "$REPORT" ]] || echo "### Fork release of upstream $UPSTREAM_TAG failed in the \"$GITHUB_JOB\" job" > "$REPORT"
  printf '\nRun: %s\n' "$RUN_URL" >> "$REPORT"
  cat "$REPORT" >> "$GITHUB_STEP_SUMMARY"

  local issue
  issue="$(open_report_issue)"
  if [[ -n "$issue" ]]; then
    gh issue comment "$issue" --repo "$GITHUB_REPOSITORY" --body-file "$REPORT" || true
  else
    gh issue create --repo "$GITHUB_REPOSITORY" --title "$REPORT_TITLE" --body-file "$REPORT" || true
  fi
}

close_report() {
  local issue
  issue="$(open_report_issue)"
  if [[ -n "$issue" ]]; then
    gh issue close "$issue" --repo "$GITHUB_REPOSITORY" --comment "Fixed, $RELEASE_TAG was published." || true
  fi
}

case "${1:-}" in
  merge) merge ;;
  google-services) google_services ;;
  report) report ;;
  close-report) close_report ;;
  *)
    echo "Unknown command '${1:-}'" >&2
    exit 2
    ;;
esac
