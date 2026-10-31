/*
 * tvmouse - 电视本地遥控鼠标守护进程（原生版）
 *
 * 读 /dev/input/event1（红外接收器），写 /dev/input/event4（鼠标设备）。
 *
 * 编译（NDK）：
 *   armv7a-linux-androideabi21-clang -O2 -o tvmouse tvmouse.c
 *
 * 用法：
 *   tvmouse [-g] [-s 最高速度] [-i 起步速度] [-h 光标保持秒数] [-b right|side|back] [-t 切换键] [-W 屏宽] [-H 屏高] [-k 键设备] [-m 鼠标设备] [-n]
 *   tvmouse -l [-k 键设备]      学习模式：只打印按键码，不做任何动作
 *
 * 映射文件（默认 /data/local/tmp/tvmouse.map），每行 `键码=动作`：
 *   158=right       返回键 → 鼠标右键
 *   139=wheelup     设置键 → 滚轮上
 *   动作：left right middle wheelup wheeldown back home pass none
 *
 * 非映射键的默认行为：方向键移动、OK 左键、HOME 走 input、其它键原样补发。
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <signal.h>
#include <poll.h>
#include <time.h>
#include <linux/input.h>
#include <sys/ioctl.h>

#define BACK_RIGHT 0
#define BACK_SIDE  1
#define BACK_REAL  2

#define ACT_NONE      0
#define ACT_LEFT      1
#define ACT_RIGHT     2
#define ACT_MIDDLE    3
#define ACT_WHEELUP   4
#define ACT_WHEELDOWN 5
#define ACT_BACK      6
#define ACT_HOME      7
#define ACT_PASS      8

#define MAX_MAP 64

static volatile int g_running = 1;
static void on_sig(int s) { (void)s; g_running = 0; }

static int g_mfd = -1;
static int g_back_mode = BACK_RIGHT;
static int g_toggle = 0;    /* 接管/释放切换键的键码，0=未设置 */
static int g_active = 1;    /* 当前是否处于接管状态 */
static int g_grabbed = 0;
static int g_pending_grab = 0;  /* 释放态按下切换键后，等抬起再重新抓取 */
static long long g_hide_ms = 15000;  /* 光标保持可见时长，0=永不隐藏 */
static double g_v0 = 30.0;           /* 起步速度 px/s：决定轻点一下走多远 */
static int g_screen_w = 1920;        /* 屏幕宽，-W 传入，用于滚轮贴边补偿 */
static int g_screen_h = 1080;        /* 屏幕高，-H 传入 */
static int g_pos_x = -1, g_pos_y = -1;  /* 估算光标位置，-1=未初始化 */
static int g_passthrough = 0;        /* -p：释放态仍独占，按键回放给红外设备 */
static long long g_skip_until = 0;   /* 回放期间忽略自己写回去的事件 */

/* 鼠标设备支持的 EV_KEY 位图：换接收器后常见"只支持鼠标按钮"的设备，
   往里写音量/返回等键会被内核静默丢弃，必须先查能力再决定怎么发 */
static unsigned char g_mkeys[(KEY_MAX + 7) / 8];
static int g_mkeys_ok = 0;

static int mouse_supports(int code) {
    if (!g_mkeys_ok || code < 0 || code > KEY_MAX) return 1;
    return (g_mkeys[code / 8] >> (code % 8)) & 1;
}

static struct { int code; int act; } g_map[MAX_MAP];
static int g_mapn = 0;

static long long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static void emit(int fd, int type, int code, int value) {
    struct input_event ev;
    memset(&ev, 0, sizeof ev);
    ev.type = type;
    ev.code = code;
    ev.value = value;
    if (write(fd, &ev, sizeof ev) != (ssize_t)sizeof ev) { /* 忽略 */ }
}

static void move_by(int fd, int dx, int dy) {
    if (dx) emit(fd, EV_REL, REL_X, dx);
    if (dy) emit(fd, EV_REL, REL_Y, dy);
    emit(fd, EV_SYN, SYN_REPORT, 0);
}

static void click_btn(int fd, int btn) {
    emit(fd, EV_KEY, btn, 1);
    emit(fd, EV_SYN, SYN_REPORT, 0);
    emit(fd, EV_KEY, btn, 0);
    emit(fd, EV_SYN, SYN_REPORT, 0);
}

