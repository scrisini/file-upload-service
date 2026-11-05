#!/usr/bin/env bash
#
# Tests scripts/git-shortcuts.sh on throwaway repositories (a bare "origin" and a clone in a temp
# directory), in zsh and in bash, answering its questions. gh is replaced by a stub that records its
# calls, so nothing reaches GitHub. Run after changing git-shortcuts.sh:
#
#   scripts/test-git-shortcuts.sh

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SHORTCUTS="$ROOT/scripts/git-shortcuts.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
passed=0 failed=0

# A fake gh: records its arguments; `pr list` prints $GH_OPEN_PR; `pr create` prints a URL
mkdir -p "$WORK/bin"
cat > "$WORK/bin/gh" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$GH_LOG"
case "$1 $2" in
    "pr list") printf '%s' "${GH_OPEN_PR:-}" ;;
    "pr create") printf 'https://github.com/example/repo/pull/42\n' ;;
esac
EOF
chmod +x "$WORK/bin/gh"

git_() { git -c user.name=Test -c user.email=test@example.com -c init.defaultBranch=main -c advice.detachedHead=false "$@"; }

commit() {  # commit <repo> <file> <content> <message>
    printf '%s\n' "$3" > "$1/$2"
    git_ -C "$1" add "$2" >/dev/null
    git_ -C "$1" commit -q -m "$4"
}

# A fresh origin (main and develop, one commit each side) and a clone on feature/x with one commit
fixture() {
    rm -rf "$WORK/origin.git" "$WORK/seed" "$WORK/clone"
    git_ init -q --bare "$WORK/origin.git"
    git_ clone -q "$WORK/origin.git" "$WORK/seed" 2>/dev/null
    commit "$WORK/seed" README.md "hello" "chore: start"
    git_ -C "$WORK/seed" push -q origin main
    git_ -C "$WORK/seed" switch -q -c develop
    commit "$WORK/seed" app.txt "v1" "feat: app"
    git_ -C "$WORK/seed" push -q origin develop
    git_ clone -q "$WORK/origin.git" "$WORK/clone" 2>/dev/null
    git_ -C "$WORK/clone" switch -q -c feature/x origin/develop
    commit "$WORK/clone" feature.txt "mine" "feat: my change"
    git -C "$WORK/clone" config user.name Test
    git -C "$WORK/clone" config user.email test@example.com
}

# A commit on origin's <branch>, made through the seed clone
upstream_commit() {  # upstream_commit <branch> <file> <content> <message>
    git_ -C "$WORK/seed" fetch -q origin
    git_ -C "$WORK/seed" switch -q "$1" 2>/dev/null || git_ -C "$WORK/seed" switch -q -c "$1" "origin/$1"
    git_ -C "$WORK/seed" reset -q --hard "origin/$1"
    commit "$WORK/seed" "$2" "$3" "$4"
    git_ -C "$WORK/seed" push -q origin "$1"
}

# run <shell> <answers> <command...>: sources the shortcuts in the clone, types the answers
run() {
    local shell="$1" answers="$2"
    shift 2
    local flags=(-c)
    [ "$shell" = zsh ] && flags=(-f -c)
    set +e
    output="$(cd "$WORK/clone" && printf '%b' "$answers" | PATH="$WORK/bin:$PATH" GH_LOG="$WORK/gh.log" \
        "$shell" "${flags[@]}" "source '$SHORTCUTS'; $*" 2>&1)"
    status=$?
    set -e
}

check() {  # check <description> <condition...>
    local description="$1"
    shift
    if "$@"; then
        passed=$((passed + 1))
    else
        failed=$((failed + 1))
        printf 'FAIL [%s] %s\n--- output:\n%s\n---\n' "$shell" "$description" "$output"
    fi
}
says() { case "$output" in *"$1"*) return 0 ;; *) return 1 ;; esac; }
head_of() { git -C "$WORK/clone" rev-parse "$1"; }
contains() { git -C "$WORK/clone" merge-base --is-ancestor "$2" "$1"; }

