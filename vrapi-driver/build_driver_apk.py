#!/usr/bin/env python3
"""
Собирает PhoneXR VrApi Driver — пакет com.oculus.systemdriver, который загрузчик libvrapi.so внутри
игр Gear VR / Quest открывает сам. В нём DriverLoader и собственный VrApi PhoneXR
(gearvr-shim, цель phonexr_vrapi) с загрузчиком Compatibility-Layer: игры VrApi идут без патча.

  python3 vrapi-driver/build_driver_apk.py
Сначала соберите gearvr-shim (build и build32, цель phonexr_vrapi).
"""

import glob
import os
import shutil
import subprocess
import tempfile
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HERE = os.path.join(ROOT, "vrapi-driver")
SDK = os.environ.get("ANDROID_HOME") or os.path.expanduser("~/Library/Android/sdk")
OUTPUT = os.path.join(ROOT, "vrapi-driver", "phonexr-vrapi-driver.apk")


def build_tool(name):
    folder = os.path.join(SDK, "build-tools")
    for version in sorted(os.listdir(folder), reverse=True):
        path = os.path.join(folder, version, name)
        if os.path.exists(path):
            return path
    raise SystemExit(f"Не найден {name}")


def main():
    android_jar = sorted(glob.glob(os.path.join(SDK, "platforms", "android-*", "android.jar")))[-1]
    shim = os.path.join(ROOT, "gearvr-shim", "build", "assets")
    assets = os.path.join(ROOT, "compatibility-layer-runtime", "loaders")
    libraries = {
        "lib/arm64-v8a/libphonexr_vrapi.so": os.path.join(shim, "libphonexr_vrapi.so"),
        "lib/arm64-v8a/libcompatibility_layer_loader.so": os.path.join(assets, "libcompatibility_layer_loader.so"),
        "lib/armeabi-v7a/libphonexr_vrapi.so": os.path.join(shim, "libphonexr_vrapi32.so"),
        "lib/armeabi-v7a/libcompatibility_layer_loader.so": os.path.join(assets, "libcompatibility_layer_loader32.so"),
    }
    for path in libraries.values():
        if not os.path.exists(path):
            raise SystemExit(f"Нет {path}")
    with tempfile.TemporaryDirectory() as work:
        base = os.path.join(work, "base.apk")
        subprocess.run([build_tool("aapt2"), "link", "-o", base, "--manifest", os.path.join(HERE, "AndroidManifest.xml"),
                        "-I", android_jar], check=True)
        classes = os.path.join(work, "classes")
        subprocess.run(["javac", "-source", "11", "-target", "11", "-cp", android_jar, "-d", classes,
                        *glob.glob(os.path.join(HERE, "java", "**", "*.java"), recursive=True)], check=True)
        dex = os.path.join(work, "dex")
        os.makedirs(dex)
        subprocess.run([build_tool("d8"), "--lib", android_jar, "--min-api", "26", "--output", dex,
                        *glob.glob(os.path.join(classes, "**", "*.class"), recursive=True)], check=True)
        unsigned = os.path.join(work, "unsigned.apk")
        shutil.copy(base, unsigned)
        with zipfile.ZipFile(unsigned, "a") as apk:
            apk.write(os.path.join(dex, "classes.dex"), "classes.dex")
            for name, path in libraries.items():
                apk.write(path, name, compress_type=zipfile.ZIP_DEFLATED)
        aligned = os.path.join(work, "aligned.apk")
        subprocess.run([build_tool("zipalign"), "-P", "16", "-f", "4", unsigned, aligned], check=True)
        keystore = os.path.join(assets, "phonexr-signing.p12")
        subprocess.run([build_tool("apksigner"), "sign", "--ks", keystore, "--ks-pass", "pass:android",
                        "--ks-key-alias", "androiddebugkey", "--key-pass", "pass:android",
                        "--out", OUTPUT, aligned], check=True)
    print("Готово:", OUTPUT, os.path.getsize(OUTPUT) // 1024, "КБ")


if __name__ == "__main__":
    main()
