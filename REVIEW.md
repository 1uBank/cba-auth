# Review guidelines

## What matters most
Prioritize bugs that could break staging or production: logic errors,
unhandled failures, data loss, and security problems. Correctness comes
before style.

## Always flag as serious
- Hardcoded secrets: passwords, API keys, tokens, or cloud access keys
- Overly permissive access: open ports to 0.0.0.0/0, wildcard (*) permissions
- Destructive changes (deleting data, dropping tables, replacing infrastructure) with no clear rollback
- Missing error handling around external calls (APIs, queues, databases)
- Environment-specific values typed directly into code instead of config
- Changes to deployment or CI/CD files that the PR description doesn't explain
- Tests removed or weakened without a replacement

## Nits
- Report at most 3 minor or style comments per PR.
- Only mention them if they hurt readability or maintainability.

## Do not comment on
- Formatting, spacing, or import order (our linter handles it)
- Generated files, lockfiles, and vendored code
- Files under docs/

## Evidence
- When you claim code behaves incorrectly, cite the file and line.
  Don't infer behavior from names alone.
- If you are not confident a problem is real, say so. Don't present it as confirmed.

## Repo-specific checks
