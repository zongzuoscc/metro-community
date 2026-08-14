#!/usr/bin/env bash
set -euo pipefail

# 本脚本只负责监督三个 Java 进程；数据库、Redis、RabbitMQ、Elasticsearch 与可选 AI
# 容器仍由 docker compose 管理。任何一个进程异常退出时都会终止另外两个，避免本地留下
# 端口仍被占用但功能已经残缺的“半启动”环境。
project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
run_dir="${METRO_LOCAL_RUN_DIR:-${project_root}/target/local-services}"
java_bin="${JAVA_HOME:+${JAVA_HOME}/bin/}java"

backend_jar="${project_root}/target/community-0.0.1-SNAPSHOT-backend.jar"
agent_jar="${project_root}/target/community-0.0.1-SNAPSHOT-agent.jar"
worker_jar="${project_root}/target/community-0.0.1-SNAPSHOT-worker.jar"

if ! "${java_bin}" -version 2>&1 | head -n 1 | grep -q 'version "21'; then
  echo "需要 Java 21。请先设置 JAVA_HOME 后再运行。" >&2
  exit 2
fi

needs_package=false
if [[ ! -f "${backend_jar}" || ! -f "${agent_jar}" || ! -f "${worker_jar}" ]]; then
  needs_package=true
elif [[ -n "$(find "${project_root}/src" "${project_root}/pom.xml" -newer "${backend_jar}" -print -quit)" ]]; then
  # 角色 Jar 已存在但源码更新时也必须重新打包，避免看似启动成功却运行上一版代码。
  needs_package=true
fi
if [[ "${needs_package}" == true ]]; then
  (cd "${project_root}" && ./mvnw -DskipTests package)
fi

mkdir -p "${run_dir}"
pids=()

cleanup() {
  local pid
  for pid in "${pids[@]:-}"; do
    if kill -0 "${pid}" 2>/dev/null; then
      kill "${pid}" 2>/dev/null || true
    fi
  done
  for pid in "${pids[@]:-}"; do
    wait "${pid}" 2>/dev/null || true
  done
}
trap cleanup EXIT INT TERM

start_role() {
  local role="$1"
  local jar="$2"
  "${java_bin}" -jar "${jar}" >"${run_dir}/${role}.log" 2>&1 &
  pids+=("$!")
}

wait_for_health() {
  local role="$1"
  local endpoint="$2"
  local attempts=90
  while (( attempts > 0 )); do
    if curl --fail --silent --max-time 1 "${endpoint}" >/dev/null; then
      return 0
    fi
    attempts=$((attempts - 1))
    sleep 1
  done
  echo "${role} 未在 90 秒内就绪，最近日志如下：" >&2
  tail -n 80 "${run_dir}/${role}.log" >&2 || true
  return 1
}

start_role backend "${backend_jar}"
start_role agent "${agent_jar}"
start_role worker "${worker_jar}"

wait_for_health backend "http://127.0.0.1:18080/actuator/health"
wait_for_health agent "http://127.0.0.1:18081/actuator/health"
wait_for_health worker "http://127.0.0.1:18082/actuator/health"

echo "Backend、Agent、Worker 已分别在 18080、18081、18082 就绪。"
echo "日志目录：${run_dir}"

# macOS 自带 Bash 3.2 没有 wait -n；轮询 PID 可同时兼容 macOS 与新版 Linux。
while true; do
  for pid in "${pids[@]}"; do
    if ! kill -0 "${pid}" 2>/dev/null; then
      wait "${pid}"
      exit $?
    fi
  done
  sleep 1
done
