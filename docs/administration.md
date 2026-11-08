# Administrer Patria

[Présentation](../README.md) · [Utilisation](utilisation.md) · [Confidentialité](confidentialite.md)

Ce guide concerne l'exploitant du serveur et le compte administrateur. Les **utilisateurs des foyers** utilisent le [guide utilisateur](utilisation.md) : ce sont deux rôles distincts.

## Avant de commencer

- Un serveur ou NAS avec Docker Engine et Docker Compose ; accès au terminal et droits pour utiliser Docker.
- Les fichiers [`compose.yaml`](../compose.yaml) et [`.env.example`](../.env.example), placés dans un même dossier privé.
- Un gestionnaire de mots de passe pour conserver les secrets, et un support de sauvegarde **hors du serveur**.
- Pour l'accès distant : HTTPS avec un reverse proxy ou un réseau privé adapté. N'exposez pas directement le port HTTP de Patria.

Les images sont publiées sur Docker Hub pour amd64 et arm64. Pour les utilisateurs : `supersouris7/patria:latest` (dernière version stable), ou `:X.Y.Z` pour fixer une version. `latest` devient disponible après le premier tag stable. Le canal `:unstable` suit chaque push sur `main` après les tests ; il est réservé à l'installation de développement. Sauvegardez avant de changer de version ou de mettre à jour.

## Première installation Docker

**Configurez le chiffrement avant le premier lancement et avant toute saisie financière.** Il protège les fichiers du volume et les copies volées, pas un serveur déjà compromis.

Dans le dossier de `compose.yaml` :

```bash
cp .env.example .env
chmod 600 .env
openssl rand -base64 32
```

Sans OpenSSL : `head -c 32 /dev/urandom | base64`. **Conservez immédiatement le résultat** dans votre gestionnaire de mots de passe, séparément des sauvegardes du volume.

Ouvrez `.env` et renseignez :

```dotenv
APP_PASSWORD=REMPLACER_PAR_UN_MOT_DE_PASSE_LONG_ET_UNIQUE
PATRIA_IMAGE=supersouris7/patria:latest
DB_ENCRYPTION_KEY=REMPLACER_PAR_LA_CLE_ALEATOIRE_GENEREE
DATABASE_PATH=/data/immobilier.sqlite3
PORT=8101
```

Ne copiez pas les valeurs d'exemple comme secrets. Pour la clé générée en base64 : une seule ligne, sans espace ni guillemets. Ne commitez jamais `.env`, les bases, les sauvegardes ou les documents personnels.

**Installation personnelle de développement :** laissez `PATRIA_IMAGE` absent (le Compose garde `unstable`) ou définissez explicitement `supersouris7/patria:unstable`. Modifier le canal ne change ni le volume ni la clé. Un push GitHub publie l'image, mais ne redéploie pas automatiquement votre conteneur ou votre stack Portainer.

Puis :

```bash
docker compose up -d
docker compose logs patria
```

Ouvrez `http://127.0.0.1:8101` **sur le serveur lui-même**. Connectez-vous avec `admin` et `APP_PASSWORD`. L'identifiant est modifiable par `ADMIN_LOGIN`.

Avant de saisir des données :

- Administration → Sécurité indique **Bases chiffrées**.
- La clé est récupérable depuis son lieu de conservation ; ce n'est pas seulement une valeur laissée dans `.env`.
- L'adresse utilisée à distance est en HTTPS.
- Vous avez prévu une sauvegarde externe et une restauration d'essai.

Les données sont dans `/data`, volume Docker historique `patrimoine_data`. Ne changez pas ce volume pour une installation existante : vous risqueriez d'ouvrir une base vide au lieu de retrouver vos données.

## Chiffrement des bases

`DB_ENCRYPTION_KEY` active SQLite3 Multiple Ciphers, ChaCha20-Poly1305. Chaque foyer, `comptes.sqlite3`, les copies avant migration et les foyers supprimés sont concernés. Une base en clair est chiffrée à son ouverture ; avec une clé, toutes les bases sont ouvertes au démarrage.

