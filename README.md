# Patria

Patria est une application web francaise auto-hebergee de suivi du budget du foyer et du patrimoine immobilier. Cette premiere version Java permet de se connecter, d'enregistrer des biens et d'y associer des prets. Elle ne se connecte a aucune banque ni service financier ; seules des donnees publiques (data.gouv.fr) sont consultees pour le prix du marche.

## Fonctionnalites actuelles

### Budget du foyer (accueil)

- Foyer de plusieurs personnes ; revenus nets avant impot (salaire mensuel, primes et 13e mois annuels, revenus ponctuels).
- Depenses saisies une seule fois avec leur frequence (mensuelle, trimestrielle, semestrielle, annuelle, ponctuelle) et leur premier paiement ; modeles en un clic (electricite, mutuelle, assurance auto...).
- Reprise automatique de l'immobilier, sans ressaisie : mensualites de pret, taxe fonciere (octobre, ou de janvier a octobre si elle est mensualisee dans les charges du bien ; prelevee en entier sur un compte joint ou une personne si choisi dans Repartition), taxe d'habitation (novembre), copropriete (chaque trimestre), assurance et autres charges des biens (sur 12 mois), loyers percus.
- Accueil : revenus, depenses et reste a vivre du mois, taux d'endettement (methode bancaire HCSF : credits ÷ revenus nets avant impot, loyers a 70 %, en vert sous 33 %, orange de 33 à 35 %, rouge au-delà ; détail, jauge et marge avant 33 % sur la page Budget), alertes (depenses non mensuelles des 3 prochains mois, mois en deficit), montant a mettre de cote chaque mois, graphique et detail des 12 prochains mois (un clic sur un mois du graphique ouvre son detail, avec le reste a vivre ou ce qui manque ; chaque ligne mene a sa saisie, la taxe fonciere aux charges du bien).
- Page Budget : moyenne mensuelle sur 12 mois, depenses classees par categorie (un clic sur une categorie montre ses depenses, chacune menant a sa saisie).
- Un budget par personne, identique a celui du foyer : selecteur « Foyer | Alice | Bob » sur l'Accueil et le Budget. Chaque personne voit ses revenus et sa part des depenses, biens (charges, loyers) et prets, avec son propre taux d'endettement.
- Repartition en pourcentages, a parts egales par defaut (y compris pour les personnes ajoutees ensuite) : dans le formulaire de chaque depense, et dans Administration → Repartition pour les biens et les prets. Un pret suit la repartition de son bien, sauf repartition propre.
- Compte joint : un membre du foyer de type « Compte joint », avec son propre budget (onglet a cote des personnes). Il recoit des virements et paie les depenses qu'on lui attribue ; il ne prend part aux « parts egales » que si on lui donne un pourcentage.
- Virements entre membres (Administration → Virements), recurrents ou ponctuels : depense pour qui envoie, recette pour qui recoit. Neutres pour le budget du foyer, sans effet sur le taux d'endettement. Les credits payes par un compte joint comptent a parts egales dans le taux d'endettement de chaque personne.
- Import de depenses par fichier CSV sans en-tete (Administration → Depenses), avec modele a telecharger et apercu avant enregistrement ou l'on choisit a qui reviennent les depenses (voir « Importer des depenses »).

### Immobilier

- Authentification par mot de passe, avec limitation des tentatives (10 echecs par 15 minutes).
- Gestion de plusieurs biens : type, commune, adresse, surface, cout d'acquisition, estimations prudente et optimiste, frais de vente.
- Actualisation automatique de l'estimation depuis sa date, selon l'evolution mensuelle des prix du departement (indice DVF, meme type de bien) ; le curseur et les scenarios partent de la valeur actualisee. Signalement d'une estimation de plus de 3 ans et d'un bien sans periode d'occupation.
- Fiche de bien : curseur de valeur retenue (scenarios pessimiste, prudent, fourchette, optimiste ou valeur libre), enregistre en direct sur le bien. Il est remis sur le scenario prudent quand les estimations du bien changent.
- Vue d'ensemble : somme des valeurs retenues de chaque bien (sans curseur), valeur nette indicative calculee bien par bien, plus-value latente, capital restant du, prochaine mensualite.
- Prets associes a un bien, crees a partir d'un echeancier CSV. Taux actuel, capital restant du et prochaine echeance sont calcules depuis l'echeancier a la date du jour ; un pret pas encore commence compte pour la totalite du capital emprunte. Interets payes et total du pret (interets seuls, hors assurance et frais) sur la fiche du pret et dans les tableaux de prets, avec une ligne Total.
- Apports personnels par bien.
- Charges par annee (taxe fonciere, copropriete, assurance, autre) : une seule saisie suffit, les autres annees sont estimees (taxe fonciere revalorisee chaque annee selon le coefficient legal, autres charges constantes).
- Occupation par periode : location (loyer percu) ou residence principale (loyer de reference saisi, compte comme loyer economise dans l'enrichissement reel) ; les jours sans periode comptent comme vacants. Rendement brut sur les seuls loyers percus.
- Charges : la taxe d'habitation (residences secondaires) s'ajoute aux types existants.
- Rentabilite reelle depuis l'achat : cout du credit paye (interets et frais des echeances passees), enrichissement reel (valeur apres frais de vente − cout d'acquisition − cout du credit − charges + loyers percus et economises) et multiple (valeur nette ÷ argent sorti de sa poche).
- Modification et suppression des biens, prets et apports.
- Persistance SQLite dans le volume Docker historique `patrimoine_data`, montants stockes au centime pres.
- Serveur HTTP Java natif : sans Spring, Tomcat ni pool de connexions.

