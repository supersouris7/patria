"""Two Docker Hub channels; latest is promoted separately from successful version builds.

Docker Hub keeps only latest, unstable and the newest X.Y.Z: prune deletes commit-hash tags and older versions,
and never touches any other tag name.
"""
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request

IMAGE = "supersouris7/patria"
STABLE = re.compile(r"(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)", re.ASCII)


def version(tag):
    match = STABLE.fullmatch(tag)
    return tuple(map(int, match.groups())) if match else None


def channel(ref):
    if ref == "refs/heads/main":
        return "unstable", False
    prefix = "refs/tags/v"
    if ref.startswith(prefix) and version(ref[len(prefix):]) is not None:
        return ref[len(prefix):], True
    raise ValueError("Publication autorisée seulement depuis main ou un tag stable vX.Y.Z (sans préversion).")


def highest_stable(tags, expected):
    stable = [tag for tag in tags if version(tag["name"]) is not None and tag.get("tag_status") == "active"]
    if not any(tag["name"] == expected for tag in stable):
        raise ValueError("La version venant d'être publiée n'est pas visible sur Docker Hub ; relancer la promotion.")
    selected = max(stable, key=lambda tag: version(tag["name"]))
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", selected.get("digest", "")):
        raise ValueError("Digest de la version stable manquant ou invalide.")
    return selected


def hub_tags():
    url = f"https://hub.docker.com/v2/repositories/{IMAGE}/tags/?page_size=100"
    tags = []
    while url:
        if not url.startswith(f"https://hub.docker.com/v2/repositories/{IMAGE}/tags/"):
            raise ValueError("Pagination Docker Hub inattendue.")
        request = urllib.request.Request(url, headers={"Cache-Control": "no-cache"})
        with urllib.request.urlopen(request, timeout=30) as response:
            data = json.load(response)
        tags.extend(data["results"])
        url = data.get("next")
    return tags


def promote(expected):
    tags = hub_tags()
    selected = highest_stable(tags, expected)
    current = next((tag for tag in tags if tag["name"] == "latest"), None)
    if current:
        # Lire la version reellement publiee, pas l'ordre des workflows ni leur date de fin.
        config = json.loads(subprocess.check_output([
            "docker", "buildx", "imagetools", "inspect", f"{IMAGE}@{current['digest']}",
            "--format", '{{json (index .Image "linux/amd64")}}'], text=True))
        current_version = config.get("config", {}).get("Labels", {}).get("org.opencontainers.image.version", "")
        if version(current_version) is None:
            raise ValueError("latest existe sans version stable reconnue : refus de l'écraser automatiquement.")
        if version(current_version) > version(selected["name"]):
            print("latest est déjà plus récent ; aucune régression autorisée.")
            return
        if current["digest"] == selected["digest"]:
            print("latest pointe déjà sur la version stable la plus récente.")
            return
    subprocess.run(["docker", "buildx", "imagetools", "create", "--tag", f"{IMAGE}:latest",
                    f"{IMAGE}@{selected['digest']}"], check=True)
    print(f"latest -> {selected['name']} ({selected['digest']})")


KEPT_CHANNELS = {"latest", "unstable"}
COMMIT = re.compile(r"[0-9a-f]{7,40}", re.ASCII)


def to_delete(tags):
    """Tags a effacer : hashs de commit et versions plus anciennes que la plus recente. Les autres noms restent."""
    stable = [tag for tag in tags if version(tag["name"]) is not None]
    newest = max(stable, key=lambda tag: version(tag["name"]))["name"] if stable else None
    doomed = [tag["name"] for tag in tags
              if tag["name"] not in KEPT_CHANNELS and tag["name"] != newest
              and (COMMIT.fullmatch(tag["name"]) or version(tag["name"]) is not None)]
    return sorted(doomed)


def hub_token(username, token):
    request = urllib.request.Request("https://hub.docker.com/v2/users/login", method="POST",
                                     data=json.dumps({"username": username, "password": token}).encode(),
                                     headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)["token"]


def prune(dry_run):
    tags = hub_tags()
    doomed = to_delete(tags)
    kept = sorted(tag["name"] for tag in tags if tag["name"] not in doomed)
    print(f"Gardes : {', '.join(kept) or 'aucun'}")
    print(f"A effacer : {len(doomed)} tag(s)")
    if dry_run or not doomed:
        for name in doomed:
            print(f"  (essai) {name}")
        return
    jwt = hub_token(os.environ["DOCKERHUB_USERNAME"], os.environ["DOCKERHUB_TOKEN"])
    for name in doomed:
        request = urllib.request.Request(f"https://hub.docker.com/v2/repositories/{IMAGE}/tags/{name}/", method="DELETE",
                                         headers={"Authorization": f"Bearer {jwt}"})
        try:
            with urllib.request.urlopen(request, timeout=30):
                print(f"  efface {name}")
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise SystemExit(f"Effacement de {name} refuse ({error.code}) : le jeton DOCKERHUB_TOKEN doit avoir le droit Delete.")
            print(f"  deja absent {name}")


if __name__ == "__main__":
    if sys.argv[1:] == ["plan"]:
        requested = os.environ.get("PATRIA_RELEASE_TAG", "").strip()
        ref = "refs/tags/" + requested if requested else os.environ["GITHUB_REF"]
        tag, stable = channel(ref)
        commit = subprocess.check_output(["git", "rev-parse", "--verify", ref + "^{commit}"], text=True).strip()
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
            output.write(f"tag={tag}\nstable={str(stable).lower()}\ncommit={commit}\n")
    elif len(sys.argv) == 3 and sys.argv[1] == "promote" and version(sys.argv[2]) is not None:
        promote(sys.argv[2])
    elif sys.argv[1:2] == ["prune"] and sys.argv[2:] in ([], ["--dry-run"]):
        prune(sys.argv[2:] == ["--dry-run"])
    else:
        raise SystemExit("Usage : docker_publish.py plan|promote X.Y.Z|prune [--dry-run]")
