#!/usr/bin/env bash
set -euo pipefail

OUT_DIR="${1:-qidongjiaoben/perf-data}"
INTERVAL_SECONDS="${2:-2}"
ROUNDS="${3:-60}"

mkdir -p "${OUT_DIR}"

CPU_MEM_CSV="${OUT_DIR}/cpu_mem.csv"
NET_CSV="${OUT_DIR}/net.csv"
DOCKER_CSV="${OUT_DIR}/docker.csv"
MYSQL_CSV="${OUT_DIR}/mysql.csv"

echo "timestamp,cpu_usage_percent,mem_usage_percent,load1,load5,load15" > "${CPU_MEM_CSV}"
echo "timestamp,iface,rx_bytes,tx_bytes" > "${NET_CSV}"
echo "timestamp,container,name,cpu_percent,mem_usage,mem_percent,net_io,block_io,pids" > "${DOCKER_CSV}"
echo "timestamp,mysql_cpu,mysql_mem,mysql_rss_kb" > "${MYSQL_CSV}"

collect_once() {
  local ts
  ts="$(date '+%F %T')"

  local cpu mem load
  cpu="$(top -bn1 | awk '/Cpu\(s\)/ {print 100-$8; exit}')"
  mem="$(free | awk '/Mem:/ {printf "%.2f", $3*100/$2}')"
  load="$(cat /proc/loadavg | awk '{print $1","$2","$3}')"
  echo "${ts},${cpu},${mem},${load}" >> "${CPU_MEM_CSV}"

  ip -s link | awk -v ts="${ts}" '
    /: e/ {iface=$2; gsub(":", "", iface)}
    /RX:/ {getline; rx=$1}
    /TX:/ {getline; tx=$1; if (iface != "") print ts "," iface "," rx "," tx}
  ' >> "${NET_CSV}" || true

  docker stats --no-stream --format "{{.Container}},{{.Name}},{{.CPUPerc}},{{.MemUsage}},{{.MemPerc}},{{.NetIO}},{{.BlockIO}},{{.PIDs}}" \
    | sed "s/^/${ts},/" >> "${DOCKER_CSV}" || true

  local mysql_line
  mysql_line="$(ps -C mysqld -o %cpu=,%mem=,rss= | awk 'NR==1{print $1","$2","$3}')"
  if [[ -z "${mysql_line}" ]]; then
    mysql_line="0,0,0"
  fi
  echo "${ts},${mysql_line}" >> "${MYSQL_CSV}"
}

for ((i=1; i<=ROUNDS; i++)); do
  collect_once
  sleep "${INTERVAL_SECONDS}"
done

echo "监控完成，结果目录: ${OUT_DIR}"
