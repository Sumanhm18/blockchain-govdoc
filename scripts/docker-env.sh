# Public development images use an isolated Docker CLI configuration.
# This avoids changing the user's Docker configuration or invoking credential helpers.
mkdir -p "$ROOT_DIR/.runtime/docker"
if [[ ! -f "$ROOT_DIR/.runtime/docker/config.json" ]]; then
  printf '{"auths":{}}\n' > "$ROOT_DIR/.runtime/docker/config.json"
fi
mkdir -p "$ROOT_DIR/.runtime/docker/cli-plugins"
for plugin in docker-compose docker-buildx; do
  for source in "/Applications/Docker.app/Contents/Resources/cli-plugins/$plugin" "$HOME/.docker/cli-plugins/$plugin"; do
    if [[ -x "$source" ]]; then ln -sf "$source" "$ROOT_DIR/.runtime/docker/cli-plugins/$plugin"; break; fi
  done
done
export DOCKER_CONFIG="$ROOT_DIR/.runtime/docker"
if [[ -z "${DOCKER_HOST:-}" ]]; then
  if [[ -S "$HOME/.docker/run/docker.sock" ]]; then
    export DOCKER_HOST="unix://$HOME/.docker/run/docker.sock"
  elif [[ -S "$HOME/.docker/desktop/docker.sock" ]]; then
    export DOCKER_HOST="unix://$HOME/.docker/desktop/docker.sock"
  fi
fi