static void wheel(int fd, int dir) {
    /* 贴边补偿：光标在屏幕最上/最下时，滚轮会落在状态栏/导航栏上导致无效，
       先往屏幕中间挪 1/4 屏，再发滚轮。 */
    if (g_pos_y >= 0 && g_screen_h > 0) {
        int quarter = g_screen_h / 4;
        if (g_pos_y < g_screen_h / 8) {
            move_by(fd, 0, quarter);
            g_pos_y += quarter;
        } else if (g_pos_y > g_screen_h * 7 / 8) {
            move_by(fd, 0, -quarter);
            g_pos_y -= quarter;
        }
    }
    emit(fd, EV_REL, REL_WHEEL, dir);
    emit(fd, EV_SYN, SYN_REPORT, 0);
}

/* Linux input 键码 → Android keycode（只列常用系统/媒体键） */
static int android_key(int c) {
    switch (c) {
        case KEY_BACK:            return 4;
        case KEY_HOME:
        case KEY_HOMEPAGE:        return 3;
        case KEY_MENU:            return 82;
        case KEY_POWER:           return 26;
        case KEY_PLAYPAUSE:       return 85;
        case KEY_STOP:            return 86;
        case KEY_NEXTSONG:        return 87;
        case KEY_PREVIOUSSONG:    return 88;
        case KEY_REWIND:          return 89;
        case KEY_FASTFORWARD:     return 90;
        case KEY_CHANNELUP:       return 166;
        case KEY_CHANNELDOWN:     return 167;
        case KEY_INFO:            return 165;
        default:                  return -1;
    }
}

/* 普通键补发：优先写鼠标设备；设备不支持该键时交给 App 处理。
   音量/静音一律交给 App 用 AudioManager 调，延迟远低于 input keyevent（实测 1.4s）。 */
static void pass_key(int code) {
    if (code == KEY_VOLUMEUP || code == KEY_VOLUMEDOWN || code == KEY_MUTE) {
        printf("VOL=%s\n", code == KEY_VOLUMEUP ? "up"
                         : code == KEY_VOLUMEDOWN ? "down" : "mute");
        fflush(stdout);
        return;
    }
    if (code == KEY_HOME || code == KEY_HOMEPAGE) {
        if (system("input keyevent 3 &") != 0) { /* 忽略 */ }
        return;
    }
    if (g_mfd < 0) return;
    if (!mouse_supports(code)) {
        /* 设备没有这个键的能力，写进去也无效：退回 input keyevent 注入（慢，仅低频键） */
        int a = android_key(code);
        if (a > 0) {
            char cmd[64];
            snprintf(cmd, sizeof cmd, "input keyevent %d &", a);
            if (system(cmd) != 0) { /* 忽略 */ }
        }
        return;
    }
    emit(g_mfd, EV_KEY, code, 1);
    emit(g_mfd, EV_SYN, SYN_REPORT, 0);
    emit(g_mfd, EV_KEY, code, 0);
    emit(g_mfd, EV_SYN, SYN_REPORT, 0);
}

static int act_from_name(const char *s) {
    if (!strcmp(s, "left")) return ACT_LEFT;
    if (!strcmp(s, "right")) return ACT_RIGHT;
    if (!strcmp(s, "middle")) return ACT_MIDDLE;
    if (!strcmp(s, "wheelup")) return ACT_WHEELUP;
    if (!strcmp(s, "wheeldown")) return ACT_WHEELDOWN;
    if (!strcmp(s, "back")) return ACT_BACK;
    if (!strcmp(s, "home")) return ACT_HOME;
    if (!strcmp(s, "pass")) return ACT_PASS;
    if (!strcmp(s, "none")) return ACT_NONE;
    return -1;
}

static void load_map(const char *path) {
    FILE *f = fopen(path, "r");
    if (!f) return;
    char line[160];
    while (fgets(line, sizeof line, f)) {
        int code;
        char name[32];
        if (sscanf(line, "%d=%31s", &code, name) == 2) {
            int act = act_from_name(name);
            if (act >= 0 && g_mapn < MAX_MAP) {
                g_map[g_mapn].code = code;
                g_map[g_mapn].act = act;
                g_mapn++;
            }
        }
    }
    fclose(f);
}

