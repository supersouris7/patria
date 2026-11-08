# Audit de sécurité de l'image Docker — 8 novembre 2026

Mesures faites avec Trivy 0.76.2 (`aquasec/trivy`, base de vulnérabilités du jour), le Debian Security Tracker et l'API OSV. Aucun chiffre n'est estimé : ce qui n'a pas pu être mesuré est signalé.

## État initial

| Élément | Constat |
|---|---|
| Image finale | `debian:bookworm-slim` (Debian 12.15) + Java réduit par `jlink` |
| Java | Temurin 21.0.13+9 (correctifs d'octobre 2026) |
| Compilation | `maven:3.9.9-eclipse-temurin-21`, étape séparée (rien de Maven dans l'image finale) |
| Paquets système (Trivy) | 93, dont shell, apt, perl, util-linux, systemd (bibliothèques), gnupg, tar, coreutils… |
| Paquets réellement utiles | glibc (et libgcc/libstdc++ pour les bibliothèques natives de Java) ; zlib est inutile (Java embarque la sienne) |
| Dépendances Java | sqlite-jdbc 3.51.3.0 (SQLite3 Multiple Ciphers), openpdf 2.0.3, qrcodegen 1.8.0, jbcrypt 0.4 : aucune vulnérabilité connue (OSV, Trivy) |
| Utilisateur | 10001, non root |
| `.dockerignore` | liste noire : `.opencode/`, `graphify-out/`, fichiers locaux entraient dans le contexte de construction (sans être copiés dans l'image) |
| Actions GitHub | `docker/*` en v3/v6, exécutées sous Node.js 20 (obsolète) |
| Tags Docker Hub | uniquement `unstable`, `X.Y.Z`, `latest` : aucun tag de SHA n'est créé |

## Vulnérabilités signalées (Docker Scout) — vérification

Statut Debian relevé le 8 novembre 2026 sur security-tracker.debian.org. Aucune n'a de correctif pour Bookworm.

| CVE | Paquet | Debian 12 (bookworm) | Correctif officiel | Utile à Patria |
|---|---|---|---|---|
| CVE-2026-76844 | zlib | vulnérable | Debian 13 seulement (DSA-6613-1, 1:1.3.dfsg+really1.3.1-1+deb13u1) | non |
| CVE-2026-85091 | zlib | vulnérable | Debian 13 seulement (même DSA) | non |
| CVE-2026-53615 | util-linux | vulnérable | aucun | non |
| CVE-2026-78410 | util-linux | vulnérable | aucun | non |
| CVE-2026-53613 | util-linux | vulnérable | aucun | non |
| CVE-2026-13595 | util-linux (libblkid) | vulnérable | aucun | non |
| CVE-2026-82560 | perl (Pod::Text) | vulnérable | aucun | non |
| CVE-2026-15534 | perl | vulnérable (trixie : *postponed*) | aucun | non |
| CVE-2026-84243 | glibc (gettext, variable LANGUAGE) | vulnérable | aucun | oui (glibc) |
| CVE-2026-86805 | glibc (ld.so) | vulnérable | aucun | oui (glibc) |
| CVE-2026-95818 | glibc (ld.so) | vulnérable | aucun | oui (glibc) |
| CVE-2026-97399 | glibc (strncasecmp, Power8) | vulnérable | aucun | non applicable (amd64/arm64) |
| CVE-2026-114690 | gcc-12 (libstdc++) | vulnérable | aucun | libstdc++ seulement |
| CVE-2026-114691 | gcc-12 (libstdc++) | vulnérable | aucun | libstdc++ seulement |
| CVE-2026-115455 | gcc-12 | **non affecté** selon Debian (« alloc handling is different ») : faux positif de Scout | — | — |
| CVE-2025-45582 | tar | aucune version Debian associée dans le tracker ; non signalée par Trivy | — | non (tar inutile) |

Trivy relevait aussi CVE-2023-45853 (zlib, critique) : Debian l'ignore pour bookworm (minizip non construit).

Conclusion : les vulnérabilités viennent de l'image de base, presque toutes de paquets dont Patria ne se sert pas, et Debian 12 ne les corrige pas. Une mise à jour des paquets ne suffit donc pas ; il faut changer d'image de base.

## Images de base comparées (Trivy, amd64)

| Image | Distribution | Paquets | CVE uniques | Remarques |
|---|---|---|---|---|
| `debian:bookworm-slim` (avant) | Debian 12.15 | 88 | 100 (1 critique, 10 élevées) | aucune avec correctif |
| `debian:trixie-slim` | Debian 13.7 | 78 | 87 | shell, apt |
| `gcr.io/distroless/cc-debian12` | Debian 12 | 11 | 47 | sans shell |
| `gcr.io/distroless/cc-debian13` | Debian 13 | 14 | 31 | sans shell |
| `eclipse-temurin:21-jre-noble` | Ubuntu 24.04 | 136 | 25 | shell, JRE complet |
| **`cgr.dev/chainguard/glibc-dynamic`** | Wolfi | **7** | **0** | sans shell ni gestionnaire de paquets, amd64 et arm64, reconstruite chaque jour |

Choix : Wolfi `glibc-dynamic`, figée par son empreinte et mise à jour par Dependabot. Compatible avec le Java de Temurin (glibc 2.44) et avec la bibliothèque native de SQLite3 Multiple Ciphers, testée ci-dessous. Contrepartie : pas de shell dans le conteneur (diagnostic : voir le guide d'administration).

## Résultats

| Indicateur | Avant (`unstable` publiée) | Après (image construite localement) |
|---|---|---|
| Image amd64 (Docker Hub, compressée) | 78,5 Mo | ~55 Mo (gzip local, approximatif ; chiffre exact après publication) |
| Image arm64 (Docker Hub, compressée) | 77,9 Mo | non mesurée localement (pas d'émulation arm64 ici) ; construite et contrôlée en CI |
| Image amd64 décompressée | 278 Mo | 183 Mo |
| CVE critiques (Trivy) | 1 | 0 |
| CVE élevées (Trivy) | 10 | 0 |
| CVE moyennes (Trivy) | 41 | 0 |
| CVE faibles (Trivy) | 48 | 0 |
| CVE sans correctif | 100 | 0 détectée par Trivy ; 1 avis Chainguard encore ouvert (CVE-2026-84243, glibc) |
| Paquets système | 93 | 7 |
| Java | Temurin 21.0.13+9 | Temurin 21.0.13+9 |
| Distribution | Debian 12.15 | Wolfi |
| Actions GitHub sous Node.js 20 | 4 | 0 |

CVE-2026-84243 n'est pas considérée comme corrigée : l'avis Chainguard (CGA-vmwj-jjh8-4jc2) la liste sans version corrigée pour glibc 2.44, même si Trivy ne la remonte pas. Portée : la variable `LANGUAGE` de gettext, que Patria ne lit pas et qui n'est pas réglable de l'extérieur du conteneur.

## Contrôles fonctionnels

- 147 tests Java et 11 tests du script de publication : réussis.
- `scripts/smoke_image.py` (amd64, conteneur en lecture seule, sans capacités) : démarrage, `/sante`, page de connexion, en-têtes de sécurité, base chiffrée (en-tête illisible), redémarrage avec la même clé, refus d'une mauvaise clé, clé absente des journaux, utilisateur non root.
- Compatibilité des données : base créée par l'image stable publiée (schéma v13, chiffrée) puis ouverte par la nouvelle image : migration jusqu'à v17 avec sa sauvegarde automatique, données relues, propriétaire du volume inchangé (10001).
- Image : aucun shell, aucun gestionnaire de paquets, aucun `.env`, aucune base SQLite, aucun secret dans l'historique ; programme et bibliothèque native appartenant à root.
- arm64 : non testé localement (pas d'émulation sur cette machine) ; le job `verify` de la CI construit, démarre et analyse l'image arm64 sous QEMU avant toute publication.
