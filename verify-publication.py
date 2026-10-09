#!/usr/bin/env python3
"""核对本地 CAPI Maven 发布；可与只读下载的远端仓库目录比较。"""
import argparse
import hashlib
import json
import re
import subprocess
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

NS = {"m": "http://maven.apache.org/POM/4.0.0"}

def props(text):
    text = re.sub(r"\\\r?\n[ \t]*", " ", text)
    return {k.strip(): v.strip().replace(r"\=", "=") for line in text.splitlines()
            if "=" in line and not line.lstrip().startswith("#")
            for k, v in [line.split("=", 1)]}

def dependencies(pom):
    return {node.text for node in ET.parse(pom).findall("m:dependencies/m:dependency/m:artifactId", NS)}

def inspect_klib(path):
    with zipfile.ZipFile(path) as z:
        files = {n: z.read(n) for n in z.namelist() if not n.endswith("/")}
    manifest = props(files["default/manifest"].decode())
    metadata = {n: hashlib.sha256(b).hexdigest() for n, b in files.items() if "/linkdata/" in n}
    return manifest, files, metadata

def check_module(path):
    data = json.loads(path.with_suffix(".module").read_text())
    declarations = [f for v in data["variants"] for f in v.get("files", []) if f["name"] == path.name]
    assert declarations, f"module 未列出 KLIB: {path}"
    assert all(f["sha256"] == hashlib.sha256(path.read_bytes()).hexdigest() for f in declarations), path

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parent)
    parser.add_argument("--reference", type=Path, help="org/cpf/kotlin 层级的远端只读下载目录")
    parser.add_argument("--llvm-bin", type=Path, help="包含 llvm-dis 的目录，启用全部 bitcode 检查")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    version = props((args.repo / "producer/gradle.properties").read_text())["klibVersion"]
    local = args.repo / "m2/org/cpf/kotlin"
    results = {"version": version, "leaves": [], "errors": []}
    publication_files = {"klib": 0, "pom": 0, "module": 0}
    for module in ["ohos-capi", "hms-capi"]:
        defs = sorted((args.repo / f"producer/{module}/nativeInterop/ohosArm64").glob("*.def"))
        bom = local / module / version
        assert not list(bom.glob("*.klib")), f"BOM 不应含 KLIB: {bom}"
        assert dependencies(bom / f"{module}-{version}.pom") == {p.stem for p in defs}
        if args.reference:
            assert dependencies(bom / f"{module}-{version}.pom") == dependencies(args.reference / module / version / f"{module}-{version}.pom")
        for definition in defs:
            name = definition.stem
            path = local / name / version / f"{name}-{version}.klib"
            source = props(definition.read_text())
            manifest, entries, metadata = inspect_klib(path)
            check_module(path)
            assert manifest["unique_name"] == f"org.jetbrains.kotlin.native.platform.{name}", name
            assert "--as-needed" not in manifest.get("linkerOpts", ""), name
            assert "InteropApiProtection" not in manifest.get("depends", ""), name
            assert dependencies(path.with_suffix(".pom")) == set(source.get("depends", "").split()), name
            cpp = source.get("language") == "C++"
            assert (manifest.get("enableUndefinedApiProtection") == "true") == cpp, name
            row = {"name": name, "sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "cpp": cpp}
            if args.reference:
                ref = args.reference / name / version / path.name
                check_module(ref)
                rm, re_entries, re_metadata = inspect_klib(ref)
                # includedHeaders 含临时生成源文件的哈希，不属于 API 或链接语义。
                differences = {k: [manifest.get(k), rm.get(k)] for k in manifest.keys() | rm.keys()
                               if k != "includedHeaders" and (set(manifest.get(k, "").split()) != set(rm.get(k, "").split()) if k == "depends" else manifest.get(k) != rm.get(k))}
                row.update(binary_equal=path.read_bytes() == ref.read_bytes(),
                           unpacked_equal=entries == re_entries, metadata_equal=metadata == re_metadata,
                           manifest_differences=differences,
                           differing_entries=sorted(k for k in entries.keys() | re_entries.keys() if entries.get(k) != re_entries.get(k)))
                assert dependencies(path.with_suffix(".pom")) == dependencies(ref.with_suffix(".pom")), name
                if differences: results["errors"].append({"name": name, "manifest": differences})
            if args.llvm_bin:
                bc = next(b for n, b in entries.items() if n.endswith("/cstubs.bc"))
                ir = subprocess.run([str(args.llvm_bin / "llvm-dis"), "-o", "-", "-"], input=bc, capture_output=True, check=True).stdout.decode()
                match = re.search(r"define weak[^\n]*@ThrowIllegalStateExceptionFromCString\([^\n]*\).*?\n}", ir, re.S)
                assert bool(match) == cpp, name
                if cpp:
                    body = match.group(0)
                    assert "CalledFromNativeGuard" in body and "ThrowIllegalStateExceptionWithMessage" in body and "@abort(" not in body, name
                row["bitcode_checked"] = True
            results["leaves"].append(row)
    if args.reference:
        # 正式发布的全部文件都比较完整字节；输入哈希完全一致才算复现成功。
        for reference in sorted(args.reference.glob(f"*/{version}/*")):
            extension = reference.suffix.lstrip(".")
            if extension not in publication_files:
                continue
            candidate = local / reference.relative_to(args.reference)
            if candidate.read_bytes() == reference.read_bytes():
                publication_files[extension] += 1
            else:
                results["errors"].append({"publication_file": str(reference.relative_to(args.reference)), "binary_equal": False})
        results["publication_files_equal"] = publication_files
    results["counts"] = {"leaves": len(results["leaves"]), "protected": sum(r["cpp"] for r in results["leaves"])}
    for key in ["binary_equal", "unpacked_equal", "metadata_equal", "bitcode_checked"]:
        if any(key in r for r in results["leaves"]): results["counts"][key] = sum(r.get(key, False) for r in results["leaves"])
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(results, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({"counts": results["counts"], "publication_files_equal": publication_files, "errors": results["errors"]}, ensure_ascii=False, indent=2))
    assert not results["errors"], "本地与参考发布内容不一致"

if __name__ == "__main__":
    main()