| Situation | Conséquence et action |
|---|---|
| Clé oubliée au premier lancement | L'application fonctionne **en clair**. Activez le chiffrement ci-dessous ; protégez aussi les anciennes copies. |
| Clé absente ou différente après activation | Les bases ne s'ouvrent plus. Remettez **la clé d'origine**, puis recréez le conteneur. Ne générez pas une nouvelle clé. |
| Clé définitivement perdue | Les bases et sauvegardes chiffrées sont irrécupérables. Aucun mot de passe administrateur ni intervention du développeur ne les déverrouille. |

Une clé manquante dans `.env` n'est pas forcément perdue : vérifiez votre gestionnaire de mots de passe ou le fichier secret. Ne publiez jamais sa valeur dans des journaux ou une demande d'aide.

### Installation existante en clair

1. Arrêtez Patria et faites une copie cohérente :

   ```bash
   docker compose stop patria
   docker compose cp patria:/data ./sauvegarde-avant-chiffrement
   ```

2. Générez la clé (`openssl rand -base64 32`), conservez-la en lieu sûr, puis ajoutez `DB_ENCRYPTION_KEY=...` dans `.env`.
3. Redémarrez :

   ```bash
   docker compose up -d --force-recreate
   docker compose logs patria
   ```

   Les journaux annoncent « Base chiffrée : … » pour les bases converties. Vérifiez aussi Administration → Sécurité et la lecture de vos données.
4. Faites une **nouvelle sauvegarde chiffrée**, puis une restauration d'essai. La copie avant chiffrement et les anciennes sauvegardes restent en clair : chiffrez-les avec un outil adapté ou éliminez-les selon vos règles de conservation. Ne supprimez pas votre seule copie vérifiée. Une suppression de fichier n'est pas une garantie d'effacement physique du support.

### Conservation et limites

- Gardez la même clé pendant toute la vie de l'installation. Changement de clé et retour en clair ne sont pas pris en charge.
- Conservez-la **à part des sauvegardes**, mais de façon à pouvoir retrouver les deux après perte du serveur.
- Alternative : `DB_ENCRYPTION_KEY_FILE=/run/secrets/patria_key`, avec un fichier secret monté et lisible par le conteneur. Le fichier a priorité sur la variable directe. Ne définissez qu'une source pour éviter les ambiguïtés.
- La personne qui exploite le serveur a accès à la clé et peut techniquement lire les données.
- L'aide `/aide/chiffrement` est lisible même sans connexion. L'accueil administrateur et la page Sécurité rappellent l'absence de chiffrement.

## Passer en HTTPS

HTTP ne protège ni le mot de passe, ni les données, ni le cookie de session. Ne l'utilisez à distance qu'après avoir configuré HTTPS. Patria alerte sur l'écran de connexion et à chaque session HTTP ; localhost est exempté.

Avec Caddy : un domaine pointant vers le serveur, les ports 80 et 443 accessibles et redirigés vers lui, puis ajoutez ce service **sous `services:`** dans votre Compose existant :

```yaml
  caddy:
    image: caddy:2
    restart: unless-stopped
    ports: ["80:80", "443:443"]
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile:ro
      - caddy-data:/data
```

Ajoutez aussi `caddy-data:` sous la section `volumes:` existante, sans retirer `patrimoine_data:`. Créez `Caddyfile` à côté de `compose.yaml` :

```caddyfile
patria.example.fr {
    reverse_proxy patria:8080
}
```

Remplacez le domaine, lancez `docker compose up -d`, puis ouvrez `https://patria.example.fr`. Caddy gère le certificat Let's Encrypt. Gardez le port de Patria limité à `127.0.0.1`, comme dans le fichier fourni. Le proxy doit écraser les en-têtes transmis par les clients, notamment `X-Forwarded-Proto`, et être le seul accès réseau à Patria. Vérifiez HTTPS **depuis un autre appareil** : la visite locale seule ne valide pas le chemin distant.

