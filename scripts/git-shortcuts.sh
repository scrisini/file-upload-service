#!/usr/bin/env bash
# shellcheck shell=bash
#
# Git shortcuts for this repository's workflow (README "Contributing"). Each one fetches first,
# says exactly what it will change, and asks before changing anything (the default is no).
#
#   grebase-remote [branch]   rebase the current feature branch onto origin/<branch> (default: develop)
#   gbackmerge                back-merge main into develop: a pull request, merged with a merge commit
#   gshortcuts                list these
#
# To have them in every shell, source this file from ~/.zshrc or ~/.bashrc:
#
#   source /path/to/file-upload-service/scripts/git-shortcuts.sh
#
# or let it add that line (it asks first):   scripts/git-shortcuts.sh --install
#
# Settings (environment): GS_REMOTE (origin), GS_MAIN (main), GS_DEVELOP (develop).
# Works in zsh and bash (3.2 and later). Needs git 2.38+ to predict conflicts; gbackmerge needs the
# GitHub CLI (gh), signed in.

_gs_say() { printf '%s\n' "$*"; }
_gs_fail() { printf 'error: %s\n' "$*" >&2; return 1; }

# Asks a yes/no question; only y or yes (any case) means yes
_gs_confirm() {
    local answer
    printf '%s [y/N] ' "$1"
    read -r answer || return 1
    case "$answer" in
        y|Y|yes|Yes|YES) return 0 ;;
        *) return 1 ;;
    esac
}

# Indents a list, showing at most 10 lines and how many more there are
_gs_list() {
    awk -v max=10 'NR <= max { print "      " $0 } END { if (NR > max) print "      … and " (NR - max) " more" }'
}

# Files that would conflict merging $2 into $1 (empty when it merges cleanly); returns 2 if git is too old to say
_gs_conflicts() {
    local out
    if ! out="$(git merge-tree --write-tree --name-only --no-messages "$1" "$2" 2>/dev/null)"; then
        [ -z "$out" ] && return 2
        printf '%s\n' "$out" | sed '1d'
    fi
    return 0
}

_gs_in_repo() {
    git rev-parse --git-dir >/dev/null 2>&1 || _gs_fail "not in a git repository"
}

