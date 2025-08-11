import re, os, sys

def normalize(u: str) -> str:
    gid = re.search(r"/groups/(\d+)", u)
    pid = re.search(r"multi_permalinks=(\d+)", u)
    if gid and pid:
        return f"https://www.facebook.com/groups/{gid.group(1)}/permalink/{pid.group(1)}/"
    return u

def run(url: str, out_dir: str) -> str:
    url = normalize(url)
    os.makedirs(out_dir, exist_ok=True)
    archive = os.path.join(out_dir, "archive.txt")
    argv = [
        "gallery-dl", "-o", "part=false",
        "--download-archive", archive,
        "--sleep", "1-2",
        "-d", out_dir,
        url,
    ]
    from gallery_dl import main as gmain
    sys.argv = argv
    code = gmain.main()
    if code != 0:
        return f"gallery-dl exited with code {code}"
    return f"Downloaded to temp: {out_dir}"
