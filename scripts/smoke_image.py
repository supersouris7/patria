#!/usr/bin/env python3
"""Controle d'une image Patria avant publication, sur des donnees de test jetables.

    python3 scripts/smoke_image.py IMAGE [--platform linux/arm64]

Verifie : demarrage en conteneur durci (lecture seule, /tmp en memoire, aucun privilege), reponse HTTP sur le port
8080 et en-tetes de securite, base des comptes chiffree (en-tete illisible sans la cle), redemarrage avec la meme cle,
refus d'une mauvaise cle, absence de la cle dans les journaux, utilisateur non root. Le volume de test est supprime a
la fin ; aucune donnee reelle n'est touchee.
"""
import argparse
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.request


def docker(*args, check=True, capture=True):
    return subprocess.run(["docker", *args], check=check, capture_output=capture, text=True)


def wait_ready(port, container, timeout):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{port}/sante", timeout=5) as response:
                if response.read() == b"ok":
                    return True
        except (urllib.error.URLError, ConnectionError, OSError):
            pass
        if docker("inspect", "-f", "{{.State.Running}}", container, check=False).stdout.strip() != "true":
            return False
        time.sleep(2)
    return False


def run(image, platform, volume, key, port, name, legacy=False):
    docker("rm", "-f", name, check=False)
    hardening = [] if legacy else ["--read-only", "--tmpfs", "/tmp"]
    docker("run", "-d", "--name", name, "--platform", platform, *hardening, "--cap-drop", "ALL",
           "--security-opt", "no-new-privileges:true", "-v", f"{volume}:/data", "-p", f"127.0.0.1:{port}:8080",
           "-e", "APP_PASSWORD=controle-image", "-e", f"DB_ENCRYPTION_KEY={key}", "-e", "OFFLINE=true", image)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("image")
    parser.add_argument("--platform", default="linux/amd64")
    parser.add_argument("--port", type=int, default=18180)
    parser.add_argument("--timeout", type=int, default=240)
    # Version ancienne republiee (tag existant) : sans durcissement de lecture seule ni en-tetes recents.
    parser.add_argument("--legacy", action="store_true")
    args = parser.parse_args()
    suffix = secrets.token_hex(4)
    volume, name = f"patria-controle-{suffix}", f"patria-controle-{suffix}"
    key = "controle-" + secrets.token_hex(16)
    failures = []

    def check(condition, message):
        print(("OK   " if condition else "ECHEC") + " " + message)
        if not condition:
            failures.append(message)

    docker("volume", "create", volume)
    try:
        run(args.image, args.platform, volume, key, args.port, name, args.legacy)
        ready = wait_ready(args.port, name, args.timeout)
        check(ready, f"demarrage et /sante ({args.platform})")
        if ready:
            with urllib.request.urlopen(f"http://127.0.0.1:{args.port}/connexion", timeout=10) as response:
                headers = response.headers
                check(response.status == 200, "page de connexion (HTTP 200)")
                check("frame-ancestors 'none'" in headers.get("Content-Security-Policy", ""), "politique de securite du contenu")
                if not args.legacy:
                    check(headers.get("X-Frame-Options") == "DENY" and headers.get("X-Content-Type-Options") == "nosniff", "en-tetes anti-cadre et nosniff")
        user = docker("inspect", "-f", "{{.Config.User}}", args.image).stdout.strip()
        check(user not in ("", "0", "root") and not user.startswith(("0:", "root:")), f"utilisateur non root ({user})")
        logs = docker("logs", name, check=False)
        check(key not in logs.stdout + logs.stderr, "cle de chiffrement absente des journaux")
        docker("stop", name)
        header = subprocess.run(["docker", "cp", f"{name}:/data/comptes.sqlite3", "-"], capture_output=True).stdout
        check(len(header) > 0 and b"SQLite format 3" not in header[:2048], "base des comptes chiffree (en-tete illisible)")

        run(args.image, args.platform, volume, key, args.port, name, args.legacy)
        check(wait_ready(args.port, name, args.timeout), "redemarrage avec la meme cle (bases relues)")
        docker("stop", name)

        run(args.image, args.platform, volume, "mauvaise-" + secrets.token_hex(16), args.port, name, args.legacy)
        refused = not wait_ready(args.port, name, 60)
        check(refused, "mauvaise cle refusee")
    finally:
        docker("rm", "-f", name, check=False)
        docker("volume", "rm", "-f", volume, check=False)
    if failures:
        print(f"{len(failures)} controle(s) en echec", file=sys.stderr)
        return 1
    print("Image conforme.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