# grebase-remote [branch]: rebase the current feature branch onto <remote>/<branch>
grebase-remote() {
    local remote="${GS_REMOTE:-origin}" base="${1:-${GS_DEVELOP:-develop}}"
    local branch target incoming mine conflicts on_remote remote_only
    _gs_in_repo || return 1
    branch="$(git symbolic-ref --quiet --short HEAD)" || { _gs_fail "detached HEAD: check out your feature branch first"; return 1; }
    case "$branch" in
        "${GS_MAIN:-main}"|"${GS_DEVELOP:-develop}"|master)
            _gs_fail "$branch is a shared branch: rebasing it would rewrite history everyone has. Check out your feature branch."
            return 1 ;;
    esac
    if [ "$branch" = "$base" ]; then
        _gs_fail "you're on $base itself: check out the feature branch to rebase onto it"
        return 1
    fi
    if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
        _gs_fail "$branch has uncommitted changes: commit or stash them first (git stash), then run this again"
        return 1
    fi
    git fetch --quiet "$remote" || { _gs_fail "couldn't fetch from $remote"; return 1; }
    target="$remote/$base"
    git rev-parse --verify --quiet "$target" >/dev/null || { _gs_fail "$target doesn't exist"; return 1; }

    incoming="$(git rev-list --count "HEAD..$target")"
    mine="$(git rev-list --count "$target..HEAD")"
    if [ "$incoming" -eq 0 ]; then
        _gs_say "$branch already contains everything on $target: nothing to do."
        return 0
    fi

    _gs_say "Rebase $branch onto $target. What changes:"
    _gs_say "  - $incoming commit(s) from $target come into $branch:"
    git log --oneline "HEAD..$target" | _gs_list
    if [ "$mine" -eq 0 ]; then
        _gs_say "  - $branch has no commits of its own: it simply moves up to $target."
    else
        _gs_say "  - your $mine commit(s) are replayed on top of them, each getting a new id:"
        git log --oneline "$target..HEAD" | _gs_list
    fi
    conflicts="$(_gs_conflicts HEAD "$target")"
    case $? in
        2) _gs_say "  - (git is too old to predict conflicts)" ;;
        *) if [ -n "$conflicts" ]; then
               _gs_say "  - expect conflicts in these files; you'd resolve them commit by commit:"
               printf '%s\n' "$conflicts" | _gs_list
           else
               _gs_say "  - no conflicts expected."
           fi ;;
    esac
    on_remote="$(git ls-remote --heads "$remote" "$branch" 2>/dev/null)"
    if [ -n "$on_remote" ]; then
        _gs_say "  - $branch is pushed to $remote: afterwards its history differs from $remote/$branch, so"
        _gs_say "    pushing needs --force-with-lease, and anyone else using the branch must reset to it."
        git fetch --quiet "$remote" "$branch" 2>/dev/null
        remote_only="$(git rev-list --count "HEAD..$remote/$branch" 2>/dev/null || echo 0)"
        if [ "$remote_only" -gt 0 ]; then
            _gs_say "  - WARNING: $remote/$branch has $remote_only commit(s) you don't have locally. Pull them"
            _gs_say "    first (git pull), or a force-push would remove them."
        fi
    fi
    _gs_say "  - note: AGENTS.md \"Branching and merging\" prefers merging $target into a work branch"
    _gs_say "    (git merge $target), which keeps commit ids; a rebase suits branches no one else uses."
    _gs_confirm "Rebase $branch onto $target now?" || { _gs_say "Nothing changed."; return 0; }

    if ! git rebase "$target"; then
        _gs_say ""
        _gs_say "The rebase stopped on a conflict. Fix the files, git add them, then git rebase --continue."
        _gs_say "To go back to exactly where you were: git rebase --abort"
        return 1
    fi
    _gs_say "Rebased: $branch now sits on $target."
    if [ -n "$on_remote" ]; then
        if _gs_confirm "Push $branch to $remote with --force-with-lease (replaces its history there)?"; then
            git push --force-with-lease "$remote" "$branch"
        else
            _gs_say "Not pushed. When ready: git push --force-with-lease $remote $branch"
        fi
    fi
}

