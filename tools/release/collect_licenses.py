#!/usr/bin/env python3
"""Inventory exact release artifacts and preserve their published licensing metadata."""
from pathlib import Path
import subprocess
import concurrent.futures, hashlib, io, json, re, urllib.request, xml.etree.ElementTree as ET, zipfile
ROOT = Path(__file__).resolve().parents[2]
CACHE = Path.home()/".gradle/caches/modules-2/files-2.1"
OUT = ROOT/"third_party/runtime"
NS = {"m":"http://maven.apache.org/POM/4.0.0"}

def fetch(url):
    return subprocess.check_output(["curl", "--fail", "--silent", "--show-error", "--location", "--retry", "3", "--retry-all-errors", "--max-time", "45", url])

def pom(coord):
    g,a,v=coord.split(":")
    name=coord.replace(":","_")+".pom"
    dest=OUT/"poms"/name
    if not dest.exists():
        candidates=list((CACHE/g/a/v).glob("*/*.pom"))
        if candidates: data=candidates[0].read_bytes()
        else:
            base="https://dl.google.com/dl/android/maven2/" if g.startswith("androidx.") else "https://repo.maven.apache.org/maven2/"
            url=base+g.replace(".","/")+f"/{a}/{v}/{a}-{v}.pom"
            data=fetch(url)
        dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(data)
    return ET.fromstring(dest.read_bytes()),name

def licenses(coord,depth=0):
    if depth>8: raise RuntimeError("POM parent cycle")
    root,name=pom(coord)
    result=[(li.findtext("m:name",namespaces=NS),li.findtext("m:url",namespaces=NS)) for li in root.findall("m:licenses/m:license",NS)]
    if not result:
        parent=root.find("m:parent",NS)
        if parent is not None:
            result=licenses(":".join(parent.findtext("m:"+x,namespaces=NS) for x in ["groupId","artifactId","version"]),depth+1)
    if not result: raise RuntimeError("No license metadata: "+coord)
    return result

def notices(coord,path):
    target=OUT/"notices"/coord.replace(":","_")
    def scan(data,prefix=""):
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            for name in z.namelist():
                base=Path(name).name
                if re.search(r"(?i)(license|notice|copyright|copying)",base) and not name.endswith("/") and not name.endswith(".class"):
                    raw=z.read(name)
                    try: raw.decode("utf-8")
                    except UnicodeError: continue
                    target.mkdir(parents=True,exist_ok=True)
                    (target/(hashlib.sha256((prefix+name).encode()).hexdigest()[:10]+"-"+base)).write_bytes(raw)
                elif name=="classes.jar": scan(z.read(name),"classes.jar/")
    if zipfile.is_zipfile(path): scan(Path(path).read_bytes())

def main():
    OUT.mkdir(parents=True,exist_ok=True)
    pairs=[line.split("\t") for line in (ROOT/"release-artifacts/runtime-dependencies.tsv").read_text().splitlines()]
    coordinates=sorted({c for c,_ in pairs})
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        found=dict(zip(coordinates,pool.map(licenses,coordinates)))
    for c,p in pairs: notices(c,p)
    apache=OUT/"Apache-2.0.txt"
    if not apache.exists(): apache.write_bytes(fetch("https://www.apache.org/licenses/LICENSE-2.0.txt"))
    protobuf=OUT/"Protobuf-BSD-3-Clause.txt"
    if not protobuf.exists(): protobuf.write_bytes(fetch("https://raw.githubusercontent.com/protocolbuffers/protobuf/v3.25.5/LICENSE"))
    (OUT/"dependencies.json").write_text(json.dumps(found,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    rows=["# Release runtime dependency licenses", "", "Generated from the resolved releaseRuntimeClasspath. Includes transitive artifacts, even when shrinking removes their unused code. POM metadata is preserved in poms/; original embedded licenses, notices and copyright files are preserved in notices/. Full license texts: Apache-2.0.txt and Protobuf-BSD-3-Clause.txt (Google Protobuf BSD text, retrieved from protocolbuffers/protobuf v3.25.5; DataStore publishes the repackaged dependency under BSD-3-Clause). Third-party terms remain independent of the project's custom license.", "", "| Artifact | Declared license |", "| --- | --- |"]
    rows += ["| "+c+" | "+"; ".join(f"[{n}]({u})" for n,u in found[c])+" |" for c in coordinates]
    (OUT/"README.md").write_text("\n".join(rows)+"\n",encoding="utf-8")
    print("License inventory complete:",len(coordinates),"coordinates")
    for c,ls in found.items():
        if any("apache" not in n.lower() for n,_ in ls):print(c,ls)
if __name__=="__main__":main()