Alternatives : [Nginx Proxy Manager](https://nginxproxymanager.com/guide/), [Traefik](https://doc.traefik.io/traefik/https/acme/), [Tailscale Serve](https://tailscale.com/kb/1312/serve) pour un accès privé. [Documentation Caddy](https://caddyserver.com/docs/quick-starts/reverse-proxy).

## Créer les accès

Le compte administrateur ne voit aucune donnée financière **dans l'interface**. Il est le seul à créer les foyers, leurs membres et les comptes, et à régler les droits. Pour votre budget personnel, créez aussi un compte utilisateur.

1. Administration → Foyers → Nouveau foyer (le foyer principal existe déjà).
2. Dans la carte du foyer, ajoutez ses membres (personnes, puis compte joint). Le « × » d'un membre le supprime.
3. Utilisateurs → Nouvel utilisateur : choisissez le membre représenté et un mot de passe provisoire d'au moins 8 caractères.
4. Dans la carte du foyer, tableau **Qui voit quoi** : une ligne par compte du foyer (« Ajouter un compte à ce foyer » en ajoute un), une colonne par membre, puis **Totaux du foyer** et **Immobilier**. Cochez ce que chacun voit, puis « Enregistrer les droits ».

Chaque case donne l'accès complet (voir et modifier) :

- **Un membre** : ses revenus, dépenses et virements. Une ligne partagée avec un membre non coché reste visible, en lecture seule.
- **Totaux du foyer** : le total de tous les membres (les lignes des membres non cochés y apparaissent « Ligne masquée ») et les réglages communs (Guide de saisie, impôts du foyer).
- **Immobilier** : biens, prêts, apports, charges et répartition.
- Aucune case : le compte n'a pas accès au foyer.

La page d'un utilisateur résume ses droits dans chaque foyer, avec un lien vers le tableau. Le rôle de « gestionnaire » des versions précédentes n'existe plus : au démarrage, un ancien gestionnaire reçoit toutes les cases de son foyer, que vous pouvez ensuite décocher.

**Demandes de compte.** Le lien « Créer un compte » de la page de connexion affiche, au choix (Administration → Demandes de compte), un simple message « demandez à l'administrateur » ou un formulaire (nom, identifiant souhaité, message). Les demandes arrivent sur cette même page : « Créer le compte » avec un mot de passe provisoire, ou « Supprimer ». Au plus 30 demandes restent en attente.

Les mots de passe provisoires doivent être changés à la connexion suivante. Désactivation, suppression de compte ou réinitialisation déconnectent les sessions concernées. Les changements de droits sont journalisés et visibles dans « qui voit quoi ».

**Migration depuis une version où l'administrateur saisissait le budget :** les données restent présentes. Foyers propose les foyers auxquels aucun compte n'a accès : confiez-les à un compte existant ou créez votre compte personnel. Ce compte reçoit toutes les cases et reprend la progression du guide de saisie.

La page Sécurité montre les connexions, blocages (15 minutes après 10 échecs par IP ou 30 par identifiant), sessions, mots de passe provisoires, journal des droits et état de l'installation. Les connexions sont conservées 90 jours. Le rôle administrateur n'empêche pas l'exploitant technique du serveur de lire ou modifier les fichiers.

## IA locale (facultative)

Sans IA locale, les imports (relevés, échéanciers, exports de courtier) proposent un prompt à copier dans un assistant externe, avec un avertissement sur les données. Avec une IA locale, l'utilisateur dépose son document (PDF texte, CSV ou texte) et Patria le fait convertir **sur votre serveur** : rien ne sort. Les scans sans texte ne sont pas lus.

Prévoir environ **4 Go de mémoire libre** pour un modèle 3B (`qwen2.5:3b`, conseillé), **8 Go** pour un 7B (`qwen2.5:7b`, plus fiable). Sur processeur seul, comptez une à plusieurs minutes par document.

1. Ajoutez Ollama à côté de Patria dans `compose.yaml` (même réseau, aucun port publié) :

   ```yaml
     ollama:
       image: ollama/ollama:latest
       container_name: ollama
       volumes:
         - ollama_models:/root/.ollama
       restart: unless-stopped
   # et sous volumes:
     ollama_models:
   ```

2. Téléchargez le modèle une fois : `docker exec ollama ollama pull qwen2.5:3b`.
3. Administration → Sécurité → **IA locale** : cochez « Utiliser une IA locale », adresse `http://ollama:11434`, modèle `qwen2.5:3b`, puis « Enregistrer et tester ». Le résultat du test s'affiche (serveur joignable, modèle installé).

Décocher la case revient au prompt à copier. Les prompts restent toujours disponibles.

## Double authentification

Chaque compte, administrateur compris, l'active dans **Mon compte** : en plus du mot de passe, Patria demande le code à 6 chiffres d'une application d'authentification (Aegis, 2FAS, Google Authenticator, Microsoft Authenticator, Bitwarden…). Norme TOTP : ni SMS ni service extérieur ; le QR code est dessiné par Patria et la clé reste sur le serveur.

- **Fortement recommandée pour l'administrateur dès que Patria est accessible depuis Internet.** Patria le détecte (ouverture par un nom de domaine, ou connexion déjà venue d'une adresse IP publique) et l'affiche dans Sécurité, Foyers et Mon compte tant qu'elle n'est pas activée.
- À la connexion, « Se souvenir de cet appareil 30 jours » évite de retaper le code sur ce navigateur. Cinq codes faux annulent la connexion ; les codes faux comptent dans le blocage des 15 minutes.
- La page Sécurité indique, pour chaque compte, si la double authentification est activée et depuis quand.
- **Un utilisateur a perdu son téléphone** : Utilisateurs → son compte → « Retirer sa double authentification ». C'est journalisé, ses appareils retenus sont oubliés, et il voit un avis à sa connexion suivante.
- **L'administrateur a perdu son téléphone** : ajoutez `ADMIN_2FA_RESET=true` dans `.env`, redémarrez (`docker compose up -d --force-recreate`), connectez-vous, puis **retirez la ligne** et redémarrez encore.

