#!/usr/bin/env bash
# Betaflight as a program (SITL) for the DRAUPNE test bench, inside WSL (Ubuntu), as root:
#
#   wsl -d Ubuntu -u root -- bash sitl.sh build <version>            build one Betaflight tag (4.4.1, 4.5.5, 2025.12.5, 4.3.2)
#   wsl -d Ubuntu -u root -- bash sitl.sh run <version> <folder> [feed]
#                                                                    run it in that folder (its eeprom.bin is there) until
#                                                                    "stop"; started again whenever it ends by itself (save,
#                                                                    CLI exit, MSP reboot all end the process); feed: the
#                                                                    level-attitude feeder beside it
#   wsl -d Ubuntu -u root -- bash sitl.sh stop                       end the one that runs
#   wsl -d Ubuntu -u root -- bash sitl.sh status                     one line: running or not, which, where
#
# The PowerShell scripts beside this one (build.ps1, start.ps1, stop.ps1, status.ps1) are the way in from Windows.
# Everything lives under /opt/draupne-sitl: src (Betaflight's repository), tree/<version> (its checkout), <version>/
# (the program and how it was built). One SITL at a time: Betaflight's ports are fixed in its source (UART n on TCP
# 5760+n, motors out on UDP 9002, the simulator's feed in on UDP 9003).
set -u

HOME_DIR=${DRAUPNE_SITL_HOME:-/opt/draupne-sitl}
SRC=$HOME_DIR/src
TREES=$HOME_DIR/tree
REPO=https://github.com/betaflight/betaflight.git
LOCK=$HOME_DIR/run.lock
CURRENT=$HOME_DIR/current
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

say() { printf '%s\n' "$*"; }
die() { printf 'sitl: %s\n' "$*" >&2; exit 2; }

# The compiler each line of Betaflight is built with: the newest of Ubuntu's that builds it with Betaflight's own
# Makefile and flags (the options below aside). gcc 15 refuses 4.4 and 4.5 (a -Werror on vtx_table.c that it is the
# first to give); gcc 11's -Ofast build of 4.4.1 hangs at start (gcc 12, 13, 14 do not).
compiler_for() {
    case $1 in
        4.3.*|4.4.*|4.5.*) echo gcc-14 ;;
        *) echo gcc-15 ;;
    esac
}

# Betaflight's options a build has beside SITL's own: GPS (with it GPS rescue, NMEA, UBLOX), as the real drone's
# firmware has it - SITL leaves the GPS out by itself from 4.4 on (common_pre.h: "!defined(SITL)"), and a drone
# whose failsafe is GPS-RESCUE is not tried without it. 4.3's SITL takes GPS rescue out in its own target.h: there
# it stays out. 4.5 with GPS in SITL has one function of the attitude calculation SITL leaves out defined and not
# used (imuCalcGroundspeedGain): that one warning is no error there - the only warning relaxed, and only for 4.5.
options_for() {
    case $1 in
        4.3.*) echo "" ;;
        4.5.*) echo "-DUSE_GPS -Wno-error=unused-function" ;;
        *) echo "-DUSE_GPS" ;;
    esac
}

# What SITL's own glue needs to run as the real drone is set up; Betaflight's flight code is never touched.
# - 4.3: its SITL ends with a segmentation fault at start: systemInit reschedules the serial task before the
#   scheduler has tasks. Betaflight 4.4 took that line out; so does this.
# - 4.4 on: a GPS on a UART (the real drone has it on UART3) ends SITL with a segmentation fault at its first GPS
#   task: SITL's TCP "UART" has no function to set a baud rate or a mode (NULL in its table), and the GPS sets
#   both. Two that take the value and do nothing else are put in. Nothing answers on that UART's TCP port, so the
#   GPS has no fix - as the real drone's indoors.
# - 4.3, 4.4: an MSP answer leaves SITL only when its TCP thread wakes - on the next bytes that come in, or after
#   0.5 s (dyad_setUpdateTimeout). A request that waits for its answer waits half a second, where a board on USB
#   answers in a millisecond. Betaflight 4.5 made it 0.01 s; so does this.
patch_tree() {
    local v=$1 dir=$TREES/$1
    case $v in
        4.3.*|4.4.*)
            sed -i 's/dyad_setUpdateTimeout(0\.5f);/dyad_setUpdateTimeout(0.01f);/' "$dir/src/main/target/SITL/target.c"
            grep -q 'dyad_setUpdateTimeout(0.01f);' "$dir/src/main/target/SITL/target.c" || die "target.c is not as this patch knows it"
            printf '%s' "src/main/target/SITL/target.c: dyad_setUpdateTimeout 0.5 s -> 0.01 s (as Betaflight 4.5 has it); "
            ;;
    esac
    case $v in
        4.3.*)
            sed -i '/rescheduleTask(TASK_SERIAL, 1);/d' "$dir/src/main/target/SITL/target.c"
            echo "src/main/target/SITL/target.c: the line 'rescheduleTask(TASK_SERIAL, 1);' taken out (as Betaflight 4.4 did)"
            ;;
        *)
            local f; f=$(find "$dir/src" -name serial_tcp.c | head -1)
            grep -q 'serialSetBaudRate = NULL,' "$f" && grep -q 'setMode = NULL,' "$f" || die "$f is not as this patch knows it"
            sed -i -e 's/\.serialSetBaudRate = NULL,/.serialSetBaudRate = tcpSetBaudRate,/' -e 's/\.setMode = NULL,/.setMode = tcpSetMode,/' \
                -e '/^static const struct serialPortVTable tcpVTable = {/i\
// (the DRAUPNE bench) a TCP "UART" has no baud rate and no mode: what the GPS sets is taken and kept, no more\
static void tcpSetBaudRate(serialPort_t *instance, uint32_t baudRate) { instance->baudRate = baudRate; }\
static void tcpSetMode(serialPort_t *instance, portMode_e mode) { instance->mode = mode; }\
' "$f"
            echo "${f#"$dir"/}: serialSetBaudRate and setMode, NULL in the TCP UART's table, given functions that keep the value (the GPS calls both)"
            ;;
    esac
}

