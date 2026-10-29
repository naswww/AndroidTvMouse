#!/usr/bin/env python3
"""从 RSA 私钥（PKCS#8 PEM）生成 adb 格式的公钥串。

adb 的公钥不是 SSH 格式，而是自定义结构体：
    struct RSAPublicKey {
        uint32 len;              // 模长（以 32bit 字计），2048 位 = 64
        uint32 n0inv;            // -n^-1 mod 2^32
        uint32 modulus[len];     // 模数，小端
        uint32 rr[len];          // R^2 mod n，R = 2^(len*32)，小端
        uint32 exponent;         // 公钥指数，通常 65537
    }
整体 base64 后再拼上 " 用户名"，就是 adbkey.pub 的内容。

用法：adbkey_pub.py <私钥路径> <用户名>
"""
import base64
import struct
import subprocess
import sys


def main() -> int:
    if len(sys.argv) < 3:
        sys.stderr.write("用法: adbkey_pub.py <私钥路径> <用户名>\n")
        return 2
    key_path, name = sys.argv[1], sys.argv[2]

    out = subprocess.check_output(
        ["openssl", "rsa", "-in", key_path, "-noout", "-modulus"], text=True
    ).strip()
    modulus_hex = out.split("=", 1)[1]
    n = int(modulus_hex, 16)
    e = 65537

    words = (n.bit_length() + 31) // 32          # 2048 位 -> 64
    nbytes = words * 4

    mod = n.to_bytes(nbytes, "little")
    n0inv = (-pow(n, -1, 1 << 32)) % (1 << 32)
    rr = pow(2, nbytes * 8, n).to_bytes(nbytes, "little")

    blob = struct.pack("<II%ds%dsI" % (nbytes, nbytes), words, n0inv, mod, rr, e)
    sys.stdout.write(base64.b64encode(blob).decode() + " " + name)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
