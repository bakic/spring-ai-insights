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
5. Add four repository secrets in GitHub (Settings → Secrets and variables → Actions):

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

1. Set `version=X.Y.Z` (no `-SNAPSHOT`) in `gradle.properties` and commit.
2. Tag and push the tag:

   ```shell
   git tag vX.Y.Z
   git push origin vX.Y.Z
   ```

   The workflow refuses to publish when the tag does not equal `v` + `version`, or when the version is a SNAPSHOT.
   It builds, runs `verifyPublication`, then runs `publishAndReleaseToMavenCentral`, which uploads, waits for
   Central's validation and releases. Artifacts become downloadable 10 to 30 minutes later.
3. Set `version` to the next `-SNAPSHOT` and commit.

If the workflow fails after the upload, look at the deployment on the
[Central Portal deployments page](https://central.sonatype.com/publishing/deployments): drop it, fix the cause,
delete and re-push the tag.
