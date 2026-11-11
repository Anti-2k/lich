# Shared by the local git hooks in this folder (pre-commit, commit-msg, pre-push); install them with
#   cp tools/git-hooks/* "$(git rev-parse --git-common-dir)/hooks/"
# which covers every worktree of the clone.
#
# The hooks refuse jars (except the Gradle wrapper's) and any text matching a line of
# <git common dir>/info/forbidden-patterns: one extended regex per line, case-insensitive, # starts a comment.
# That file belongs to the clone and is never versioned, so the words it guards never appear in the repository.

FORBIDDEN=$(mktemp)
trap 'rm -f "$FORBIDDEN"' EXIT
patterns_file="$(git rev-parse --git-common-dir)/info/forbidden-patterns"
if [ -f "$patterns_file" ]; then
    grep -vE '^[[:space:]]*(#|$)' "$patterns_file" | tr -d '\r' > "$FORBIDDEN"
fi

# Lines of stdin that match a forbidden pattern, with line numbers; nothing without a patterns file.
forbidden_hits() {
    if [ -s "$FORBIDDEN" ]; then grep -inE -f "$FORBIDDEN"; else cat > /dev/null; fi
}

# Paths on stdin that are jars, other than the Gradle wrapper's.
jar_hits() {
    grep -iE '\.jar$' | grep -vx 'gradle/wrapper/gradle-wrapper.jar'
}