static int lookup(int code) {
    for (int i = 0; i < g_mapn; i++)
        if (g_map[i].code == code) return g_map[i].act;
    return -1;
}

static int default_action(int code) {
    switch (code) {
        case KEY_ENTER:
        case KEY_KPENTER: return ACT_LEFT;
        case KEY_BACK:
            if (g_back_mode == BACK_RIGHT) return ACT_RIGHT;
            if (g_back_mode == BACK_SIDE)  return ACT_MIDDLE;
            return ACT_BACK;
        case KEY_HOME:
        case KEY_HOMEPAGE: return ACT_HOME;
        default: return ACT_PASS;
    }
}

static void do_action(int act, int code) {
    switch (act) {
        case ACT_LEFT:      click_btn(g_mfd, BTN_LEFT); break;
        case ACT_RIGHT:     click_btn(g_mfd, BTN_RIGHT); break;
        case ACT_MIDDLE:    click_btn(g_mfd, BTN_MIDDLE); break;
        case ACT_WHEELUP:   wheel(g_mfd, 1); break;
        case ACT_WHEELDOWN: wheel(g_mfd, -1); break;
        case ACT_BACK:      pass_key(KEY_BACK); break;
        case ACT_HOME:      pass_key(KEY_HOME); break;
        case ACT_PASS:      pass_key(code); break;
        case ACT_NONE:      break;
    }
}

/* 学习模式：打印按键码，供 App 采集 */
static int learn_mode(const char *keydev) {
    int kfd = open(keydev, g_passthrough ? O_RDWR : O_RDONLY);
    if (kfd < 0) { fprintf(stderr, "open %s: %s\n", keydev, strerror(errno)); return 1; }
    signal(SIGTERM, on_sig);
    signal(SIGINT, on_sig);
    printf("学习模式：请按遥控器上的键\n");
    fflush(stdout);
    struct pollfd pfd = { kfd, POLLIN, 0 };
    struct input_event ev;
    while (g_running) {
        if (poll(&pfd, 1, 200) <= 0) continue;
        if (read(kfd, &ev, sizeof ev) != (ssize_t)sizeof ev) continue;
        if (ev.type != EV_KEY || ev.value != 1) continue;
        printf("KEY code=%d\n", ev.code);
        fflush(stdout);
    }
    ioctl(kfd, EVIOCGRAB, 0);
    close(kfd);
    return 0;
}

/* 扫描 /dev/input/event*，找带 REL_X/REL_Y + BTN_LEFT 的鼠标设备。
   换无线接收器后 event 编号会变，不能再硬编码 event4。 */
static const char *find_mouse_dev(char *buf, size_t buflen) {
    for (int i = 0; i < 32; i++) {
        snprintf(buf, buflen, "/dev/input/event%d", i);
        int fd = open(buf, O_RDWR | O_NONBLOCK);
        if (fd < 0) continue;
        unsigned long relbit[2] = {0}, keybit[16] = {0};
        int ok = ioctl(fd, EVIOCGBIT(EV_REL, sizeof relbit), relbit) >= 0
              && ioctl(fd, EVIOCGBIT(EV_KEY, sizeof keybit), keybit) >= 0
              && (relbit[0] & (1UL << REL_X))
              && (relbit[0] & (1UL << REL_Y))
              && (keybit[BTN_LEFT / (8 * sizeof(unsigned long))]
                  & (1UL << (BTN_LEFT % (8 * sizeof(unsigned long)))));
        close(fd);
        if (ok) return buf;
    }
    return NULL;
}

