#!/system/bin/sh
# 电视本地遥控鼠标守护脚本（无需 root、无需电脑）
#
# 用法（必须用「adb执行」，不能用「本机执行」）：
#   sh /data/local/tmp/tvmouse.sh diag    环境自检
#   sh /data/local/tmp/tvmouse.sh learn   打印 15 秒按键名
#   sh /data/local/tmp/tvmouse.sh run     前台运行
#   sh /data/local/tmp/tvmouse.sh start   后台运行
#   sh /data/local/tmp/tvmouse.sh stop    停止
#
# 环境变量：STEP 单步像素(默认48)  KEEPALIVE 1/0 是否保持指针可见
#
# 说明：本机每次进程派生约 50ms（sendevent 一次 50ms），所以采用
#      「按一次走一步」而不是逐帧移动；逐帧平滑需要原生程序（见 docs/architecture.md P3）。

KEY_DEV=${KEY_DEV:-/dev/input/event1}
MOUSE_DEV=${MOUSE_DEV:-/dev/input/event4}
STEP=${STEP:-48}
KEEPALIVE=${KEEPALIVE:-1}

BASE=/data/local/tmp
PIDF=$BASE/tvmouse.pid
TPIDF=$BASE/tvmouse_keep.pid
KEYLOG=$BASE/tvmouse.keys
CONF=$BASE/tvmouse.conf

# 读取用户保存的参数（STEP=64 之类），覆盖上面的默认值
[ -f $CONF ] && . $CONF

probe_() {
  echo "---- 环境自检 ----"
  echo "id      : $(id)"
  echo "enforce : $(getenforce 2>/dev/null)"
  echo "key dev : $(ls -l $KEY_DEV 2>&1)"
  err=$(timeout 1 getevent -lt $KEY_DEV 2>&1 >/dev/null)
  case "$err" in
    *ermission*|*enied*) echo "读按键  : 失败（没 input 组权限，请用「adb执行」）" ;;
    *)                   echo "读按键  : OK" ;;
  esac
  if [ -w $MOUSE_DEV ]; then echo "写鼠标  : 有写权限"; else echo "写鼠标  : 无写权限"; fi
  echo "-------------------"
}

mv_() { # dx dy
  if [ "$1" -ne 0 ]; then sendevent $MOUSE_DEV 2 0 $1; fi
  if [ "$2" -ne 0 ]; then sendevent $MOUSE_DEV 2 1 $2; fi
  sendevent $MOUSE_DEV 0 0 0
}

click_() {
  sendevent $MOUSE_DEV 1 272 1
  sendevent $MOUSE_DEV 0 0 0
  sendevent $MOUSE_DEV 1 272 0
  sendevent $MOUSE_DEV 0 0 0
}

# 系统指针闲置约 3-5 秒会自动隐藏；每秒做一次 1px 往返（净位移 0）保持可见
keepalive_() {
  while :; do
    sleep 1
    sendevent $MOUSE_DEV 2 0 1
    sendevent $MOUSE_DEV 0 0 0
    sendevent $MOUSE_DEV 2 0 -1
    sendevent $MOUSE_DEV 0 0 0
  done
}

run_() {
  echo $$ > $PIDF
  : > $KEYLOG
  if [ "$KEEPALIVE" != "0" ]; then
    keepalive_ &
    echo $! > $TPIDF
  fi
  echo "遥控鼠标已启动  key=$KEY_DEV mouse=$MOUSE_DEV step=$STEP keepalive=$KEEPALIVE"

  getevent -lt $KEY_DEV | while read -r line; do
    case "$line" in
      *KEY_UP*|*KEY_DOWN*|*KEY_LEFT*|*KEY_RIGHT*|*KEY_ENTER*|*KEY_DPAD*)
        echo "$line" >> $KEYLOG ;;
    esac
    case "$line" in
      *KEY_UP*DOWN*|*KEY_DPAD_UP*DOWN*)        mv_ 0 -$STEP ;;
      *KEY_DOWN*DOWN*|*KEY_DPAD_DOWN*DOWN*)    mv_ 0 $STEP ;;
      *KEY_LEFT*DOWN*|*KEY_DPAD_LEFT*DOWN*)    mv_ -$STEP 0 ;;
      *KEY_RIGHT*DOWN*|*KEY_DPAD_RIGHT*DOWN*)  mv_ $STEP 0 ;;
      *KEY_ENTER*DOWN*|*KEY_DPAD_CENTER*DOWN*) click_ ;;
    esac
  done
}

stop_() {
  [ -f $TPIDF ] && kill $(cat $TPIDF) 2>/dev/null && rm -f $TPIDF
  [ -f $PIDF ] && kill $(cat $PIDF) 2>/dev/null && rm -f $PIDF
  rm -f $BASE/.tvmouse_dir
  echo "已停止"
}

case "${1:-run}" in
  diag)  probe_ ;;
  learn)
    probe_
    echo "监听 $KEY_DEV 15 秒，请按遥控器方向键/OK..."
    timeout 15 getevent -lt $KEY_DEV
    echo "---- 结束 ----"
    ;;
  run)   run_ ;;
  start)
    if [ -f $PIDF ] && kill -0 "$(cat $PIDF)" 2>/dev/null; then
      echo "已在运行 pid=$(cat $PIDF)"; exit 0
    fi
    # setsid 让它脱离 adb shell 的会话，adb 断开后仍继续运行
    if which setsid >/dev/null 2>&1; then
      setsid sh /data/local/tmp/tvmouse.sh run > /data/local/tmp/tvmouse.log 2>&1 &
    else
      nohup sh /data/local/tmp/tvmouse.sh run > /data/local/tmp/tvmouse.log 2>&1 &
    fi
    sleep 1
    if [ -f $PIDF ]; then echo "已在后台启动 pid=$(cat $PIDF)"; else echo "启动失败，见 /data/local/tmp/tvmouse.log"; fi
    ;;
  stop)  stop_ ;;
  set)
    [ -z "${2:-}" ] && { echo "用法: sh $0 set STEP 64"; exit 1; }
    echo "$2=${3}" >> $CONF
    echo "已保存 $2=${3}（执行 stop 再 start 生效）"
    ;;
  *)     echo "用法: sh $0 {diag|learn|run|start|stop|set}" ;;
esac
