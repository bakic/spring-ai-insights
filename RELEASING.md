# Releasing

Releases go to Maven Central through the [Central Portal](https://central.sonatype.com/).
The `Release` workflow (`.github/workflows/release.yml`) publishes when a `v*` tag is pushed.
Six modules are published: `core`, `mongodb`, `bedrock`, `web`, `autoconfigure` and `starter`.
The `observability` project and the demo are never published.

## One-time setup

1. Create a [Central Portal account](https://central.sonatype.org/register/central-portal/#create-an-account).
2. Verify the namespace that matches the group ID in `gradle.properties` (`group=io.github.bakic`).
   An `io.github.<name>` namespace can only be verified by whoever owns the GitHub user or organization `<name>`.
   To publish under another namespace, change `group` and `projectUrl` in `gradle.properties` first.
3. [Create a GPG key pair](https://central.sonatype.org/publish/requirements/gpg/#generating-a-key-pair) and
   [publish the public key](https://central.sonatype.org/publish/requirements/gpg/#distributing-your-public-key)
   to a key server. Central rejects artifacts whose signing key it cannot find.
4. [Generate a Portal user token](https://central.sonatype.org/publish/generate-portal-token/).
   The token, not your login, is the username and password for publishing.
5. Create a GitHub environment named `release` (Settings → Environments). Tick **Required reviewers**, add
   yourself or another maintainer, and click **Save protection rules**; the reviewer is not stored until you do.
   Under "Deployment branches and tags" choose "Selected branches and tags" and add a rule of type **Tag** with the
   pattern `v*`. The release job runs in this environment, so its secrets are not available to other workflows or
   branches. Check that both rules are saved, because without the reviewer a pushed tag publishes at once:

   ```shell
   gh api repos/<owner>/<repo>/environments/release --jq '[.protection_rules[].type]'
   # ["branch_policy","required_reviewers"]
   ```

6. Add a tag ruleset (Settings → Rules → Rulesets → New tag ruleset), target pattern `v*`, with **Restrict
   creations**, **Restrict updates** and **Restrict deletions**. Add the maintainers to the bypass list first;
   otherwise nobody, including you, can create a release tag.
7. Protect `main` (Settings → Rules → Rulesets → New branch ruleset, target the default branch): restrict
   deletions, block force pushes and require the status checks `build (mongo:7.0)` and `build (mongo:8.0)`.
   Releases are cut from `main` only, so this is the branch whose history you vouch for.
8. Add four secrets **to the `release` environment** (not as repository secrets):

   | Secret                   | Value                                                       |
   |--------------------------|-------------------------------------------------------------|
   | `MAVEN_CENTRAL_USERNAME` | Portal user token username                                  |
   | `MAVEN_CENTRAL_PASSWORD` | Portal user token password                                  |
   | `SIGNING_KEY`            | Output of `gpg --export-secret-keys --armor <key id>`, whole |
   | `SIGNING_KEY_PASSWORD`   | Passphrase of that key                                      |

## Dry run

```shell
./gradlew verifyPublication
```

This publishes the six modules to `build/staging-repo` and fails if anything Central requires is missing:
sources and javadoc jars, Gradle module metadata, and POM name, description, URL, license, developer and SCM.
It also fails if test fixtures or any unexpected module get published. CI runs it on every push.

## Release steps

1. Set `version=X.Y.Z` (no `-SNAPSHOT`) in `gradle.properties`, commit, push to `main` and wait for CI to pass.
2. Tag and push the tag:

   ```shell
   git tag vX.Y.Z
   git push origin vX.Y.Z
   ```

3. Approve the run: Actions → Release → the run → **Review deployments** → tick `release` → **Approve and deploy**.
   Until then the run shows "Waiting". After approval the workflow refuses to publish when the tagged commit is
   not on `main`, when the tag does not equal `v` + `version`, or when the version is a SNAPSHOT.
   It builds, runs `verifyPublication`, then runs `publishAndReleaseToMavenCentral`, which uploads, waits for
   Central's validation and releases. **Do not cancel the run once the "Publish to Maven Central" step has
   started.** The upload is automatic, so Central releases the bundle even if the workflow is cancelled, and a
   re-run then fails with "is currently being published in another deployment".
4. Check that the release is live. Central's search index lags by an hour or more, so look at the repository itself;
   each of the six modules should return 200 for the jar, pom, module metadata and signature:

   ```shell
   for m in core mongodb bedrock web autoconfigure starter; do
     for f in jar pom module jar.asc; do
       curl -s -o /dev/null -w "$m $f %{http_code}\n" \
         https://repo1.maven.org/maven2/io/github/bakic/spring-ai-insights-$m/X.Y.Z/spring-ai-insights-$m-X.Y.Z.$f
     done
   done
   ```

   The deployment on the [Central Portal deployments page](https://central.sonatype.com/publishing/deployments)
   moves from *Publishing* to *Published* a few minutes after the files appear.
5. Set `version` to the next `-SNAPSHOT` and commit. The README uses a `<version>` placeholder, so it needs no change.

## When a release fails

A published version can never be reused or deleted on Central, so the fix depends on how far the run got.

- **Before the upload** (version check, build, `verifyPublication`): fix the cause, then delete and re-push the tag
  (`git push origin :refs/tags/vX.Y.Z`, `git tag -d vX.Y.Z`, tag again). You need to be on the tag ruleset's
  bypass list to delete a tag.
- **After the upload:** open the Portal deployments page first. If the deployment is *Publishing* or *Published*,
  the release succeeded whatever the workflow says; verify it as in step 4 and do not retag. If it is *Failed*
  or *Validated* but not released, drop it, fix the cause, then delete and re-push the tag.
- **A version that was released with a defect** stays on Central. Release a fixed `X.Y.Z+1` instead.

## CI runner

`ci.yml` runs on `ubuntu-22.04`, not `ubuntu-latest`. GitHub's newer runners may have Linux 6.19 or later, where
MongoDB 8.x refuses to start (SERVER-121912) and every Mongo test waits 60 seconds for a server that never comes
up. Move to a newer runner once MongoDB ships a fixed 8.x release. The release workflow only uses the default
`mongo:7.0` image, so it stays on `ubuntu-24.04`.