int main(int argc, char **argv) {
    const char *keydev = "/dev/input/event1";
    const char *mousedev = "/dev/input/event4";
    const char *mapfile = "/data/local/tmp/tvmouse.map";
    int grab = 0, keepalive = 1, learn = 0;
    double speed = 900.0;
    char mbuf[64];   /* 自动识别的鼠标设备路径，必须活到 main 结束 */

    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "-g")) grab = 1;
        else if (!strcmp(argv[i], "-n")) keepalive = 0;
        else if (!strcmp(argv[i], "-l")) learn = 1;
        else if (!strcmp(argv[i], "-s") && i + 1 < argc) speed = atof(argv[++i]);
        else if (!strcmp(argv[i], "-h") && i + 1 < argc) g_hide_ms = atoll(argv[++i]) * 1000LL;
        else if (!strcmp(argv[i], "-i") && i + 1 < argc) g_v0 = atof(argv[++i]);
        else if (!strcmp(argv[i], "-p")) g_passthrough = 1;
        else if (!strcmp(argv[i], "-c") && i + 1 < argc) mapfile = argv[++i];
        else if (!strcmp(argv[i], "-t") && i + 1 < argc) g_toggle = atoi(argv[++i]);
        else if (!strcmp(argv[i], "-W") && i + 1 < argc) g_screen_w = atoi(argv[++i]);
        else if (!strcmp(argv[i], "-H") && i + 1 < argc) g_screen_h = atoi(argv[++i]);
        else if (!strcmp(argv[i], "-b") && i + 1 < argc) {
            const char *m = argv[++i];
            if (!strcmp(m, "side")) g_back_mode = BACK_SIDE;
            else if (!strcmp(m, "back")) g_back_mode = BACK_REAL;
            else g_back_mode = BACK_RIGHT;
        }
        else if (!strcmp(argv[i], "-k") && i + 1 < argc) keydev = argv[++i];
        else if (!strcmp(argv[i], "-m") && i + 1 < argc) mousedev = argv[++i];
    }

    if (learn) return learn_mode(keydev);

    int kfd = open(keydev, g_passthrough ? O_RDWR : O_RDONLY);
    if (kfd < 0) { fprintf(stderr, "open %s: %s\n", keydev, strerror(errno)); return 1; }
    /* 优先读写打开：需要 EVIOCGBIT 读按键能力，判断哪些键能直接写进设备 */
    int mfd = open(mousedev, O_RDWR);
    if (mfd < 0) mfd = open(mousedev, O_WRONLY);
    if (mfd < 0) {
        const char *found = find_mouse_dev(mbuf, sizeof mbuf);
        if (found) {
            mousedev = found;
            mfd = open(mousedev, O_RDWR);
            if (mfd < 0) mfd = open(mousedev, O_WRONLY);
        }
    }
    if (mfd < 0) {
        printf("ERR=no_mouse\n");
        fflush(stdout);
        fprintf(stderr, "no mouse device found\n");
        close(kfd);
        return 3;
    }
    g_mfd = mfd;
    if (ioctl(mfd, EVIOCGBIT(EV_KEY, sizeof g_mkeys), g_mkeys) >= 0) g_mkeys_ok = 1;

    load_map(mapfile);

    if (g_pos_x < 0) g_pos_x = g_screen_w / 2;
    if (g_pos_y < 0) g_pos_y = g_screen_h / 2;

    if (grab) {
        if (ioctl(kfd, EVIOCGRAB, 1) < 0) {
            /* 独占失败必须退出：降级运行会出现"光标和焦点同时动"的双触发 */
            printf("ERR=grab_failed\n");
            fflush(stdout);
            fprintf(stderr, "EVIOCGRAB failed: %s\n", strerror(errno));
            close(kfd);
            close(mfd);
            return 2;
        }
        g_grabbed = 1;
    }

    signal(SIGTERM, on_sig);
    signal(SIGINT, on_sig);

    printf("tvmouse 启动 key=%s mouse=%s speed=%.0f start=%.0f grab=%d back=%d map=%d toggle=%d hide=%llds pt=%d\n",
           keydev, mousedev, speed, g_v0, grab, g_back_mode, g_mapn, g_toggle, g_hide_ms / 1000, g_passthrough);
    fflush(stdout);

    int mask = 0;
    long long press_at = 0, last_move = now_ms(), last_tick = now_ms(), last_user_move = now_ms();
    double acc_x = 0, acc_y = 0;
    struct pollfd pfd = { kfd, POLLIN, 0 };
    struct input_event ev;

    while (g_running) {
        int r = poll(&pfd, 1, 16);
        long long t = now_ms();

        if (r > 0 && (pfd.revents & POLLIN)) {
            ssize_t n = read(kfd, &ev, sizeof ev);
            if (n == (ssize_t)sizeof ev && ev.type == EV_KEY) {
                /* 切换键：接管 ⇄ 释放 */
                if (g_toggle && ev.code == g_toggle) {
                    if (ev.value == 1) {
                        if (g_active) {
                            /* 接管 → 释放：回放模式下独占不放，否则放开恢复原生 */
                            if (g_grabbed && !g_passthrough) ioctl(kfd, EVIOCGRAB, 0);
                            g_active = 0;
                            mask = 0;
                            printf("STATE=PAUSED\n");
                            fflush(stdout);
                        } else {
                            /* 释放 → 接管：先记下，等这个键抬起再抓，
                               避免电视只收到按下收不到抬起，被当成一直按着（触发长按效果） */
                            g_pending_grab = 1;
                        }
                    } else if (ev.value == 0 && g_pending_grab) {
                        g_pending_grab = 0;
                        if (g_grabbed) ioctl(kfd, EVIOCGRAB, 1);
                        g_active = 1;
                        printf("STATE=ACTIVE\n");
                        fflush(stdout);
                    }
                    continue;
                }
                if (!g_active) {
                    if (g_passthrough) {
                        /* 保持独占，把按键原样写回红外设备：电视照常收到，
                           但切换键永远不会泄漏，也就没有 U 模式弹窗 */
                        if (t < g_skip_until) continue;
                        if (g_grabbed) ioctl(kfd, EVIOCGRAB, 0);
                        if (write(kfd, &ev, sizeof ev) != (ssize_t)sizeof ev) { /* 忽略 */ }
                        usleep(12000);
                        if (g_grabbed) ioctl(kfd, EVIOCGRAB, 1);
                        g_skip_until = now_ms() + 80;
                    }
                    continue;   /* 非回放模式：暂停时完全不干预，遥控恢复原生 */
                }

                int bit = 0;
                switch (ev.code) {
                    case KEY_UP:    bit = 1; break;
                    case KEY_DOWN:  bit = 2; break;
                    case KEY_LEFT:  bit = 4; break;
                    case KEY_RIGHT: bit = 8; break;
                    default: break;
                }
                if (bit) {
                    if (ev.value == 1) { if (!mask) press_at = t; mask |= bit; }
                    else if (ev.value == 0) mask &= ~bit;
                } else if (ev.value == 1) {
                    int act = lookup(ev.code);
                    if (act < 0) act = default_action(ev.code);
                    do_action(act, ev.code);
                }
            }
        }

        double dt = (t - last_tick) / 1000.0;
        last_tick = t;
        if (dt > 0.1) dt = 0.1;

        if (mask) {
            double held = (t - press_at) / 1000.0;
            double v0 = g_v0;
            double ramp = held / 1.0;
            if (ramp > 1.0) ramp = 1.0;
            double v = v0 + (speed - v0) * ramp;

            double dx = 0, dy = 0;
            if (mask & 4) dx -= 1;
            if (mask & 8) dx += 1;
            if (mask & 1) dy -= 1;
            if (mask & 2) dy += 1;
            if (dx && dy) { dx *= 0.7071; dy *= 0.7071; }

            acc_x += dx * v * dt;
            acc_y += dy * v * dt;
            int mx = (int)acc_x, my = (int)acc_y;
            if (mx || my) {
                acc_x -= mx;
                acc_y -= my;
                move_by(mfd, mx, my);
                g_pos_x += mx;
                g_pos_y += my;
                if (g_pos_x < 0) g_pos_x = 0;
                if (g_pos_x > g_screen_w) g_pos_x = g_screen_w;
                if (g_pos_y < 0) g_pos_y = 0;
                if (g_pos_y > g_screen_h) g_pos_y = g_screen_h;
                last_move = t;
                last_user_move = t;
            }
        } else if (keepalive
                   && (g_hide_ms == 0 || t - last_user_move < g_hide_ms)
                   && t - last_move > 2000) {
            move_by(mfd, 1, 0);
            move_by(mfd, -1, 0);
            last_move = t;
        }
    }

    if (g_grabbed) ioctl(kfd, EVIOCGRAB, 0);
    close(kfd);
    close(mfd);
    return 0;
}
