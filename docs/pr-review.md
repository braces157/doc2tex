# Automated pull request review

This repository configures [CodeRabbit](https://www.coderabbit.ai/) in
[.coderabbit.yaml](../.coderabbit.yaml). It reviews ready pull requests targeting
the default branch and updates its review when new commits are pushed. Drafts
are skipped. Feedback focuses on actionable bugs, data loss, security, and
conversion regressions, with guidance for the Java backend, React editor,
regression tests, setup scripts, and CI.

## Enable the GitHub app

1. Install the [CodeRabbit GitHub app](https://github.com/apps/coderabbitai) under
   the repository owner's account. Choose **Only select repositories** and select
   **braces157/doc2tex**. Review and grant the app's requested permissions.
2. Commit and push `.coderabbit.yaml` with this change.
3. Open a ready pull request targeting `main`. Confirm that CodeRabbit posts a
   review. For an existing pull request, comment `@coderabbitai review` after the
   app is enabled.

The YAML configures the bot; it does not install or authorize the GitHub app.
This integration needs no repository API-key secret or extra Actions workflow.
Available features and limits depend on the account's CodeRabbit plan.

## Working with reviews

- `@coderabbitai review`: request an incremental review.
- `@coderabbitai full review`: review all changes again.
- `@coderabbitai pause`: pause automatic reviews on the pull request.
- `@coderabbitai resume`: resume automatic reviews.

Reply to a review comment to ask for clarification. Verify suggestions before
applying them. Human maintainers decide whether to merge; automatic approval is
disabled in this configuration. Existing CI remains responsible for linting,
building, browser checks, and real PDF regression tests.

See the official [GitHub setup guide](https://docs.coderabbit.ai/platforms/github-com),
[configuration reference](https://docs.coderabbit.ai/reference/configuration), and
[review commands](https://docs.coderabbit.ai/guides/commands).
