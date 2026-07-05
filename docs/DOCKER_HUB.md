# Docker Hub Publish Blueprint

Authoritative recipe for publishing this backend to Docker Hub. The user's standing instruction (set 2026-06-13) is:

> Whenever I ask to "push current image to dockerhub", **recreate** the image from the current working tree and push it to Docker Hub **only**. Do **not** deploy to the `subzero` server and do not perform any other interactions.

"Recreate" means: remove the existing local tag, build a fresh image from the current source state, then push. Do not push a stale local image without rebuilding first. Stop once the push completes and the new image id/digest is confirmed.

> **Deploying to `subzero` is a separate, manual step the user runs themselves** — it is intentionally NOT part of this "push to dockerhub" flow anymore. Do not SSH to subzero, pull, or restart the app container as part of a push.

## Image identity

| Field | Value |
| --- | --- |
| Registry | `docker.io` (Docker Hub) |
| Repository | `developabror/orient-advertise-backend` |
| Tag | `latest` |
| Full reference | `developabror/orient-advertise-backend:latest` |
| Dockerfile | `./Dockerfile` (project root, multi-stage: temurin-21-jdk build → temurin-21-jre runtime) |
| Build context | project root (`.`) |
| BuildKit | required (cache mounts on `/root/.gradle`) |

## Blueprint commands

Run from the project root (`/home/user/Desktop/Orient Advertise/Orient-advertise-backend`):

```bash
# 1. Remove the existing local image tag so the next build cannot accidentally re-push stale bits.
docker rmi developabror/orient-advertise-backend:latest

# 2. Build a fresh image from the current working tree (uncommitted edits included).
#    BuildKit cache mounts keep the Gradle distribution + Maven cache warm across builds.
DOCKER_BUILDKIT=1 docker build -t developabror/orient-advertise-backend:latest .

# 3. Push to Docker Hub. Credentials already live in ~/.docker/config.json under the
#    developabror account — no `docker login` required on this machine.
docker push developabror/orient-advertise-backend:latest

# 4. Verify the local tag points at the new image id and confirm the pushed digest.
docker images developabror/orient-advertise-backend:latest \
  --format "table {{.Repository}}\t{{.Tag}}\t{{.ID}}\t{{.Size}}\t{{.CreatedSince}}"
```

That completes the push. **Do not deploy to subzero as part of this flow.**

## Deploying to subzero (separate, user-run step — NOT part of "push to dockerhub")

The user deploys to the `subzero` server manually when they choose to; it is no longer chained to a
push. For reference, the manual deploy is `docker compose pull app && docker compose up -d app` in
`~/orient-advertise` on subzero, with a disk guard first (the 8 GB box has hit 100% and crash-looped
postgres — never `docker image prune -a`). Full deploy/verify/rollback detail lives in
`DEPLOY-subzero.md` at the repo-set root. Only do this if the user explicitly asks to deploy/release
to subzero — not on a plain "push to dockerhub".

## Notes

- The Docker build copies sources from the working tree, not from git HEAD, so uncommitted changes are baked into the image. That is intentional — it lets the user ship in-progress fixes without a commit.
- `Dockerfile` skips the test suite (`-x test`); tests run separately in CI.
- The runtime stage installs `ffmpeg` because `FFmpegTranscoder` / `FFprobeVideoInspector` shell out to it. Do not strip this from the Dockerfile.
- If the push fails with auth errors, run `docker login` once with the `developabror` account; the token is stored in `~/.docker/config.json`.
- Layer reuse on push is normal — only the final boot-jar layer changes when only source code changed. A "Layer already exists" log line is not a sign that the image is stale.
