# Sourced by the benchmark scripts. Runs one client launch in the background and keeps a single
# status line up to date from its log until the launch exits.
#   watch_client <title> <log> radar -- command...
#   watch_client <title> <log> bench:<warmup s>:<measured s> -- command...

clock() { printf '%d:%02d' $(($1 / 60)) $(($1 % 60)); }

# On a terminal the line is redrawn in place. Otherwise only a changed status is printed.
_progress_last=""
draw() { # line [status without the clock]
  if [ -t 1 ]; then
    printf '\r\033[K%s' "$1"
  elif [ "${2:-$1}" != "$_progress_last" ]; then
    printf '%s\n' "$1"
  fi
  _progress_last="${2:-$1}"
}

# What the launch is doing, read from its log.
client_status() { # log kind connected_seconds
  local log="$1" kind="$2" since="$3"
  if grep -qF "autopilot] wrote" "$log" 2>/dev/null; then
    echo "writing results"
  elif [ "$kind" = radar ]; then
    local n phase
    n=$(grep -cF "autopilot] measuring " "$log" 2>/dev/null || true)
    if [ "${n:-0}" -gt 0 ]; then
      phase=$(grep -F "autopilot] measuring " "$log" | tail -1 | sed 's/.*measuring //')
      echo "$phase ($n/7)"
    elif [ "$since" -ge 0 ]; then
      echo "joining and settling"
    else
      echo "launching"
    fi
  else
    local warmup seconds
    IFS=: read -r _ warmup seconds <<< "$kind"
    if [ "$since" -lt 0 ]; then
      echo "launching"
    elif [ "$since" -lt "$warmup" ]; then
      echo "warmup $(clock "$since")/$(clock "$warmup")"
    elif [ "$since" -lt $((warmup + seconds)) ]; then
      echo "measuring $(clock $((since - warmup)))/$(clock "$seconds")"
    else
      echo "writing results"
    fi
  fi
}

watch_client() {
  local title="$1" log="$2" kind="$3"
  shift 4
  "$@" > "$log" 2>&1 &
  local pid=$! start=$SECONDS connected=-1 status rc=0
  trap 'kill "$pid" 2>/dev/null; wait "$pid" 2>/dev/null; printf "\n"; exit 130' INT TERM
  _progress_last=""
  while kill -0 "$pid" 2>/dev/null; do
    if [ "$connected" -lt 0 ] && grep -qF "Connecting to" "$log" 2>/dev/null; then
      connected=$SECONDS
    fi
    status=$(client_status "$log" "$kind" $((connected < 0 ? -1 : SECONDS - connected)))
    draw "$title - $status - $(clock $((SECONDS - start)))" "$status"
    sleep 1
  done
  wait "$pid" || rc=$?
  trap - INT TERM
  if [ "$rc" -eq 0 ]; then
    status="done"
  else
    status="failed (exit $rc, see $log)"
  fi
  draw "$title - $status - $(clock $((SECONDS - start)))" "$status"
  [ -t 1 ] && printf '\n'
  return "$rc"
}
