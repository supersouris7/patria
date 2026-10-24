# Patria

Patria est une application web francaise auto-hebergee de suivi immobilier. Cette premiere version Java permet de se connecter, d'enregistrer des biens et d'y associer des prets. Elle ne se connecte a aucune banque ni service financier.

## Fonctionnalites actuelles

- Authentification par mot de passe.
- Gestion de plusieurs biens : type, commune, code INSEE, surface, achat et estimation.
- Gestion des prets associes : capital, solde de reference, taux, duree et mensualite.
- Persistance SQLite dans le volume Docker historique `patrimoine_data`.
- Serveur HTTP Java natif : sans Spring, Tomcat ni pool de connexions.

L'amortissement detaille, les evenements de pret, DVF et l'import/export JSON seront ajoutes dans des versions ulterieures.

## Demarrage avec Docker

Creer un fichier `.env` a partir de l'exemple et y definir un mot de passe long :

```bash
cp .env.example .env
# Modifier APP_PASSWORD dans .env
docker compose up -d
```

L'application est disponible sur `http://127.0.0.1:8101` par defaut. Le service n'ecoute que localement : placer un reverse proxy HTTPS devant lui pour l'exposer sur le reseau. Les donnees restent dans le volume Docker `patrimoine_data` lorsque le conteneur est arrete. Ce nom est conserve afin de reutiliser la base des versions Python precedentes.

Commandes utiles :

```bash
docker compose logs -f patria
docker compose pull
docker compose up -d
```

`APP_PASSWORD` est obligatoire et doit etre un mot de passe long. Un hash `{bcrypt}...` reste accepte lors d'une migration. Ne commitez jamais `.env` ni le volume de donnees.

## Developpement

Prerequis : Java 21 et Maven 3.9+.

```bash
mvn verify
APP_PASSWORD=mot-de-passe-local mvn package
DATABASE_PATH=./patria.sqlite3 java -Xms16m -Xmx96m -jar target/patria.jar
```

La base de developpement est definie par `DATABASE_PATH` ; sa valeur par defaut est `/data/immobilier.sqlite3`.

## Publication Docker Hub

Chaque push sur `main` lance les tests puis construit et publie `supersouris7/patria:unstable` pour `linux/amd64` et `linux/arm64`.

Configurer les secrets du depot GitHub une fois :

- `DOCKERHUB_USERNAME`
- `DOCKERHUB_TOKEN` : access token Docker Hub avec autorisation de lecture et ecriture.