## Sauvegarder et restaurer

Les copies automatiques « avant-migration » restent **sur le même disque** (3 dernières par base). Elles ne remplacent ni une sauvegarde périodique ni une copie hors serveur.

### Sauvegarde cohérente

Dans le dossier Compose, avec un **nouveau dossier de destination** à chaque sauvegarde :

```bash
docker compose stop patria
docker compose cp patria:/data ./sauvegarde-patria-2026-11-05
docker compose start patria
```

Vérifiez la réussite de la copie avant de continuer. Copiez le dossier sur un support externe ou un autre serveur protégé. Une base active peut avoir des écritures dans ses fichiers `-wal` : ne copiez pas un seul `.sqlite3` pendant que Patria tourne. Conservez **tout `/data`**, y compris comptes, foyers et fichiers annexes.

Conservez séparément la clé d'origine et une copie protégée de la configuration nécessaire à la restauration (Compose, version/digest de l'image, paramètres non secrets ; secrets dans votre gestionnaire). Une sauvegarde chiffrée sans la clé est inutilisable.

### Restauration

Testez d'abord sur une installation **isolée**, avec son propre volume, sans utilisateurs connectés et sans ports publics. Ne réutilisez pas le `container_name` ni le volume de production pour cet essai.

1. Préparez le Compose et `.env` de l'installation cible avec **la même clé** et, de préférence, la même version d'image que la sauvegarde. Créez le conteneur arrêté : `docker compose create patria`.
2. Arrêtez Patria s'il tourne. Sur un serveur existant, sauvegardez son `/data` actuel avant tout remplacement.
3. Le volume cible doit être **vide** : une copie Docker fusionne les dossiers et ne supprime pas les anciennes bases/WAL. Utilisez un nouveau volume pour l'essai ou faites préparer un volume vide ; ne lancez pas de suppression globale des volumes.
4. Copiez **le contenu** de la sauvegarde :

   ```bash
   docker compose cp ./sauvegarde-patria-2026-11-05/. patria:/data
   docker compose up -d patria
   docker compose logs patria
   ```

5. Vérifiez `/sante`, la connexion, les comptes et droits, chaque foyer, les revenus/dépenses, un bien et son prêt. Vérifiez le chiffrement dans Sécurité et l'accès HTTPS si applicable. Une réponse `ok` seule ne prouve pas que toutes les données sont restaurées.

