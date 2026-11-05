"""Two Docker Hub channels; latest is promoted separately from successful version builds."""
import json
import os
import re
import subprocess
import sys
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
    else:
        raise SystemExit("Usage : docker_publish.py plan|promote (promotion réservée aux tags stables)")