build() {
    local v=${1:?which version: 4.4.1, 4.5.5, 2025.12.5, 4.3.2}
    [[ $v =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "a version is three numbers (4.4.1), not '$v'"
    local cc; cc=$(compiler_for "$v")
    export DEBIAN_FRONTEND=noninteractive
    local need=""
    for p in make git "$cc" python3; do command -v "$p" >/dev/null || need="$need $p"; done
    command -v openssl >/dev/null || need="$need openssl"
    if [ -n "$need" ]; then
        say "installing:$need"
        nice apt-get update -qq && nice apt-get install -y -qq build-essential git python3 openssl "$cc" >/dev/null || die "apt-get could not install$need"
    fi
    mkdir -p "$HOME_DIR" "$TREES"
    if [ ! -d "$SRC/.git" ]; then
        say "cloning $REPO (no file contents until a tag is checked out)"
        nice git clone -q --filter=blob:none "$REPO" "$SRC" || die "git clone failed"
    fi
    if ! git -C "$SRC" rev-parse -q --verify "refs/tags/$v" >/dev/null; then
        nice git -C "$SRC" fetch -q --tags origin || die "git fetch failed"
        git -C "$SRC" rev-parse -q --verify "refs/tags/$v" >/dev/null || die "Betaflight has no tag $v"
    fi
    local dir=$TREES/$v
    if [ ! -d "$dir" ]; then
        git -C "$SRC" worktree prune
        nice git -C "$SRC" worktree add -q --detach "$dir" "$v" || die "git worktree add failed"
    fi
    # the tag as it is, every time: what an earlier build changed is put back first
    git -C "$dir" checkout -q --detach "$v" && git -C "$dir" reset -q --hard "$v" || die "could not check out $v"
    local patched; patched=$(patch_tree "$v")
    mkdir -p "$HOME_DIR/no-arm-sdk" "$HOME_DIR/no-config/configs"
    # SITL is built with the PC's own compiler; Betaflight's Makefile still asks for the ARM compiler unless its folder
    # exists (an empty one does), and 2025.12 for its board configs unless a configs folder exists (an empty one does)
    local goal=(TARGET=SITL)
    case $v in 4.*) ;; *) goal=(SITL "CONFIG_DIR=$HOME_DIR/no-config") ;; esac
    local options; options=$(options_for "$v")
    rm -rf "$dir/obj"
    local log=$HOME_DIR/build-$v.log
    say "building Betaflight $v SITL with $cc${options:+, options $options} (log: $log)"
    (cd "$dir" && nice -n 19 make "${goal[@]}" "ARM_SDK_DIR=$HOME_DIR/no-arm-sdk" "CROSS_CC=$cc" "EXTRA_FLAGS=$options" -j"$(nproc)" >"$log" 2>&1) \
        || { tail -20 "$log"; die "the build of $v failed"; }
    local elf=$dir/obj/main/betaflight_SITL.elf
    [ -x "$elf" ] || die "the build of $v made no $elf"
    mkdir -p "$HOME_DIR/$v"
    cp "$elf" "$HOME_DIR/$v/betaflight_SITL.elf"
    {
        echo "version=$v"
        echo "commit=$(git -C "$dir" rev-parse HEAD)"
        echo "compiler=$cc ($($cc --version | head -1))"
        echo "make=make ${goal[*]} ARM_SDK_DIR=$HOME_DIR/no-arm-sdk CROSS_CC=$cc EXTRA_FLAGS=$options"
        echo "options=${options:-none}"
        echo "patch=$patched"
        echo "built=$(date -Is)"
        echo "sha256=$(sha256sum "$HOME_DIR/$v/betaflight_SITL.elf" | cut -d' ' -f1)"
    } >"$HOME_DIR/$v/build.txt"
    cat "$HOME_DIR/$v/build.txt"
}

# The time of day to the millisecond, from bash's own clock (no program started for it).
now() {
    local t=$EPOCHREALTIME
    printf '%(%H:%M:%S)T.%s' "${t%.*}" "${t#*.}"
}

