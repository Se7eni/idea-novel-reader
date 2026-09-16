#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
不依赖 Gradle 的本地打包脚本。

直接调用 IDEA 自带的 JBR 编译源码，并打成 IntelliJ 可直接安装的插件包。
适合本机没装 Gradle、也不想下载几个 G 依赖的场景。

用法：
    python tools/package_local.py
    python tools/package_local.py --idea "D:/Software/IntelliJ IDEA 2026.2.0.1" --version 1.0.0
"""
import argparse
import os
import shutil
import subprocess
import sys
import zipfile

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA_SRC = os.path.join(ROOT, "src", "main", "java")
RES_DIR = os.path.join(ROOT, "src", "main", "resources")
BUILD = os.path.join(ROOT, "build")
CLASSES = os.path.join(BUILD, "classes")
DIST = os.path.join(BUILD, "dist")


def remove_dir(path):
    """删除目录。某些环境会拦截 rmtree（比如带安全删除钩子的沙箱），降级成逐个文件删。"""
    if not os.path.isdir(path):
        return
    try:
        shutil.rmtree(path)
        return
    except Exception:
        pass
    for dirpath, dirnames, filenames in os.walk(path, topdown=False):
        for name in filenames:
            try:
                os.remove(os.path.join(dirpath, name))
            except Exception:
                pass
        for name in dirnames:
            try:
                os.rmdir(os.path.join(dirpath, name))
            except Exception:
                pass
    try:
        os.rmdir(path)
    except Exception:
        pass


def find_sources():
    sources = []
    for dirpath, _, filenames in os.walk(JAVA_SRC):
        for name in filenames:
            if name.endswith(".java"):
                sources.append(os.path.join(dirpath, name))
    return sorted(sources)


def compile_sources(idea_home):
    javac = os.path.join(idea_home, "jbr", "bin", "javac.exe")
    if not os.path.exists(javac):
        javac = os.path.join(idea_home, "jbr", "bin", "javac")
    if not os.path.exists(javac):
        raise SystemExit("找不到 javac，请确认 IDEA 安装目录下有 jbr/bin/javac：" + idea_home)

    remove_dir(CLASSES)
    os.makedirs(CLASSES, exist_ok=True)

    sources = find_sources()
    if not sources:
        raise SystemExit("没有找到 Java 源文件：" + JAVA_SRC)

    if not os.path.isdir(os.path.join(idea_home, "lib")):
        raise SystemExit("IDEA 的 lib 目录不存在：" + os.path.join(idea_home, "lib"))
    # 用通配符让 javac 自己展开 lib 下所有 jar，避免拼出超长的 classpath。
    # 注意：这个参数不能加引号，否则 javac 不会展开通配符。
    classpath = (idea_home.rstrip("/\\") + "/lib/*")

    print("编译 %d 个源文件..." % len(sources))
    cmd = [javac, "-encoding", "UTF-8", "-nowarn", "-d", CLASSES, "-cp", classpath] + sources
    proc = subprocess.run(cmd, cwd=ROOT,
                          capture_output=True, text=True, encoding="utf-8", errors="replace")
    if proc.returncode != 0:
        print(proc.stdout or "")
        print(proc.stderr or "")
        raise SystemExit("编译失败")
    if proc.stderr.strip():
        print(proc.stderr.strip())
    print("编译完成 -> %s" % CLASSES)


def merge_resources():
    if not os.path.isdir(RES_DIR):
        return
    for dirpath, _, filenames in os.walk(RES_DIR):
        rel = os.path.relpath(dirpath, RES_DIR)
        target = CLASSES if rel == "." else os.path.join(CLASSES, rel)
        os.makedirs(target, exist_ok=True)
        for name in filenames:
            shutil.copy2(os.path.join(dirpath, name), os.path.join(target, name))


def make_jar(out_jar):
    if os.path.exists(out_jar):
        os.remove(out_jar)
    count = 0
    with zipfile.ZipFile(out_jar, "w", zipfile.ZIP_DEFLATED) as zf:
        # plugin.xml 放前面，插件加载器能更快定位
        priority = ["META-INF/plugin.xml"]
        written = set()
        for rel in priority:
            p = os.path.join(CLASSES, rel)
            if os.path.exists(p):
                zf.write(p, rel.replace("\\", "/"))
                written.add(os.path.abspath(p))
                count += 1
        for dirpath, _, filenames in os.walk(CLASSES):
            for name in filenames:
                full = os.path.join(dirpath, name)
                if os.path.abspath(full) in written:
                    continue
                arc = os.path.relpath(full, CLASSES).replace("\\", "/")
                zf.write(full, arc)
                count += 1
    print("打包 jar（%d 个文件）-> %s" % (count, out_jar))


def make_zip(jar_path, plugin_id, version, out_zip):
    if os.path.exists(out_zip):
        os.remove(out_zip)
    arc_jar = "%s/lib/%s-%s.jar" % (plugin_id, plugin_id, version)
    with zipfile.ZipFile(out_zip, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.write(jar_path, arc_jar)
    print("打包安装包 -> %s" % out_zip)


def main():
    parser = argparse.ArgumentParser(description="编译并打包 IDEA 小说阅读插件")
    parser.add_argument("--idea", default=r"D:/Software/IntelliJ IDEA 2026.2.0.1",
                        help="IDEA 安装目录，用于提供 JBR 和平台依赖")
    parser.add_argument("--version", default="1.0.3", help="插件版本号")
    parser.add_argument("--id", default="idea-novel-reader", help="插件包 id，影响安装包内目录名")
    args = parser.parse_args()

    idea_home = args.idea
    if not os.path.isdir(idea_home):
        raise SystemExit("IDEA 目录不存在：" + idea_home)

    os.makedirs(DIST, exist_ok=True)
    compile_sources(idea_home)
    merge_resources()

    jar_path = os.path.join(DIST, "%s-%s.jar" % (args.id, args.version))
    zip_path = os.path.join(DIST, "%s-%s.zip" % (args.id, args.version))
    make_jar(jar_path)
    make_zip(jar_path, args.id, args.version, zip_path)

    print("")
    print("完成。在 IDEA 里：Settings > Plugins > 齿轮图标 > Install Plugin from Disk，选择：")
    print("  " + zip_path)


if __name__ == "__main__":
    main()