# gbackmerge: back-merge main into develop through a pull request, merged with a merge commit
gbackmerge() {
    local remote="${GS_REMOTE:-origin}" main="${GS_MAIN:-main}" develop="${GS_DEVELOP:-develop}"
    local missing conflicts merged files pr url number
    _gs_in_repo || return 1
    command -v gh >/dev/null 2>&1 || { _gs_fail "gbackmerge needs the GitHub CLI (gh), signed in"; return 1; }
    git fetch --quiet "$remote" "$main" "$develop" || { _gs_fail "couldn't fetch $main and $develop from $remote"; return 1; }

    missing="$(git rev-list --count "$remote/$develop..$remote/$main")"
    if [ "$missing" -eq 0 ]; then
        _gs_say "$develop already has every commit on $main: no back-merge needed."
        return 0
    fi
    conflicts="$(_gs_conflicts "$remote/$develop" "$remote/$main")"
    if [ $? -ne 2 ] && [ -n "$conflicts" ]; then
        _gs_say "Back-merging $main into $develop would conflict in:"
        printf '%s\n' "$conflicts" | _gs_list
        _gs_say "GitHub can't merge a pull request from $main with conflicts, and $main mustn't be committed"
        _gs_say "to. Resolve them on a branch from $develop, then open the pull request from it:"
        _gs_say "  git switch -c chore/back-merge-$main $remote/$develop"
        _gs_say "  git merge $remote/$main        # resolve, git add, git commit"
        _gs_say "  git push -u $remote chore/back-merge-$main"
        _gs_say "  gh pr create --base $develop --title \"chore: back-merge $main into $develop\""
        _gs_say "Nothing changed."
        return 1
    fi
    merged="$(git merge-tree --write-tree "$remote/$develop" "$remote/$main" 2>/dev/null | sed -n 1p)"
    files="$(git diff --name-only "$remote/$develop" "$merged" 2>/dev/null)"

    _gs_say "Back-merge $main into $develop (README \"Branching and merging\"). What changes:"
    _gs_say "  - $missing commit(s) on $main that $develop lacks join $develop's history:"
    git log --oneline "$remote/$develop..$remote/$main" | _gs_list
    if [ -z "$files" ]; then
        _gs_say "  - files on $develop: none change (the release squash holds what $develop already has)."
    else
        _gs_say "  - files on $develop that change:"
        printf '%s\n' "$files" | _gs_list
    fi
    _gs_say "  - how: a pull request $main → $develop on GitHub, merged with a merge commit (never squash or"
    _gs_say "    rebase); $main itself isn't touched. GitHub may list files under \"changed\": those are what"
    _gs_say "    $main changed since the common commit."

    pr="$(gh pr list --base "$develop" --head "$main" --state open --json number --jq '.[] | .number' 2>/dev/null)"
    if [ -n "$pr" ]; then
        _gs_say "A back-merge pull request is already open: #$pr."
        number="$pr"
    else
        _gs_confirm "Open the pull request $main → $develop?" || { _gs_say "Nothing changed."; return 0; }
        url="$(gh pr create --base "$develop" --head "$main" --title "chore: back-merge $main into $develop" \
            --body "Back-merge after a release squash, so the next release from $develop doesn't replay work already released. Merge with a **merge commit**.")" \
            || { _gs_fail "couldn't open the pull request"; return 1; }
        _gs_say "Opened $url"
        number="${url##*/}"
    fi
    _gs_confirm "Merge #$number into $develop now, with a merge commit?" || { _gs_say "Left open: #$number."; return 0; }
    gh pr merge "$number" --merge || { _gs_fail "couldn't merge #$number (see above); it's still open"; return 1; }
    git fetch --quiet "$remote" "$develop"
    if [ "$(git symbolic-ref --quiet --short HEAD)" = "$develop" ] && [ -z "$(git status --porcelain --untracked-files=no)" ]; then
        git merge --ff-only --quiet "$remote/$develop" && _gs_say "Merged; your local $develop is up to date."
    else
        _gs_say "Merged. Update your local $develop when you're on it: git pull --ff-only"
    fi
}

gshortcuts() {
    _gs_say "grebase-remote [branch]   rebase the current feature branch onto ${GS_REMOTE:-origin}/<branch> (default ${GS_DEVELOP:-develop})"
    _gs_say "gbackmerge                back-merge ${GS_MAIN:-main} into ${GS_DEVELOP:-develop}: a pull request with a merge commit"
    _gs_say "Each says what it will change and asks first."
}

# Run as a script (not sourced): --install adds the source line to your shell's startup file
_gs_install() {
    local self rc line
    self="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
    case "$(basename "${SHELL:-}")" in
        zsh) rc="$HOME/.zshrc" ;;
        bash) rc="$HOME/.bashrc"; [ "$(uname)" = Darwin ] && rc="$HOME/.bash_profile" ;;
        *) _gs_fail "unknown shell '${SHELL:-}': add this line to its startup file yourself: source \"$self\""; return 1 ;;
    esac
    line="source \"$self\"  # git shortcuts: grebase-remote, gbackmerge"
    if [ -f "$rc" ] && grep -Fq "$self" "$rc"; then
        _gs_say "$rc already sources $self: nothing to do."
        return 0
    fi
    _gs_say "This adds one line to the end of $rc:"
    _gs_say "  $line"
    _gs_say "New shells then have grebase-remote, gbackmerge and gshortcuts. Nothing else changes."
    _gs_confirm "Add it?" || { _gs_say "Nothing changed."; return 0; }
    printf '\n%s\n' "$line" >> "$rc"
    _gs_say "Added. Open a new shell, or run: source \"$rc\""
}

if [ -n "${BASH_VERSION:-}" ] && [ "${BASH_SOURCE[0]}" = "$0" ]; then
    case "${1:-}" in
        --install) _gs_install ;;
        *) sed -n '3,22p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
    esac
fi
