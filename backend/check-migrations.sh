#!/bin/bash
# Fails the build if two migration files claim the same Flyway version number
# (e.g. two different "V3__..." files). This is exactly the class of bug that
# previously shipped broken and only surfaced as a runtime crash after a long
# docker build - catching it here means `docker compose up -d --build` fails
# fast with a clear message instead of producing a jar that crashes on boot.
set -euo pipefail

MIGRATION_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/src/main/resources/db/migration" && pwd)"
cd "$MIGRATION_DIR"

shopt -s nullglob
files=(V*.sql)
shopt -u nullglob

if [ ${#files[@]} -eq 0 ]; then
    echo "check-migrations.sh: no migration files found in $MIGRATION_DIR - nothing to check."
    exit 0
fi

versions=()
for f in "${files[@]}"; do
    version=$(echo "$f" | sed -E 's/^V([0-9]+(_[0-9]+)*)__.*/\1/')
    versions+=("$version")
done

duplicates=$(printf '%s\n' "${versions[@]}" | sort | uniq -d)

if [ -n "$duplicates" ]; then
    echo "=============================================================="
    echo "BUILD FAILED: duplicate Flyway migration version(s) detected"
    echo "=============================================================="
    for dup in $duplicates; do
        echo ""
        echo "Version $dup is claimed by multiple files:"
        for f in "${files[@]}"; do
            v=$(echo "$f" | sed -E 's/^V([0-9]+(_[0-9]+)*)__.*/\1/')
            if [ "$v" == "$dup" ]; then
                echo "  - $f"
            fi
        done
    done
    echo ""
    echo "Fix: rename or remove one of the conflicting files so every"
    echo "migration has a unique version number, then rebuild."
    echo "=============================================================="
    exit 1
fi

echo "check-migrations.sh: ${#files[@]} migration file(s), all versions unique. OK."
