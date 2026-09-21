# -*- coding: utf-8 -*-
"""
跑 UI 探针，把书架/工具栏渲染成 PNG 供肉眼比对。

为什么用 Python 拉起而不是一行 shell：
Git Bash 会把 classpath 里的 /d/Software/... 做路径转换，
Java 拿到后找不到 IDEA 的 jar；Python 直传字符串没有这个中间层。
"""
import os
import subprocess
import sys

# 本文件在 tools/probe 下，退三层才是项目根
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# 注意：IDEA 路径里若有正斜杠，Java 的 classpath 通配符 * 就不展开，
# 结果是「找不到平台类」这种离奇报错。统一换成反斜杠。
IDEA = os.path.normpath(sys.argv[1] if len(sys.argv) > 1
                        else r"D:\Software\IntelliJ IDEA 2026.2.0.1")

classes = os.path.join(ROOT, "build", "classes")
probe_out = os.path.join(ROOT, "tools", "probe", "out")
cp = os.pathsep.join([probe_out, classes, os.path.join(IDEA, "lib", "*")])
print("IDEA =", IDEA)
print("CP   =", cp)

java = os.path.join(IDEA, "jbr", "bin", "java.exe")
vm_args = [
    "--add-opens", "java.desktop/javax.swing=ALL-UNNAMED",
    "--add-opens", "java.desktop/javax.swing.plaf.basic=ALL-UNNAMED",
    "--add-opens", "java.desktop/javax.swing.plaf=ALL-UNNAMED",
    "--add-opens", "java.desktop/java.awt=ALL-UNNAMED",
    "--add-opens", "java.desktop/sun.awt=ALL-UNNAMED",
]

cmd = [java] + vm_args + ["-cp", cp, "UiProbe"]
env = dict(os.environ)
env.pop("JAVA_TOOL_OPTIONS", None)
p = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True,
                   encoding="utf-8", errors="replace")
print("exit =", p.returncode)
if p.stdout:
    print("--- stdout ---")
    print(p.stdout)
if p.stderr:
    lines = p.stderr.splitlines()
    print("--- stderr (%d 行) ---" % len(lines))
    for ln in lines[:40]:
        print(ln)