Si la clé manque ou diffère : arrêtez, remettez la bonne clé, puis `docker compose up -d --force-recreate patria`. Ne recréez pas une base vide pour contourner le problème. N'utilisez pas une ancienne version de Patria sur une base déjà migrée vers un schéma plus récent.

## Sécurité de l'image et du conteneur

L'image finale part de `cgr.dev/chainguard/glibc-dynamic` (Wolfi) : seulement la glibc et les certificats, **sans shell ni gestionnaire de paquets**, reconstruite chaque jour avec les correctifs ; Java est réduit aux modules utiles (`jlink`). Elle tourne sous l'utilisateur 10001 (le même qu'avant : les volumes existants restent lisibles). Sans shell, `docker exec … sh` n'est plus possible. Pour diagnostiquer : `docker logs patria` ; copier un fichier avec `docker cp patria:/data/<fichier> .` ; ou lancer un conteneur d'outils qui partage les espaces du conteneur, sans le modifier : `docker run --rm -it --pid=container:patria --network=container:patria busybox`. Les sauvegardes et la restauration (copie du volume `/data`) ne changent pas.

Le `compose.yaml` durcit le conteneur ; reprenez ces lignes si vous utilisez votre propre fichier (Portainer…) :

```yaml
    read_only: true          # seuls /data (volume) et /tmp s'écrivent
    tmpfs:
      - /tmp
    security_opt:
      - no-new-privileges:true
    cap_drop:
      - ALL
```

Derrière un proxy HTTPS qui transmet `X-Forwarded-Proto: https`, Patria envoie aussi `Strict-Transport-Security` (un an).

## Mise à jour et supervision

Après une sauvegarde externe vérifiée :

```bash
docker compose pull
docker compose up -d
docker compose logs -f patria
```

Le schéma est migré au démarrage, avec copie préalable. Vérifiez la version et le commit en pied de page. `/sante` répond `ok` sans authentification. Un retour à une ancienne image peut nécessiter de restaurer **la sauvegarde d'avant mise à jour**, pas de réutiliser la base migrée.

## Configuration

| Variable | Utilité |
|---|---|
| `PATRIA_IMAGE` | Image choisie par Compose : `supersouris7/patria:latest` pour le public, `:X.Y.Z` pour une version fixe ; absent : `:unstable` pour l'installation personnelle. |
| `APP_PASSWORD` | Obligatoire : mot de passe administrateur ; empreinte `{bcrypt}…` acceptée. |
| `ADMIN_LOGIN` | Identifiant administrateur, `admin` par défaut. |
| `DB_ENCRYPTION_KEY` | Clé de chiffrement, au moins 16 caractères ; utilisez les 32 octets aléatoires générés plus haut. Absente : bases en clair. |
| `DB_ENCRYPTION_KEY_FILE` | Fichier contenant la clé, à monter dans le conteneur ; prioritaire. |
| `DATABASE_PATH` | Base principale, `/data/immobilier.sqlite3` par défaut ; autres bases à côté. |
| `PORT` | Port publié par Compose, `8101` par défaut, sur localhost. |
| `HTTP_PORT` | Port du serveur Java, `8080` par défaut ; ne pas changer sans adapter le Compose/proxy. |
| `OFFLINE` | `true` coupe les appels automatiques sortants : marché, taux, localisation des IP. |
| `KOFI_URL` | Lien « Soutenir Patria » : `https://ko-fi.com/supersouris7` par défaut ; une autre URL `https://ko-fi.com/<nom>` le remplace, vide le masque. |
| `ADMIN_2FA_RESET` | `true` retire la double authentification de l'administrateur au démarrage (téléphone perdu) ; à retirer ensuite. |

Les services utilisés hors mode hors ligne sont détaillés dans [Confidentialité](confidentialite.md). Sans réseau sortant, le budget reste utilisable, mais les données de marché ne sont plus actualisées.

## Suppressions

Une suppression de foyer archive sa base en `.supprime-AAAAMMJJ-HHMMSS` ; l'effacement définitif se fait dans Foyers → Foyers supprimés. Le foyer principal ne se supprime pas. Supprimer un compte ne supprime pas le budget de son foyer. Les sauvegardes externes conservent leurs anciennes données : définissez une durée de rétention et protégez-les.
