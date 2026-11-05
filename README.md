# Patria

Patria est une application web francaise auto-hebergee de suivi du budget du foyer et du patrimoine immobilier. Cette premiere version Java permet de se connecter, d'enregistrer des biens et d'y associer des prets. Elle ne se connecte a aucune banque ni service financier ; seules des donnees publiques (data.gouv.fr) sont consultees pour le prix du marche.

## Fonctionnalites actuelles

### Budget du foyer (accueil)

- Foyer de plusieurs personnes ; revenus nets avant impot (salaire mensuel, primes et 13e mois annuels, revenus ponctuels).
- Depenses saisies une seule fois avec leur frequence (mensuelle, trimestrielle, semestrielle, annuelle, ponctuelle) et leur premier paiement ; modeles en un clic (electricite, mutuelle, assurance auto...).
- Reprise automatique de l'immobilier, sans ressaisie : mensualites de pret, taxe fonciere (octobre, ou de janvier a octobre si elle est mensualisee dans les charges du bien ; prelevee en entier sur un compte joint ou une personne si choisi dans Repartition), taxe d'habitation (novembre), copropriete (chaque trimestre), assurance et autres charges des biens (sur 12 mois), loyers percus.
- Previsionnel (page d'accueil) : revenus, depenses et reste a vivre du mois, taux d'endettement (methode bancaire HCSF : credits ÷ revenus nets avant impot, loyers a 70 %, en vert sous 33 %, orange de 33 à 35 %, rouge au-delà ; détail, jauge et marge avant 33 % sur la page Budget moyen), alertes (depenses non mensuelles des 3 prochains mois, mois en deficit), montant a mettre de cote chaque mois, graphique et detail des 12 prochains mois (un clic sur un mois du graphique ouvre son detail, avec le reste a vivre ou ce qui manque ; chaque ligne mene a sa saisie, la taxe fonciere aux charges du bien).
- Budget moyen : moyenne mensuelle sur 12 mois, depenses classees par categorie (un clic sur une categorie montre ses depenses, chacune menant a sa saisie).
- Un budget par personne, identique a celui du foyer : selecteur « Foyer | Alice | Bob » sur le Previsionnel et le Budget moyen. Chaque personne voit ses revenus et sa part des depenses, biens (charges, loyers) et prets, avec son propre taux d'endettement.
- Repartition en pourcentages, a parts egales par defaut (y compris pour les personnes ajoutees ensuite) : dans le formulaire de chaque depense, et dans Administration → Repartition pour les biens et les prets. Un pret suit la repartition de son bien, sauf repartition propre.
- Compte joint : un membre du foyer de type « Compte joint », avec son propre budget (onglet a cote des personnes). Il recoit des virements et paie les depenses qu'on lui attribue ; il ne prend part aux « parts egales » que si on lui donne un pourcentage.
- Virements entre membres (Administration → Virements), recurrents ou ponctuels : depense pour qui envoie, recette pour qui recoit. Neutres pour le budget du foyer, sans effet sur le taux d'endettement. Les credits payes par un compte joint comptent a parts egales dans le taux d'endettement de chaque personne.
- Import de depenses par fichier CSV sans en-tete (Administration → Depenses), avec modele a telecharger et apercu avant enregistrement ou l'on choisit a qui reviennent les depenses (voir « Importer des depenses »).

### Immobilier

- Connexion par identifiant et mot de passe, avec limitation des tentatives sur 15 minutes (10 echecs par adresse, 30 par identifiant) : un administrateur (defini dans l'environnement) et des utilisateurs crees par lui.
- Plusieurs foyers, chacun avec ses propres donnees ; chaque utilisateur ne voit que les foyers et les vues que l'administrateur lui ouvre.
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
- **Ventes du secteur** : sur la fiche du bien, une carte montre le nombre de ventes, leur repartition (graphique avec votre bien et la mediane) et les 5 ventes les plus comparables (surface proche, au plus pres, recentes). La page complete ajoute des filtres (periode, surface, distance), quatre ordres de tri, un plan des ventes autour du bien (dessine par Patria, sans service exterieur), un lien Google Maps et carte DVF par vente, et une synthese PDF.

Le calcul se fait en arriere-plan (quelques secondes) a l'ouverture de la fiche du bien. Il necessite un acces internet sortant depuis le conteneur vers `api-adresse.data.gouv.fr`, `files.data.gouv.fr`, `tabular-api.data.gouv.fr` et, pour le taux du marche, `data-api.ecb.europa.eu`. Sans acces, le reste de l'application fonctionne normalement.

Limites : DVF ne couvre pas l'Alsace-Moselle (57, 67, 68) ni Mayotte ; seules les ventes de la commune du bien (ou de son arrondissement a Paris, Lyon et Marseille) sont prises en compte.

L'import/export JSON sera ajoute dans une version ulterieure.

## Taux du marche et renegociation

A cote du taux de chaque pret (fiche du pret, Immobilier, fiche du bien, syntheses PDF), Patria affiche le taux moyen actuel des nouveaux credits immobiliers en France a taux fixe sur plus de 10 ans (statistiques MIR de la BCE, donnees Banque de France, taux nominal hors assurance) et l'ecart avec votre taux.

La fiche du pret ajoute un repere de renegociation : mensualite hors assurance recalculee au taux du marche (meme capital restant du, meme duree restante), economie d'interets, puis economie apres penalites de remboursement anticipe au plafond legal (6 mois d'interets, au plus 3 % du capital restant du). Lecture : a partir de 0,7 point d'ecart avec une economie nette, demander une simulation a sa banque.

Limites : moyenne nationale, publiee avec environ deux mois de decalage ; toutes les durees de plus de 10 ans sont regroupees (precision d'environ 0,3 point) ; comparaison indicative s'il reste moins de 10 ans ; frais de dossier, de garantie et assurance non comptes. Le taux est telecharge une fois par jour depuis `data-api.ecb.europa.eu` (acces internet sortant), sans cle d'API ; sans acces, la comparaison n'est pas affichee.

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

## Guide de saisie

L'icone Guide de saisie de l'en-tete (etincelles) guide le remplissage d'un foyer, etape par etape, sans chercher dans les menus :

1. **Documents a rassembler** : bulletins de salaire, avis d'impot, releves bancaires, tableaux d'amortissement, avis de taxe fonciere, contrats et abonnements, garde d'enfants, transports... Chacun dit ou trouver le chiffre utile. Coche verte automatique quand l'information est deja dans Patria, sinon « J'ai ce document » ou « Pas concerne ».
2. **Membres**, 3. **Revenus**, 4. **Logement** (locataire : loyer, charges, assurance habitation ; proprietaire : etapes suivantes).
5. **Biens**, 6. **Prets** (import de l'echeancier, avec le prompt IA), 7. **Charges et loyers des biens**.
8. **Depenses fixes** : listes a cocher par theme (energie, telecom, medias et abonnements, enfants, deplacements recurrents, sante, assurances, credits, vie courante), avec montant, frequence, premier paiement et « pour qui ». Une ligne deja saisie apparait cochee et n'est pas reproposee. Raccourci : le prompt IA pour tout importer depuis les releves bancaires.
9. **Impots** (prelevement a la source par personne, solde, acomptes), 10. **Virements** vers le compte joint, 11. **Repartition**, 12. **Recapitulatif**.

Une ligne deja saisie n'est jamais recreee (meme en renvoyant une ancienne page), sauf les lignes qui se saisissent par personne (impot a la source, forfaits mobiles, mutuelle...), toujours proposees avec les saisies existantes. Deux periodes d'occupation d'un meme bien ne peuvent pas se chevaucher (le loyer compterait deux fois), dans l'assistant comme dans l'administration. Chaque etape explique ce qu'elle attend (depense fixe ou variable, ce que Patria reprend deja de l'immobilier pour eviter les doublons), peut etre **passee**, et se saisit sur place. « Reprendre » saute les etapes faites ou passees ; « Tout revoir » les repasse toutes avec ce qui est deja saisi (revue annuelle) ; « Recommencer a zero » efface la progression, pas les donnees. La progression est gardee par personne connectee (table `wizard_steps`, migration v13). Un assistant IA externe n'est utile que pour transformer des PDF en fichiers a importer (echeancier, releves) ; tout le reste se saisit a la main. Un utilisateur a sa propre version : documents, revenus, logement, depenses, impots et virements de ses membres, avec les memes droits que dans l'administration.

## Revue annuelle

Guide de saisie → « Revue annuelle » : ce qui change d'une annee sur l'autre, ligne par ligne. Pour chaque revenu et chaque depense recurrente : nouveau montant et date d'effet (l'ancien montant s'arrete la veille, la repartition est reprise) ou « Arretee » (abonnement resilie). Pour chaque bien (administrateur) : taxe fonciere de l'annee et nouvelle estimation si la derniere a plus d'un an. Un calendrier rappelle quoi mettre a jour chaque mois (salaires en janvier, taux du prelevement a la source en septembre, taxe fonciere en octobre...). Un bandeau sur le Previsionnel propose la revue quand la derniere a plus d'un an.

## Bilan de sante

L'icone coeur de l'en-tete ouvre un diagnostic automatique, avec un voyant (a corriger, a surveiller, bon a savoir, en bonne sante), une explication et un lien pour corriger :

- **Finances** : reste a vivre, mois en deficit, taux d'endettement (HCSF), epargne de precaution (3 a 6 mois de depenses), part des depenses fixes, abonnements, salaires inchanges depuis plus d'un an ;
- **Biens** : estimation ancienne, ecart au marche (plus ou moins 15 %), occupation non renseignee, rendement brut, part encore due, pret plus cher que le marche ;
- **Impots** : prelevement a la source absent, taxe fonciere a saisir ou en forte hausse, loyers percus a declarer (seuil du micro-foncier), seuil de l'IFI.

Un encart du Previsionnel resume les points a corriger ou a surveiller ; synthese PDF. Un utilisateur a le bilan de sa vue (et des biens s'il consulte l'immobilier). Reperes indicatifs : ils ne remplacent pas un conseiller.

## Confidentialite

La page « Confidentialite » (lien en pied de page et sur l'ecran de connexion) explique ce que Patria garde et ce qui sort du serveur :

- toutes les donnees restent dans les bases SQLite du serveur ; aucun traceur, aucun service tiers dans les pages ; un seul cookie (session) ;
- sortent seulement : l'adresse ou la commune d'un bien vers l'API Adresse de l'Etat et les codes de commune vers les fichiers DVF (prix du marche), une requete sans donnee personnelle vers la BCE (taux) ; Google Maps et la carte DVF seulement sur clic ;
- `OFFLINE=true` coupe tous ces appels (plus de prix du marche ni de taux) ; les erreurs ne recopient pas l'adresse du bien dans les journaux.

Assistants IA : pour importer un echeancier ou des releves, il faut un assistant IA externe ; Patria ne lui envoie rien, c'est l'utilisateur qui colle le prompt et joint ses documents. Chaque prompt interdit a l'IA de recopier les donnees identifiantes (nom, adresse, IBAN, numeros de compte, numero fiscal, de securite sociale, de contrat) et demande des libelles generiques ; l'encart a cote du bouton explique comment masquer les documents, couper l'historique, ou utiliser un modele local.

Effacement : un foyer supprime est mis de cote sur le disque, puis efface definitivement depuis Administration → Foyers → Foyers supprimes ; seules les 3 dernieres copies « avant-migration » de chaque base sont gardees.

Qui voit quoi : chaque utilisateur ne voit que ce qui le concerne (ses membres, et seulement si on les lui ouvre les totaux du foyer et l'immobilier). L'administrateur ne voit aucune donnee financiere ; chaque acces ouvert est visible par les comptes du foyer (voir « Foyers et utilisateurs »).

### Chiffrement des bases

Avec `DB_ENCRYPTION_KEY` (16 caracteres au moins) dans `.env`, toutes les bases sont chiffrees sur le disque (SQLite3 Multiple Ciphers, ChaCha20-Poly1305) : les foyers, `comptes.sqlite3`, les copies « avant-migration » et les foyers supprimes. Une installation existante est chiffree au premier demarrage avec la cle, sur place.

```bash
# Generer une cle et la mettre dans .env
echo "DB_ENCRYPTION_KEY=$(openssl rand -base64 32)" >> .env
docker compose up -d
```

- **Gardez la cle en lieu sur, a part des sauvegardes** : sans elle, les bases sont illisibles et rien ne permet de les recuperer. Une cle fausse ou absente empeche Patria de demarrer (message explicite dans les journaux).
- La cle peut aussi venir d'un fichier (`DB_ENCRYPTION_KEY_FILE=/run/secrets/patria_key`, secret Docker).
- Le chiffrement protege un disque, un volume ou une sauvegarde voles. Il ne protege pas contre la personne qui administre le serveur : elle a la cle.
- Changer de cle ou revenir en clair n'est pas prevu pour l'instant.

## Passer en HTTPS

En HTTP, le mot de passe, le cookie de session et toutes les donnees circulent en clair : toute personne sur le meme reseau (Wi-Fi public ou partage, box, reseau d'entreprise) peut les lire, ou ouvrir Patria a votre place pendant 12 heures avec le cookie. Patria le signale :

- sur la page de connexion, un encadre rouge avant de taper le mot de passe ;
- a l'ouverture de session, une fenetre rouge (« J'ai compris » la ferme pour la session ; elle revient a chaque connexion) ;
- la page `/aide/https` (lisible sans connexion) reprend les explications et les liens.

Pas d'alerte quand Patria est ouvert depuis le serveur lui-meme (`localhost`), ni derriere un reverse proxy qui envoie `X-Forwarded-Proto: https` (Caddy, Nginx Proxy Manager et Traefik le font).

Avec Caddy (certificat Let's Encrypt automatique) : un nom de domaine qui pointe vers le serveur, ports 80 et 443 ouverts, puis dans `compose.yaml` :

```yaml
services:
  patria:
    # ... inchange : le port reste limite a 127.0.0.1
  caddy:
    image: caddy:2
    restart: unless-stopped
    ports: ["80:80", "443:443"]
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile:ro
      - caddy-data:/data
volumes:
  patrimoine_data:
  caddy-data:
```

et un fichier `Caddyfile` :

```
patria.example.fr {
    reverse_proxy patria:8080
}
```

Documentation : [Caddy, reverse proxy](https://caddyserver.com/docs/quick-starts/reverse-proxy), [Let's Encrypt](https://letsencrypt.org/fr/getting-started/), [Nginx Proxy Manager](https://nginxproxymanager.com/guide/), [Traefik et Let's Encrypt](https://doc.traefik.io/traefik/https/acme/), [Tailscale Serve](https://tailscale.com/kb/1312/serve) (acces a distance sans ouvrir de port).

## Syntheses PDF

Un bouton « Exporter en PDF » sur les pages de consultation telecharge une synthese au format A4 :

- **Previsionnel** : chiffres du mois, alertes, les 12 prochains mois (avec total), detail du mois en cours ;
- **Budget moyen** : moyennes mensuelles, taux d'endettement, depenses par categorie, revenus, depenses ;
- **Immobilier** : valeur, valeur nette, enrichissement reel, bilan, biens, prets, taxes foncieres ;
- **Fiche d'un bien** : scenarios de valeur, bilan, prets, apports, prix au m² face au marche ;
- **Pret** : situation a date, bilan par annee, echeancier complet.

La synthese reprend la vue affichee (foyer ou membre) et les droits de la personne connectee. Le PDF est produit par le serveur (bibliotheque OpenPDF), avec les polices standard.

## Foyers et utilisateurs

**Administrateur.** Il se connecte avec l'identifiant `admin` (ou `ADMIN_LOGIN`) et le mot de passe `APP_PASSWORD` de l'environnement. Il n'a pas de budget et **ne voit aucune donnee financiere** (ni revenus, ni depenses, ni biens) : son en-tete n'a que Foyers et Securite. Il est le seul a creer les comptes et a regler les droits. Ses pages :

- **Securite** : connexions echouees (date, identifiant tente, adresse IP, pays et ville), adresses et identifiants bloques, derniere connexion et sessions ouvertes de chaque compte, mots de passe provisoires, journal des droits (comptes crees, acces ouverts ou retires, mots de passe reinitialises, foyers crees ou supprimes), etat de l'installation (HTTPS, chiffrement, mode hors ligne, sauvegardes, version). Journal des connexions garde 90 jours ; la localisation des IP est demandee a ipwho.is (seule l'adresse est envoyee), sauf en mode hors ligne.
- **Foyers** : membres (noms seulement), comptes qui y ont acces, **avancement de la configuration** de chaque compte (pourcentage du Guide de saisie, etapes faites, passees, restantes, prochaine etape, derniere revue annuelle ; aucun montant), renommer, supprimer.
- **Utilisateurs** et **Mon compte**.

On cree d'abord un **foyer**, puis ses comptes :

1. Administration → Foyers → « Nouveau foyer ». Chaque foyer a sa propre base (`foyer-N.sqlite3`, a cote de DATABASE_PATH) : membres, revenus, depenses, virements, biens, prets, charges. La base existante est le foyer principal (il ne peut pas etre supprime). Un foyer supprime n'est pas efface tout de suite : sa base est renommee en `.supprime-AAAAMMJJ-HHMMSS`, puis peut etre effacee definitivement dans « Foyers supprimes ».
2. Dans la carte du foyer : « Ajouter le membre » (les personnes, puis le compte joint).
3. Utilisateurs → « Nouvel utilisateur », champ « Ce compte represente » (un membre existant, ou un nouveau membre a son nom). Pour la personne qui saisit tout, cocher ensuite **Gestionnaire du foyer** dans ses droits.

**Passage a cette version.** Les donnees saisies jusqu'ici par l'administrateur ne bougent pas, mais il ne les voit plus. La page Foyers propose « foyers sans gestionnaire » : nom, identifiant et mot de passe provisoire du compte personnel (par exemple `william`), foyers a lui confier. Ce compte en devient gestionnaire, est relie au membre qui porte son nom, et reprend la progression du Guide de saisie de l'administrateur.

Un **membre** est une personne du budget (ou un compte joint) ; un **utilisateur** est un compte de connexion. Un membre appartient a son foyer et n'en change pas. Un bien, lui, se deplace d'un foyer a l'autre depuis sa fiche d'administration (« Foyer du bien »), avec ses prets, echeanciers, apports, charges, occupation et prix du marche ; sa repartition entre membres repart a « parts egales ».

Droits d'un utilisateur, pour chaque foyer (Utilisateurs → un utilisateur → Foyers et droits) : acces, **gestionnaire du foyer**, vue « Foyer » (total de tous les membres), les membres qu'il represente ou suit (une personne, le compte joint...), consultation de l'immobilier. Identifiant, nom, mot de passe provisoire (8 caracteres au moins), compte actif ou non, reinitialisation du mot de passe, suppression.

**Gestionnaire.** Un utilisateur gestionnaire d'un foyer y voit et saisit tout : membres, revenus, depenses, biens, prets, repartition, Guide de saisie, imports. Il ne cree pas de compte et ne regle pas les droits. Gestionnaire de deux foyers, il peut deplacer un bien de l'un a l'autre.

**Transparence.** Comme il cree les comptes, l'administrateur pourrait s'en creer un et se l'ouvrir. Patria le rend donc visible :

- une icone d'information jaune a cote du foyer, dans l'en-tete, dit qui voit quoi : « Paul et Camille ont acces aux informations du compte joint », « Seul vous pouvez voir les informations de Paul », « Gestion gere tout le foyer », « Immobilier : ... » ;
- une pastille signale les acces ouverts, modifies ou retires depuis la derniere lecture (« 05/11/2026 · Jules : acces ouvert (totaux du foyer) »), jusqu'a « J'ai vu » ;
- un mot de passe choisi par l'administrateur (creation du compte, reinitialisation) est provisoire : il faut le changer avant toute autre page, et Patria dit que l'administrateur l'a choisi, et quand. L'administrateur ne connait donc jamais le mot de passe en usage.

**Utilisateur.** Il ne voit que les foyers ouverts (selecteur dans l'en-tete s'il en a plusieurs) et, dans chacun, les onglets ouverts dans Previsionnel et Budget moyen. Dans Administration, il a Revenus, Depenses, Virements et Mon compte :

- il voit les lignes qui concernent au moins une de ses vues (toutes avec la vue « Foyer ») ;
- il ajoute, modifie et supprime celles qui ne concernent que ses vues ; une ligne partagee avec un autre membre s'ouvre en lecture seule (« Voir ») ;
- les liens du budget ne menent qu'aux pages qu'il peut ouvrir ;
- « parts egales » se repartit entre ses membres seulement ;
- pas de gestion des membres, des biens ni de la repartition (sauf gestionnaire), jamais des foyers ni des utilisateurs ; l'immobilier en lecture seule, s'il est ouvert.

Il change son mot de passe dans « Mon compte » (icone de l'en-tete, ou Administration → Mon compte sur smartphone). Desactiver un compte, le supprimer ou reinitialiser son mot de passe le deconnecte aussitot.

**Premiere connexion.** Chaque compte (administrateur compris) voit d'abord un accueil en quatre ecrans, avec « Suivant » : ce que fait Patria, pour qui (famille, proches, amis), la confidentialite (auto-heberge, chiffrement, appels exterieurs), et le soutien au developpement. Il ne revient pas ensuite, sauf depuis « Mon compte » → « Revoir l'accueil ». Ensuite, tant que le Guide de saisie n'est pas termine, un encadre jaune du Previsionnel indique « Configuration terminee a x % », avec « Terminer les imports » et « Ignorer ».

Les foyers, utilisateurs et droits sont dans `comptes.sqlite3`, a cote de DATABASE_PATH (mots de passe chiffres avec bcrypt). Pour une sauvegarde, copier tout le volume de donnees.

## Architecture

Le code est organise en modules (biens, prets, apports, patrimoine) au-dessus d'un socle commun (web, interface, stockage). Voir [ARCHITECTURE.md](ARCHITECTURE.md) pour la carte des modules, les regles de dependance et la marche a suivre pour ajouter une fonctionnalite.

## Mise a jour et migrations

Au demarrage, le schema de la base est mis a jour automatiquement. Avant toute migration, une copie complete est ecrite a cote de la base (`immobilier.sqlite3.avant-migration-vN-AAAAMMJJ-HHMMSS`, dans le volume `patrimoine_data`). Seules les 3 dernieres copies de chaque base sont gardees.

Le service repond `ok` sur `/sante` (sans authentification) pour la supervision.

## Demarrage avec Docker

Creer un fichier `.env` a partir de l'exemple et y definir un mot de passe long :

```bash
cp .env.example .env
# Modifier APP_PASSWORD dans .env
docker compose up -d
```

L'application est disponible sur `http://127.0.0.1:8101` par defaut. Le service n'ecoute que localement : placer un reverse proxy HTTPS devant lui pour l'exposer sur le reseau (voir « Passer en HTTPS »). Les donnees restent dans le volume Docker `patrimoine_data` lorsque le conteneur est arrete. Ce nom est conserve afin de reutiliser la base des versions Python precedentes.

Commandes utiles :

```bash
docker compose logs -f patria
docker compose pull
docker compose up -d
```

`APP_PASSWORD` est obligatoire et doit etre un mot de passe long : c'est celui de l'administrateur, dont l'identifiant est `admin` (modifiable avec `ADMIN_LOGIN`). Un hash `{bcrypt}...` reste accepte lors d'une migration. Ne commitez jamais `.env` ni le volume de donnees.

## Developpement

Prerequis : Java 21 et Maven 3.9+.

```bash
mvn verify
APP_PASSWORD=mot-de-passe-local mvn package
DATABASE_PATH=./patria.sqlite3 java -Xms16m -Xmx96m -jar target/patria.jar
```

La base de developpement est definie par `DATABASE_PATH` ; sa valeur par defaut est `/data/immobilier.sqlite3`. Le port d'ecoute est `HTTP_PORT` (8080 par defaut). La variable `PORT` du fichier `.env` ne concerne que le port publie par Docker Compose.

Le pied de page affiche la version et le commit de l'installation : `mvn package -Dgit.commit=$(git rev-parse HEAD)` en local ; l'image Docker recoit le commit par `--build-arg GIT_COMMIT=...` (la CI le fait).

## Soutenir le developpement

Patria est gratuit et sans publicite. Un lien « Soutenir Patria » (Ko-fi) est affiche en pied de page et a la fin de l'accueil. C'est un simple lien : aucune page ne charge de script ni d'image depuis ko-fi.com. `KOFI_URL=https://ko-fi.com/<nom>` choisit la page ; `KOFI_URL=` (vide) masque le lien.

## Publication Docker Hub

Chaque push sur `main` lance les tests puis construit et publie `supersouris7/patria:unstable` pour `linux/amd64` et `linux/arm64`.

Configurer les secrets du depot GitHub une fois :

- `DOCKERHUB_USERNAME`
- `DOCKERHUB_TOKEN` : access token Docker Hub avec autorisation de lecture et ecriture.
