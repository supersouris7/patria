# Patria

Patria est une application web francaise auto-hebergee de suivi immobilier. Cette premiere version Java permet de se connecter, d'enregistrer des biens et d'y associer des prets. Elle ne se connecte a aucune banque ni service financier.

## Fonctionnalites actuelles

- Authentification par mot de passe, avec limitation des tentatives (10 echecs par 15 minutes).
- Gestion de plusieurs biens : type, commune, adresse, surface, cout d'acquisition, estimations prudente et optimiste, frais de vente.
- Fiche de bien : curseur de valeur retenue (scenarios pessimiste, prudent, fourchette, optimiste ou valeur libre), enregistre en direct sur le bien. Il est remis sur le scenario prudent quand les estimations du bien changent.
- Vue d'ensemble : somme des valeurs retenues de chaque bien (sans curseur), valeur nette indicative calculee bien par bien, plus-value latente, capital restant du, prochaine mensualite.
- Prets associes a un bien, crees a partir d'un echeancier CSV. Taux actuel, capital restant du et prochaine echeance sont calcules depuis l'echeancier a la date du jour ; un pret pas encore commence compte pour la totalite du capital emprunte.
- Apports personnels par bien.
- Modification et suppression des biens, prets et apports.
- Persistance SQLite dans le volume Docker historique `patrimoine_data`, montants stockes au centime pres.
- Serveur HTTP Java natif : sans Spring, Tomcat ni pool de connexions.

La valeur nette indicative vaut : valeur estimee - frais de vente et fiscalite - capital restant du. Les frais recurrents annuels sont affiches a titre indicatif mais ne sont pas deduits.

DVF et l'import/export JSON seront ajoutes dans des versions ulterieures.

## Importer un echeancier

Depuis la page « Prets et apports » d'un bien, bouton « Ajouter un pret » (ou « Gerer le pret » pour remplacer un echeancier existant), importez un fichier CSV UTF-8 (BOM accepte), de 1 Mio maximum. Un import valide remplace toutes les lignes precedemment importees pour ce pret.

L'en-tete est strict : seules les colonnes `date`, `type`, `montant`, `capital_rembourse`, `interets`, `frais`, `capital_restant_du` et `taux_annuel_pct` sont acceptees. `date` et `type` sont obligatoires ; les autres colonnes peuvent etre omises. Chaque ligne doit contenir au moins une valeur financiere. Les separateurs virgule et point-virgule sont acceptes, ainsi que les nombres avec virgule ou point decimal.

Les types autorises sont `DEBLOCAGE`, `ECHEANCE`, `REMBOURSEMENT_ANTICIPE`, `CONSOLIDATION` et `AJUSTEMENT`. La casse n'a pas d'importance. Trois fichiers generiques sont disponibles dans `docs/examples/`.

Les fichiers d'echeancier deja produits avec les en-tetes `pretId`, `tauxAnnuelPct`, `capitalRembourse`, `autres` et `capitalRestantDu` restent acceptes a l'import. Ces colonnes sont converties automatiquement vers le format canonique.

## Architecture

Le code est organise en modules (biens, prets, apports, patrimoine) au-dessus d'un socle commun (web, interface, stockage). Voir [ARCHITECTURE.md](ARCHITECTURE.md) pour la carte des modules, les regles de dependance et la marche a suivre pour ajouter une fonctionnalite.

## Mise a jour et migrations

Au demarrage, le schema de la base est mis a jour automatiquement. Avant toute migration, une copie complete est ecrite a cote de la base (`immobilier.sqlite3.avant-migration-vN-AAAAMMJJ-HHMMSS`, dans le volume `patrimoine_data`). Ces copies peuvent etre supprimees une fois la nouvelle version verifiee.

Le service repond `ok` sur `/sante` (sans authentification) pour la supervision.

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

La base de developpement est definie par `DATABASE_PATH` ; sa valeur par defaut est `/data/immobilier.sqlite3`. Le port d'ecoute est `HTTP_PORT` (8080 par defaut). La variable `PORT` du fichier `.env` ne concerne que le port publie par Docker Compose.

## Publication Docker Hub

Chaque push sur `main` lance les tests puis construit et publie `supersouris7/patria:unstable` pour `linux/amd64` et `linux/arm64`.

Configurer les secrets du depot GitHub une fois :

- `DOCKERHUB_USERNAME`
- `DOCKERHUB_TOKEN` : access token Docker Hub avec autorisation de lecture et ecriture.
