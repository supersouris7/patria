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

### Exposer sur Internet depuis une box (port 443)

Pour quelques proches, l'option la plus sûre reste de **ne rien ouvrir** : un VPN comme [Tailscale](https://tailscale.com/kb/1312/serve) ou WireGuard donne l'accès sans exposer Patria. Si vous ouvrez quand même le port 443 :

1. **Nom de domaine dynamique** : votre IP de box change. Créez un nom gratuit chez [DuckDNS](https://www.duckdns.org/) (ou DynHost chez OVH, ou le DNS dynamique intégré à la box) et faites-le mettre à jour par la box ou un petit conteneur.
2. **Box** : bail DHCP fixe pour le serveur, puis redirection **TCP 443** (et 80 si vous voulez la redirection automatique de HTTP vers HTTPS) vers ce serveur. Rien d'autre : jamais le port de Patria (`8101`).
3. **Caddy** comme ci-dessus, avec votre nom DuckDNS. Avec le seul port 443 ouvert, Caddy obtient quand même son certificat (défi TLS-ALPN). Caddy remplace les en-têtes `X-Forwarded-*` envoyés par le client : Patria ne les croit que s'ils viennent de la machine locale ou du réseau privé (le proxy).
4. **Avant d'ouvrir le port** :
   - `APP_PASSWORD` long (16 caractères ou plus) et unique ; **double authentification** activée pour l'administrateur, conseillée pour chaque compte ;
   - inscriptions **privées** (sur invitation, par défaut) : seuls les invités créent un compte ;
   - `DB_ENCRYPTION_KEY` définie et sauvegardée hors du serveur, sauvegardes de `/data` vérifiées ;
   - une image **stable** (`latest` ou `X.Y.Z`) plutôt que `unstable`, mise à jour régulièrement.
5. **Vérifier depuis le réseau mobile** (Wi-Fi coupé) :
   - `https://votre-nom.duckdns.org` s'ouvre sans alerte de certificat ;
   - `curl -sI https://votre-nom.duckdns.org/connexion` montre `strict-transport-security` et `content-security-policy` ;
   - `http://VOTRE-IP:8101` ne répond pas ;
   - Sécurité → Connexions : les adresses affichées sont celles des visiteurs, pas celle du proxy.
6. **Ensuite** : surveillez Sécurité → Connexions échouées. Les essais de mot de passe sont limités (10 échecs par adresse et 30 par identifiant en 15 minutes) et les codes de double authentification aussi.

## Inscriptions privées ou publiques

Au premier lancement, Foyers ouvre **Installation** (Gérer → Installation, modifiable ensuite) :

- **Privée — sur invitation** : les comptes se créent sur invitation ; le réglage d'e-mail confirmé existant est conservé.
- **Publique — inscriptions ouvertes** : chacun peut créer son compte et son foyer ; l'adresse publique en `https://` est obligatoire. Sans double authentification administrateur, les listes de comptes et les pages de foyers redirigent vers son activation. Ce contrôle ne bloque pas toutes les routes administrateur : activez la double authentification avant toute exposition publique.

Ces choix ne déplacent pas Patria et n'activent ni HTTPS ni le chiffrement des bases. Une installation chez vous ou chez OVH peut être privée ou publique. Configurez HTTPS, chiffrement et sauvegardes séparément. **Enregistrer à nouveau ce choix réinitialise la politique d'inscription** : sur invitation en privé, ouverte en public ; elle reste ensuite modifiable dans Utilisateurs → Inscriptions.

**Confirmation d'e-mail en public :** si SMTP est déjà configuré lors de l'enregistrement du mode public, Patria active l'exigence de confirmation pour les nouveaux comptes. Si SMTP est configuré après, cochez explicitement « Exiger un e-mail confirmé » dans Sécurité → E-mail ; la case reste modifiable. Sans cela, les inscriptions publiques peuvent se faire sans e-mail confirmé. Testez l'envoi avant l'ouverture au public.

La même page porte l'adresse publique et deux textes facultatifs : **Conditions d'utilisation** (une case à cocher apparaît alors à l'inscription) et **Mentions légales**, publiés sur `/conditions` et `/mentions-legales` et liés en bas des pages de connexion.

**Derrière un proxy.** Les liens d'invitation et des e-mails partent de l'adresse publique. Sans elle, Patria reprend l'hôte de la requête, en `https` seulement si l'en-tête `X-Forwarded-Proto` vient d'un proxy local ou du réseau privé : réglez l'adresse publique dès qu'un proxy ou un nom de domaine est en place.

## E-mail (facultatif)

Sécurité → **E-mail** : serveur SMTP de votre fournisseur (Brevo, OVH, Gmail avec un mot de passe d'application…), port et chiffrement (STARTTLS 587 ou TLS 465), expéditeur, et **adresse publique** de Patria (`https://…`), qui sert aux liens des e-mails : Patria ne prend jamais l'adresse dans la requête, qu'un tiers pourrait falsifier. « Tester » envoie un e-mail de contrôle. Les e-mails partent en deux versions, texte et HTML (bouton pour le lien).

- Sans réglage : l'e-mail des comptes est facultatif et rien n'est envoyé.
- Avec réglage : **Mot de passe oublié** apparaît sur la page de connexion (lien valable 30 minutes vers l'e-mail confirmé, même réponse que le compte existe ou non, envois limités ; la double authentification reste demandée).
- « Exiger un e-mail confirmé » : une inscription ou une invitation demande une adresse, à confirmer à la première connexion avant toute autre page. Les comptes existants ne sont pas concernés.

Après le premier mot de passe, chaque nouveau compte se voit **proposer la double authentification** (« Activer maintenant » ou « Plus tard »).

## Comptes, foyers et droits

Chaque foyer se gère lui-même. L'administrateur **ne crée aucun compte** et ne voit aucune donnée financière dans l'interface : il supervise et dépanne. En privé comme en public, ce fonctionnement est identique ; le choix règle aussi l'adresse, certains contrôles et l'exigence initiale d'e-mail décrits ci-dessus.

**Comment on crée un compte.** Toujours par la personne elle-même, de deux façons :

1. **Inscription** (« Créer un compte » sur la page de connexion, si les inscriptions sont ouvertes) : prénom, identifiant, mot de passe, e-mail (exigé et confirmé si vous l'avez réglé), nom du foyer. Patria crée le compte **et son foyer**, avec un membre à son nom ; la personne en est **gestionnaire**.
2. **Invitation** : un lien valable 7 jours, à usage unique. La personne crée son compte depuis ce lien (ou, si elle en a déjà un, se connecte et l'accepte). Deux sortes de liens :
   - le **gestionnaire d'un foyer** invite à le rejoindre (Gérer → Mon foyer et les droits → Inviter quelqu'un), en choisissant le membre du budget (existant, nouveau à son prénom, ou aucun) et les droits de départ ; l'e-mail part tout seul si l'envoi est réglé, sinon il transmet le lien ;
   - l'**administrateur** invite à **créer son foyer** (Foyers → « Inviter une personne à créer son foyer »), ou à **reprendre un foyer sans gestionnaire** (« Créer un lien de gestion »).

**Réglage des inscriptions** (Utilisateurs → Inscriptions) :

| Réglage | Qui peut créer un compte | Pour |
|---|---|---|
| **Sur invitation** (par défaut) | les invités d'un gestionnaire, et vos liens « créer son foyer » | auto-hébergement familial |
| **Ouvertes** | tout le monde (son propre foyer), plus les invitations | inscriptions publiques, quel que soit l'hébergement |
| **Fermées** | personne, sauf vos liens « créer son foyer » | geler une installation |

Les inscriptions sont limitées à 5 comptes par heure et par adresse, avec un piège à robots. Pour une installation ouverte, réglez l'envoi d'e-mails et cochez « Exiger un e-mail confirmé ».

**Le gestionnaire** (un ou plusieurs par foyer) règle, dans Gérer → Mon foyer et les droits :

- les **membres du budget** (personnes, comptes joints ; un enfant peut être un membre sans compte) ;
- le tableau **Qui voit quoi** : une ligne par compte, une colonne par membre, puis **Totaux du foyer**, **Immobilier** et **Gestionnaire**. Chaque case donne l'accès complet (voir et modifier) : un membre, ses lignes (une ligne partagée avec un membre non coché reste en lecture seule) ; les totaux, le total de tous les membres (lignes masquées comprises) et les réglages communs ; l'immobilier, biens, prêts, apports, charges et répartition. Aucune case : plus d'accès ;
- les **invitations** en attente (annulables) et le nom du foyer.

Être gestionnaire ne change pas ce qu'on voit : il coche aussi ses propres cases et peut choisir de ne pas voir tout le foyer. Il reste toujours au moins un gestionnaire. Chacun voit le résumé de ce tableau dans l'icône « i » de l'en-tête, avec les changements récents ; tout est inscrit au journal.

**L'administrateur** :

- **supervise** : Foyers et Utilisateurs, deux listes faites pour beaucoup de comptes (indicateurs en tête, recherche sans accents, filtres, tri, pages de 50). Utilisateurs filtre les comptes actifs, désactivés, sans double authentification, inactifs depuis 90 jours ou sans foyer, et applique une action à la sélection (désactiver, réactiver, supprimer). Foyers montre membres, comptes et gestionnaires, sans aucun montant, et signale les foyers sans gestionnaire ;
- **dépanne** : réinitialiser un mot de passe (provisoire, à changer à la connexion suivante), retirer une double authentification, désactiver ou supprimer un compte, nommer un gestionnaire parmi les comptes d'un foyer qui n'en a plus, ou créer un lien de gestion ;
- **règle le serveur** : inscriptions, Sécurité, e-mail, IA locale ; supprime un foyer abandonné (sa base est mise de côté).

**Passage à cette version :** les comptes et les droits existants ne changent pas. Au premier démarrage, un compte qui voyait **tout** un foyer (tous ses membres, les totaux et l'immobilier) en devient gestionnaire. Un foyer sans gestionnaire est signalé dans Foyers, avec « Nommer gestionnaire » et « Créer un lien de gestion ». Les anciennes « demandes de compte » ne sont plus utilisées (elles restent dans la base) ; l'ancien réglage vaut « sur invitation ».

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

Un foyer se supprime depuis sa fiche (Foyers → le foyer → Supprimer) ou par son gestionnaire (Mon foyer et les droits → Supprimer le foyer, en retapant son nom). La base est archivée en `.supprime-AAAAMMJJ-HHMMSS` ; l'effacement définitif se fait dans Foyers → Foyers supprimés. Le dernier foyer de l'installation reste. Supprimer un compte ne supprime pas le budget de son foyer. Les sauvegardes externes conservent leurs anciennes données : définissez une durée de rétention et protégez-les.