# What the program says, with the time, without its line for every word written to its eeprom.
stamp() {
    local line t
    while IFS= read -r line; do
        case $line in *FLASH_ProgramWord*) continue ;; esac
        t=$EPOCHREALTIME
        printf '%(%H:%M:%S)T.%s %s\n' "${t%.*}" "${t#*.}" "$line"
    done
}

running_pid() { pgrep -x betaflight_SITL | head -1; }

run() {
    local v=${1:?which version} folder=${2:?which folder} feed=${3:-}
    local elf=$HOME_DIR/$v/betaflight_SITL.elf
    [ -x "$elf" ] || die "Betaflight $v is not built: build.ps1 -Version $v"
    mkdir -p "$folder" || die "no folder $folder"
    exec 9>"$LOCK"
    flock -n 9 || die "in use: a SITL runs already ($(cat "$CURRENT" 2>/dev/null)). stop.ps1 ends it."
    if [ -n "$(running_pid)" ]; then die "in use: a betaflight_SITL runs that no supervisor holds (pid $(running_pid)): stop.ps1 ends it."; fi
    cd "$folder" || die "no folder $folder"
    rm -f stop
    echo "$folder" >"$CURRENT"
    echo "$$" >supervisor.pid
    echo "$v" >version.txt
    cp "$HOME_DIR/$v/build.txt" build.txt 2>/dev/null
    local feeder=""
    if [ "$feed" = feed ]; then
        python3 -I "$HERE/feeder.py" "$v" >feeder.log 2>&1 &
        feeder=$!
    fi
    local n=0 rc began crashes=0
    trap '' HUP
    while [ ! -e stop ]; do
        n=$((n + 1))
        echo "$(now) [supervisor] start $n of Betaflight $v in $folder" >>sitl.log
        echo "$n" >starts.txt
        began=$SECONDS
        # the feeder waits while Betaflight starts: a packet that comes before its sensors are set up ends it with a
        # segmentation fault (SITL's updateState writes to the fake accelerometer it has not made yet)
        if [ -n "$feeder" ]; then
            kill -STOP "$feeder" 2>/dev/null
            (sleep 1.5; kill -CONT "$feeder" 2>/dev/null) &
        fi
        stdbuf -oL -eL "$elf" </dev/null 2>&1 | stamp >>sitl.log
        rc=${PIPESTATUS[0]}
        echo "$(now) [supervisor] Betaflight ended, exit code $rc" >>sitl.log
        [ -e stop ] && break
        # exit code 0 is Betaflight's own restart (save, the CLI's exit, MSP reboot); anything else within seconds of
        # its start, five times in a row, is a board that cannot start with what its eeprom.bin holds: not run again
        if [ "$rc" != 0 ] && [ $((SECONDS - began)) -lt 5 ]; then crashes=$((crashes + 1)); else crashes=0; fi
        if [ $crashes -ge 5 ]; then
            echo "$(now) [supervisor] CRASHES AT START: ended 5 times in a row within seconds (last exit code $rc) - not started again; its eeprom.bin is kept" >>sitl.log
            break
        fi
        sleep 0.2
    done
    [ -n "$feeder" ] && kill "$feeder" 2>/dev/null
    rm -f "$CURRENT" supervisor.pid
    echo "$(now) [supervisor] stopped" >>sitl.log
}

stop() {
    local folder; folder=$(cat "$CURRENT" 2>/dev/null)
    [ -n "$folder" ] && touch "$folder/stop"
    local pid; pid=$(running_pid)
    [ -n "$pid" ] && kill "$pid"
    for _ in $(seq 50); do # the program gone and its supervisor too (the lock free), 5 s at most
        if [ -z "$(running_pid)" ] && flock -n "$LOCK" true 2>/dev/null; then break; fi
        sleep 0.1
    done
    pid=$(running_pid)
    [ -n "$pid" ] && kill -9 "$pid"
    pkill -f "$HERE/feeder.py" 2>/dev/null
    say "stopped${folder:+ ($folder)}"
}

status() {
    local pid; pid=$(running_pid)
    local folder; folder=$(cat "$CURRENT" 2>/dev/null)
    if [ -z "$pid" ]; then
        if [ -n "$folder" ] && flock -n "$LOCK" true 2>/dev/null; then
            rm -f "$CURRENT" # no supervisor holds the lock: left behind by one that was ended outright (wsl --terminate)
            folder=""
        fi
        say "not running${folder:+ (its supervisor is starting it again in $folder)}"
        return 1
    fi
    local v; v=$(cat "$folder/version.txt" 2>/dev/null)
    local ports; ports=$(ss -ltnH 2>/dev/null | awk '{print $4}' | grep -oE ':57[6-9][0-9]$' | tr -d : | sort -n | tr '\n' ' ')
    say "running: Betaflight ${v:-?} pid $pid, folder ${folder:-?}, starts $(cat "$folder/starts.txt" 2>/dev/null), TCP ${ports:-none}"
}

case ${1:-} in
    build) shift; build "$@" ;;
    run) shift; run "$@" ;;
    stop) stop ;;
    status) status ;;
    *) die "build <version> | run <version> <folder> [feed] | stop | status" ;;
esac