for shell in zsh bash; do
    command -v "$shell" >/dev/null || { echo "skipping $shell: not installed"; continue; }

    # --- grebase-remote: refusals
    fixture
    git -C "$WORK/clone" switch -q develop 2>/dev/null || git -C "$WORK/clone" switch -q -c develop origin/develop
    run "$shell" "" grebase-remote
    check "refuses on develop" [ "$status" -ne 0 ]
    check "says develop is shared" says "shared branch"

    fixture
    echo dirty >> "$WORK/clone/feature.txt"
    run "$shell" "y\n" grebase-remote
    check "refuses with uncommitted changes" [ "$status" -ne 0 ]
    check "says to commit or stash" says "commit or stash"

    fixture
    run "$shell" "" grebase-remote
    check "nothing to do when up to date" says "nothing to do"
    check "nothing to do exits 0" [ "$status" -eq 0 ]

    # --- grebase-remote: impact, then no
    fixture
    upstream_commit develop other.txt "theirs" "feat: someone else's change"
    before="$(head_of HEAD)"
    run "$shell" "n\n" grebase-remote
    check "shows incoming commits" says "someone else's change"
    check "shows own commits replayed" says "my change"
    check "predicts no conflicts" says "no conflicts expected"
    check "asks before rebasing" says "Rebase feature/x onto origin/develop now? [y/N]"
    check "no answer changes nothing" [ "$(head_of HEAD)" = "$before" ]
    check "says nothing changed" says "Nothing changed."

    # --- grebase-remote: yes
    run "$shell" "y\n" grebase-remote
    check "rebase exits 0" [ "$status" -eq 0 ]
    check "branch now contains origin/develop" contains HEAD origin/develop
    check "own commit replayed with a new id" [ "$(head_of HEAD)" != "$before" ]
    check "own commit kept" [ "$(git -C "$WORK/clone" log -1 --format=%s)" = "feat: my change" ]

    # --- grebase-remote: predicted conflict; the rebase stops and explains
    fixture
    upstream_commit develop feature.txt "theirs" "feat: same file upstream"
    run "$shell" "y\n" grebase-remote
    check "predicts the conflicting file" says "feature.txt"
    check "stops on the conflict" [ "$status" -ne 0 ]
    check "explains continue and abort" says "git rebase --abort"
    git -C "$WORK/clone" rebase --abort 2>/dev/null || true

    # --- grebase-remote: a pushed branch
    fixture
    git_ -C "$WORK/clone" push -q origin feature/x
    upstream_commit develop other.txt "theirs" "feat: upstream"
    upstream_commit feature/x pair.txt "pair" "feat: a colleague's commit"
    run "$shell" "n\n" grebase-remote
    check "warns about force-with-lease" says "--force-with-lease"
    check "warns about commits only on origin" says "you don't have locally"
    git_ -C "$WORK/clone" pull -q --rebase origin feature/x 2>/dev/null
    run "$shell" "y\nn\n" grebase-remote
    check "asks before force-pushing" says "Push feature/x to origin with --force-with-lease"
    check "doesn't push on no" says "Not pushed"
    check "origin's branch untouched on no" [ "$(git -C "$WORK/clone" rev-parse origin/feature/x)" = "$(git -C "$WORK/origin.git" rev-parse feature/x)" ]

    # --- gbackmerge
    fixture
    : > "$WORK/gh.log"
    run "$shell" "" gbackmerge
    check "nothing to back-merge" says "no back-merge needed"
    check "no gh calls when nothing to do" [ ! -s "$WORK/gh.log" ]

    # a release squash on main, holding what develop already has
    git_ -C "$WORK/seed" fetch -q origin
    git_ -C "$WORK/seed" switch -q main && git_ -C "$WORK/seed" reset -q --hard origin/main
    git_ -C "$WORK/seed" merge -q --squash origin/develop >/dev/null
    git_ -C "$WORK/seed" commit -q -m "feat: app (release)"
    git_ -C "$WORK/seed" push -q origin main
    : > "$WORK/gh.log"
    run "$shell" "n\n" gbackmerge
    check "lists main's commits develop lacks" says "feat: app (release)"
    check "says no files change" says "none change"
    check "asks before opening the PR" says "Open the pull request main → develop? [y/N]"
    check "no PR opened on no" [ "$(grep -c 'pr create' "$WORK/gh.log")" -eq 0 ]

    : > "$WORK/gh.log"
    run "$shell" "y\ny\n" gbackmerge
    check "opens the PR main → develop" grep -q -- "pr create --base develop --head main --title chore: back-merge main into develop" "$WORK/gh.log"
    check "merges it with a merge commit" grep -q -- "pr merge 42 --merge" "$WORK/gh.log"

    : > "$WORK/gh.log"
    GH_OPEN_PR=7 run "$shell" "n\n" gbackmerge
    check "finds an open back-merge PR" says "already open: #7"
    check "doesn't open a second" [ "$(grep -c 'pr create' "$WORK/gh.log")" -eq 0 ]

    # conflicting histories: develop changed app.txt since, main changed it differently
    upstream_commit develop app.txt "v2-develop" "feat: develop's app"
    upstream_commit main app.txt "v2-main" "fix: hotfix on main"
    : > "$WORK/gh.log"
    run "$shell" "y\ny\n" gbackmerge
    check "conflict: lists the file" says "app.txt"
    check "conflict: explains the branch route" says "git switch -c chore/back-merge-main"
    check "conflict: exits non-zero" [ "$status" -ne 0 ]
    check "conflict: no PR opened" [ "$(grep -c 'pr create' "$WORK/gh.log")" -eq 0 ]
done

# --- --install (bash runs the file as a script)
export HOME="$WORK/home"
mkdir -p "$HOME"
shell=install
output="$(printf 'n\n' | SHELL=/bin/zsh bash "$SHORTCUTS" --install 2>&1)" || true
check "install asks and changes nothing on no" [ ! -s "$HOME/.zshrc" ]
output="$(printf 'y\n' | SHELL=/bin/zsh bash "$SHORTCUTS" --install 2>&1)" || true
check "install adds the source line" grep -Fq "source \"$SHORTCUTS\"" "$HOME/.zshrc"
output="$(printf 'y\n' | SHELL=/bin/zsh bash "$SHORTCUTS" --install 2>&1)" || true
check "install is idempotent" [ "$(grep -c "git-shortcuts.sh" "$HOME/.zshrc")" -eq 1 ]
check "install says nothing to do the second time" says "nothing to do"

printf '%d passed, %d failed\n' "$passed" "$failed"
[ "$failed" -eq 0 ]