La valeur nette indicative vaut : valeur estimee - frais de vente et fiscalite - capital restant du. Les frais recurrents annuels sont affiches a titre indicatif mais ne sont pas deduits.

## Mise a jour des coefficients de taxe fonciere

Les coefficients legaux de revalorisation des valeurs locatives sont dans `src/main/java/fr/patria/expense/PropertyTaxRevaluation.java`. Ajouter chaque annee la ligne de l'annee nouvelle ; a defaut, le dernier coefficient connu est repris.

## Prix au m² et prix du marche

Chaque bien affiche son prix au m² (valeur retenue ÷ surface, et cout d'acquisition ÷ surface) et le compare au prix du marche de son secteur, calcule a partir des ventes reelles publiees par l'administration fiscale :

- source : DVF geolocalisees (DGFiP, Etalab) et statistiques DVF mensuelles, sur data.gouv.fr, plus l'API Adresse (BAN) pour situer le bien ; aucune cle d'API ;
- ventes du meme type (appartement ou maison), de surface comparable (±40 %), sur les 3 dernieres annees publiees ;
- zone : 500 m autour de l'adresse, elargie a 1 km, puis 2 km, puis a la commune tant qu'il y a moins de 30 ventes comparables ;
- chaque vente est actualisee a aujourd'hui avec l'evolution des prix du departement ; les valeurs extremes sont ecartees ;
- resultat : mediane et fourchette (1er et 3e quartiles), garde 30 jours, recalcule si l'adresse, le type ou la surface changent. Bouton « Actualiser » sur la fiche du bien ;
- detail : « Voir les N ventes en detail » liste les ventes retenues pour la mediane (date, adresse, surface, prix, prix au m² actualise, distance), triables et filtrables, chacune avec un lien vers la carte publique DVF (explore.data.gouv.fr), ainsi qu'un lien vers la carte centree sur le bien.

Le calcul se fait en arriere-plan (quelques secondes) a l'ouverture de la fiche du bien. Il necessite un acces internet sortant depuis le conteneur vers `api-adresse.data.gouv.fr`, `files.data.gouv.fr` et `tabular-api.data.gouv.fr`. Sans acces, le reste de l'application fonctionne normalement.

Limites : DVF ne couvre pas l'Alsace-Moselle (57, 67, 68) ni Mayotte ; seules les ventes de la commune du bien (ou de son arrondissement a Paris, Lyon et Marseille) sont prises en compte.

L'import/export JSON sera ajoute dans une version ulterieure.

## Importer des depenses

Administration → Depenses → « Importer un fichier CSV ». Le fichier n'a **jamais de ligne d'en-tete** : chaque ligne est une depense, colonnes dans cet ordre.

```
libelle;categorie;montant;frequence;debut;fin
```

```
Mutuelle;Santé;35,69;Mensuel;01/01/2026;
Assurance emprunteur;Crédits;17,68;Mensuel;01/01/2026;05/09/2042
```

- `fin` est facultative (vide ou absente : sans fin).
- Categorie et frequence : le libelle affiche dans l'application (« Santé », « Mensuel », sans tenir compte des accents ni des majuscules) ou son code (`HEALTH`, `MONTHLY`).
- Dates au format 31/01/2026 ou 2026-01-31 ; montants avec virgule ou point, espaces et « € » acceptes.
- Separateur point-virgule (virgule acceptee si les montants n'en contiennent pas), guillemets acceptes autour d'un libelle ; 500 lignes au plus.
- « Telecharger le modele » donne deux lignes d'exemple.
- « Copier le prompt IA » copie un texte a coller dans un assistant IA (ChatGPT, Claude, Le Chat…) en y joignant vos documents (releves, avis d'imposition, contrats) : il renvoie le fichier dans ce format. Le prompt reste visible sous « Voir le prompt » (la copie automatique demande HTTPS ; sinon le texte est selectionne pour un Ctrl+C).

Le fichier ne dit pas a qui revient la depense. L'apercu, affiche avant tout enregistrement, permet de choisir l'attribution pour tout le fichier (parts egales ou une personne), puis ligne par ligne si besoin. Chaque depense reste modifiable ensuite, une par une, dans son formulaire (bloc Repartition).

Une seule ligne en erreur bloque l'import (rien n'est enregistre) et l'apercu indique quoi corriger ; une ligne d'en-tete est signalee comme erreur. Une depense deja saisie (meme libelle, montant, frequence et debut) est ignoree : reimporter un fichier ne cree pas de doublon.

## Importer un echeancier

Depuis la page « Prets et apports » d'un bien, bouton « Ajouter un pret » (ou « Gerer le pret » pour remplacer un echeancier existant), importez un fichier CSV UTF-8 (BOM accepte), de 1 Mio maximum. Un import valide remplace toutes les lignes precedemment importees pour ce pret.

L'en-tete est strict : seules les colonnes `date`, `type`, `montant`, `capital_rembourse`, `interets`, `frais`, `capital_restant_du` et `taux_annuel_pct` sont acceptees. `date` et `type` sont obligatoires ; les autres colonnes peuvent etre omises. Chaque ligne doit contenir au moins une valeur financiere. Les separateurs virgule et point-virgule sont acceptes, ainsi que les nombres avec virgule ou point decimal.

Les types autorises sont `DEBLOCAGE`, `ECHEANCE`, `REMBOURSEMENT_ANTICIPE`, `CONSOLIDATION` et `AJUSTEMENT`. La casse n'a pas d'importance. Trois fichiers generiques sont disponibles dans `docs/examples/`. Le bouton « Copier le prompt IA » donne un texte a coller dans un assistant IA avec le tableau d'amortissement pour obtenir ce fichier.

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
