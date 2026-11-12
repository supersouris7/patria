# Utiliser Patria

[Présentation](../README.md) · [Installation et administration](administration.md) · [Confidentialité](confidentialite.md)

Patria vous aide à prévoir votre reste à vivre, répartir les dépenses et suivre votre immobilier. Pas besoin de connecter une banque ni de posséder un bien pour utiliser le budget.

## Première connexion

Vous créez votre compte vous-même : avec le lien d'invitation reçu du gestionnaire de votre foyer (vous le rejoignez avec les droits qu'il a choisis), ou par « Créer un compte » sur la page de connexion si les inscriptions sont ouvertes (vous créez votre foyer et en êtes gestionnaire). Une barre d'étapes (Compte, E-mail, Sécurité, Découverte) guide l'arrivée : confirmation de l'e-mail si l'installation l'exige, proposition de double authentification, puis un écran d'accueil adapté à votre rôle. « Visiter Patria » pose ensuite des bulles sur les vrais éléments de l'interface (Budget, Patrimoine, Bilan, Qui voit quoi, Gérer, recherche ; sur smartphone, le menu) ; « Passer » l'évite. Mon compte → « Revoir la visite guidée » la relance.

Un **membre** est une personne du budget ou un compte joint. Un **utilisateur** est un compte de connexion. Le **gestionnaire** d'un foyer (un ou plusieurs) gère ses membres, invite les autres et règle qui voit quoi (Gérer → Mon foyer et les droits). L'**administrateur** supervise l'installation et dépanne les comptes, sans voir les finances.

- Le gestionnaire coche, pour chaque compte, les membres auxquels il a accès, les **totaux du foyer**, l'**immobilier** et s'il est **gestionnaire**. Chaque case donne l'accès complet : voir et modifier. Un gestionnaire règle aussi ses propres cases (il peut choisir de ne pas tout voir).
- Une ligne partagée avec un membre auquel vous n'avez pas accès est en lecture seule. Dans les totaux du foyer, les lignes de ces membres apparaissent « Ligne masquée ».
- Les totaux du foyer donnent aussi les réglages communs ; l'immobilier, les biens, prêts, apports et la répartition.
- L'icône jaune « qui voit quoi », à côté du foyer, indique les accès et leurs changements récents. Cela ne protège pas contre l'exploitant du serveur : [limites de confidentialité](confidentialite.md).

Sélectionnez le foyer dans l'en-tête si vous en avez plusieurs. Sur la page de connexion, « Afficher » montre le mot de passe saisi ; à la création, une jauge indique sa solidité. Mon compte permet de changer le mot de passe. Déconnexion ferme la session ; elle expire au bout de 12 heures.

**Profil et avatar.** Mon compte → Profil : prénom et nom (le nom affiché devient « Prénom Nom »), date de naissance, téléphone (visible de vous et de l'administrateur), et le membre du budget que vous êtes dans le foyer affiché (« c'est Paul dans le budget » apparaît dans le tableau des droits). Votre avatar rond montre vos initiales sur une couleur fixe, ou une photo JPEG/PNG : Patria la recadre en carré de 256 pixels et la réenregistre, ce qui retire ses métadonnées (lieu, appareil). La photo n'est visible que des comptes qui partagent un de vos foyers et de l'administrateur. L'avatar s'affiche dans l'en-tête, la liste des utilisateurs et le tableau des droits.

**Accès API (tableau de bord).** Mon compte → Accès API : créez un jeton en lecture seule pour Homepage, Homarr ou Dashy (il ne s'affiche qu'une fois, se révoque à tout moment, dix au plus). `GET /api/v1/resume` avec l'en-tête `Authorization: Bearer patria_…` renvoie en JSON le foyer, le mois, revenus, dépenses et reste à vivre du mois, l'épargne, le patrimoine net, le nombre d'alertes du bilan et l'avancement du guide (montants arrondis à l'euro, mêmes droits que vos pages). Le jeton n'ouvre que `/api/`, en lecture ; ailleurs il ne vaut rien. Un exemple de configuration Homepage est affiché sous le formulaire.

**Connexions.** Mon compte liste vos 10 dernières connexions, réussies ou non, avec l'adresse IP et le lieu. Une connexion que vous ne reconnaissez pas : changez votre mot de passe et activez la double authentification. Il n'y a plus de bouton horloge dans la barre de navigation.

**Navigation et consultation.** Les accès sont directs : logo (Prévisionnel), Budget, Épargne, Immobilier, Gérer, Bilan et icône Assistant. À droite : loupe de recherche globale, icône jaune « Qui voit quoi » sans cadre, compte avec son nom, déconnexion. Sur tablette, les pages passent dans le menu burger ; le Bilan, la recherche et le compte restent dans la barre. Sur smartphone, la barre ne garde que le logo, le burger, « Qui voit quoi » et la déconnexion : le burger contient la recherche (les pages de second niveau et les biens apparaissent en tapant), les pages, Mon compte et le thème. La pastille à côté du burger totalise les notifications : alertes du Bilan, changements d'accès et avis à lire. La recherche retrouve les pages et les biens accessibles, quelle que soit la page ouverte, sans tenir compte des accents. Dans Immobilier, les onglets Patrimoine, Mes biens (liste filtrable), Prêts et Taxe foncière sont toujours visibles ; le détail Bilan du patrimoine est tout en bas. Gérer ouvre une page avec les rubriques autorisées à gauche et le sélecteur de foyer ; sur petit écran, ce menu passe au-dessus du contenu. L'administrateur y retrouve Foyers, Utilisateurs et Sécurité, sans accès aux finances. Les listes viennent avant les formulaires : ouvrez « Ajouter… » ou « Importer… » lorsque nécessaire. Une erreur de saisie rouvre le formulaire. Les aides des indicateurs s'ouvrent par appui, ou avec Entrée au clavier. Dans **Budget**, une barre à gauche choisit **Prévisionnel** ou **Moyen**, à côté du choix du compte (Foyer, une personne).

**Bulles de navigation.** Bilan affiche le nombre de points « À corriger » et « À surveiller », hors points ignorés ; la bulle disparaît à zéro. L'Assistant affiche la progression personnelle du guide dans le foyer affiché (étapes faites ou passées). Ces indicateurs se chargent avec JavaScript ; si leur chargement échoue, les pages restent accessibles sans afficher de chiffre incertain.

**Double authentification.** Mon compte → « Activer la double authentification » : scannez le QR code avec une application d'authentification (Aegis, 2FAS, Google Authenticator…), puis tapez le code affiché. Ensuite, la connexion demande ce code après le mot de passe ; cochez « Se souvenir de cet appareil 30 jours » sur vos appareils personnels. Téléphone perdu : demandez à l'administrateur de la retirer ; Patria vous le signalera.

## Commencer avec le Guide de saisie

L'icône étincelles ouvre le Guide de saisie. Son accueil n'a qu'un bouton : **Commencer**, puis **Continuer** sur l'étape où vous en êtes (avec le temps restant estimé) ; « Tout revoir », « Revue annuelle » et « Recommencer à zéro » sont rangés dans **Autres actions**.

1. **Votre situation** (gestionnaire du foyer) : cinq questions en cartes à cliquer. Seul ou en couple avec les prénoms (les personnes du budget sont créées, enfants compris), compte joint, logement (locataire, propriétaire, hébergé), autres biens, épargne. Le guide ne garde ensuite que les étapes qui vous concernent : pas de loyer pour un propriétaire, pas de biens ni de prêts pour un locataire, pas de virements sans compte joint, pas de répartition pour une personne seule. Les réponses se changent en revenant sur l'étape.
2. **Vos documents** : importer le fichier de la banque, la réponse d'une IA, ou choisir « À la main ».
3. Puis, selon la situation : Revenus, Logement, Biens, Prêts, Charges et loyers des biens, Dépenses fixes, Impôts, Virements, Répartition, Épargne et bourse, et le **Récapitulatif**.

Chaque étape affiche en haut **« Étape 3 sur 8 · Revenus · environ 17 min pour finir le guide »**, puis **À faire ici** : la liste de ce qu'il y a à saisir (les revenus de chaque personne, le loyer, le prêt de chaque bien, chaque thème de dépenses…), cochée au fur et à mesure. En bas, un seul bouton : **Continuer : <étape suivante>**. Si rien n'est saisi, l'étape est notée « à compléter » (le guide le dit avant) et le récapitulatif la liste avec un lien direct ; « ← Précédent » reste un lien discret. Un autre compte que le gestionnaire suit un guide réduit à ses propres lignes : documents, revenus, dépenses fixes, impôts, épargne. Le bilan de santé garde son choix de comptes par défaut ; il se change dans le Bilan.

Tant que le guide n'est pas fini, une barre **Guide de saisie · étape x sur y · Continuer** s'affiche en haut des pages ; « × » la masque. La progression est propre au compte ; « Recommencer à zéro » efface la progression et la situation, pas les données. Le guide se fait en deux temps :

1. **Vos documents** : la page demande « Comment voulez-vous remplir Patria ? » et propose trois méthodes, une ouverte à la fois :
   - **Avec le fichier de ma banque** (le plus simple, sans IA) : sur le site de la banque, liste des opérations → « Exporter » ou « Télécharger » → format CSV (ou OFX, QIF), 3 à 12 mois ; déposez le fichier, Patria repère les prélèvements et revenus réguliers ;
     Le fichier peut aussi être **glissé sur n'importe quelle page** de Patria : il part au même import. Le résultat est dit tout de suite (« Relevé lu : 214 opérations. 12 régulières trouvées (…), à valider ») avec un seul bouton **Valider maintenant** vers la première étape à trier ; les opérations déjà importées ou saisies ne reviennent pas. Une banque cherchée dans « Où trouver vos documents ? » est retenue : **une fois par mois**, 30 jours après le dernier import, une barre « Relevé du mois » propose d'importer le nouveau relevé, avec les étapes de cette banque ; « × » la repousse de 30 jours.
   - **Avec une IA** (Le Chat, ChatGPT, Claude ou Gemini) : ouvrez l'IA, « Copier la consigne », collez-la avec vos documents joints, puis collez toute sa réponse dans Patria ;
   - **À la main** : l'étape est validée et le guide passe aux écrans de saisie, en commençant par les revenus de chaque personne.

   En dessous, « Quels documents rassembler, et où les trouver ? » liste les documents utiles et cherche, banque par banque, où les télécharger (avec le tutoriel complet).
2. **Le tri, catégorie par catégorie** : chaque étape (Revenus, Logement, Dépenses fixes, Impôts, Virements, Épargne) commence par ses lignes « À trier », cochées d'avance sauf les doublons possibles, les dépenses résiliées et ce qui est déjà saisi. « Valider la sélection » enregistre ce qui est coché et écarte le reste ; on corrige ensuite un montant dans « Déjà saisies » ou on complète à la main.

Les échéanciers de prêts immobiliers restent à part, à l'étape Prêts (un prompt dédié, rattaché au bien).

1. **Copiez le prompt** dans votre assistant IA avec le document (relevés de tous les comptes, 12 mois si possible). L'IA demande d'abord ce qui manque, puis liste ce qu'elle trouve.
2. **Collez sa réponse** dans le champ prévu (plus besoin de fichier ; les balises de code sont retirées). Un fichier CSV reste accepté.
3. **Cochez ce que vous gardez** dans l'aperçu : seules les lignes cochées sont importées, puis vous revenez à l'étape.
4. **Complétez à la main** : la liste « Déjà saisies » permet de modifier ou supprimer une ligne importée ; la liste à cocher ajoute le reste.

**Attention aux données confiées à une IA commerciale** (ChatGPT, Claude, Gemini, Le Chat…) : elle reçoit tout ce que vous lui envoyez (noms, adresses, IBAN, montants) et peut, selon ses conditions, le conserver ou s'en servir pour s'entraîner. Retirez vous-même les informations sensibles et réglez l'assistant avant d'envoyer : conversation temporaire, entraînement refusé, aucun lien de partage public, mémoire désactivée, conversation effacée après usage ([détail](confidentialite.md#régler-lassistant-ia-avant-denvoyer)), ou préférez un modèle local. Si l'administrateur a installé une **IA locale**, chaque import propose aussi de **déposer le document** (PDF texte, CSV ou texte) : Patria le fait analyser sur votre serveur, sans rien envoyer à l'extérieur, puis affiche directement l'aperçu. Comptez quelques minutes ; un scan sans texte n'est pas lisible.

Le prompt demandant des libellés génériques ne protège pas l'original que vous auriez déjà envoyé. Une conversation temporaire ne garantit pas l'absence de conservation chez le fournisseur ; vérifiez ses conditions, ou utilisez un modèle réellement local sans service distant. [Détails](confidentialite.md).

### Dépenses CSV

Gérer → Dépenses → Importer un fichier CSV. **Aucune ligne d'en-tête** ; ordre des colonnes :

```text
libelle;categorie;montant;frequence;debut;fin
```

Cette ligne décrit le format ; **ne la mettez pas dans le fichier**. Exemple de contenu :

```csv
Mutuelle;Santé;35,69;Mensuel;01/01/2026;
Assurance emprunteur;Crédits;17,68;Mensuel;01/01/2026;05/09/2042
```

- Fin facultative, vide ou absente : sans fin. Dates `31/01/2026` ou `2026-01-31`.
- 7e colonne facultative, **repères** séparés par des virgules : Abonnement, Télécom, Streaming, Ponctuel, Hausse récente, Doublon possible, Résilié, À vérifier, etc. Le prompt IA les fait remplir.
- Catégorie/fréquence : libellés de l'application sans sensibilité aux accents ou majuscules, ou codes (`HEALTH`, `MONTHLY`).
- Montants avec virgule ou point, espaces et symbole euro acceptés.
- Point-virgule recommandé ; virgule possible si les montants n'en contiennent pas. Guillemets autour des libellés acceptés ; maximum 500 lignes.
- Modèle téléchargeable et prompt IA disponibles. La copie automatique du prompt nécessite HTTPS ou localhost ; sinon sélection du texte pour Ctrl+C.

L'aperçu a une case par dépense, et des boutons « Tout », « Rien », par détection (ex. Télécom, Doublon possible) et par catégorie pour garder ou ignorer un groupe d'un clic ; les doublons possibles et les dépenses résiliées sont décochés d'avance. Seules les lignes cochées sont importées. L'aperçu permet aussi de choisir la répartition pour tout le fichier puis ligne par ligne. Une erreur bloque l'import sans rien enregistrer. Une dépense déjà saisie (mêmes libellé, montant, fréquence et début) est ignorée pour éviter les doublons. Chaque ligne enregistrée reste modifiable dans son formulaire.

### Échéanciers de prêts CSV

Dans Prêts et apports d'un bien → Ajouter un prêt, ou Gérer le prêt pour remplacer l'échéancier : CSV UTF-8 (BOM accepté), maximum 1 Mio. **Un import valide remplace toutes les anciennes lignes de ce prêt.**

En-tête canonique :

```csv
date,type,montant,capital_rembourse,interets,frais,capital_restant_du,taux_annuel_pct
```

`date` et `type` obligatoires ; autres colonnes facultatives, au moins une valeur financière par ligne. Types : `DEBLOCAGE`, `ECHEANCE`, `REMBOURSEMENT_ANTICIPE`, `CONSOLIDATION`, `AJUSTEMENT` (casse indifférente). Virgule ou point-virgule, décimales avec point ou virgule.

Les anciens en-têtes `pretId`, `tauxAnnuelPct`, `capitalRembourse`, `autres`, `capitalRestantDu` restent convertis automatiquement. [Trois exemples](examples/) sont fournis. Vérifiez les dates et montants renvoyés par une IA ; ils déterminent les calculs du prêt.

## Immobilier

Un compte avec l'accès Immobilier crée les biens (type, adresse, commune, surface, acquisition, estimations, frais), leurs prêts et apports.

**À qui appartient un bien.** Chaque bien appartient à **un seul foyer**, avec ses prêts, échéanciers, apports, charges et loyers. La part de chaque membre et le payeur de la taxe foncière se règlent dans Gérer → Répartition. Le droit « Tous les biens du foyer » est **tout ou rien** : il ouvre tous les biens de ce foyer en lecture seule ; sans lui, les mensualités et charges restent comptées dans votre budget, mais les fiches ne s'ouvrent pas. Un compte avec l'accès Immobilier dans deux foyers peut déplacer un bien de l'un à l'autre (« Foyer du bien »).

### Valeur et situation des prêts

- Estimations prudente et optimiste, actualisées selon l'indice mensuel DVF départemental du même type de bien depuis la date d'estimation.
- Curseur de valeur retenue : prudent, prix du marché (prix médian du m² de la commune × surface, quand il est connu), fourchette, optimiste ou valeur libre, enregistrée directement ; retour au prudent si les estimations changent.
- Vue d'ensemble : somme des valeurs retenues, capital restant dû, valeur nette indicative, plus-value latente et prochaine mensualité.
- Valeur nette indicative = valeur estimée − frais de vente/fiscalité renseignés − capital restant dû. Les charges récurrentes annuelles ne sont pas déduites de cette valeur.
- Taux actuel, capital restant dû et prochaine échéance calculés à la date du jour depuis l'échéancier. Un prêt futur compte pour la totalité du capital emprunté.
- Intérêts payés et total des intérêts du prêt, hors assurance et frais, sur les fiches et tableaux ; apports personnels par bien.

### Charges, occupation, rentabilité

Saisissez les charges par année (taxe foncière, habitation, copropriété, assurance, autres). Une année suffit : taxe foncière revalorisée selon le coefficient légal, autres charges constantes pour les années absentes. Les coefficients doivent être [maintenus chaque année](developpement.md#maintenance-des-coefficients).

Définissez les périodes de location (loyer perçu) ou de résidence principale (loyer de référence économisé). Les jours sans période sont vacants ; les périodes ne peuvent pas se chevaucher. Le rendement brut ne retient que les loyers perçus.

La rentabilité depuis l'achat inclut le coût du crédit payé (intérêts/frais des échéances passées), les charges et les loyers perçus/économisés. Enrichissement réel = valeur après frais de vente − coût d'acquisition − coût du crédit − charges + loyers perçus et économisés. Le multiple compare la valeur nette à l'argent sorti de votre poche. Ces repères dépendent des données saisies et ne calculent pas une fiscalité personnelle complète.

Modifier ou supprimer un bien, un prêt ou un apport se fait dans ses pages de gestion. La suppression d'un bien supprime aussi les données liées. Un compte avec l'accès Immobilier dans deux foyers peut déplacer un bien : prêts, échéanciers, apports, charges, occupation et marché suivent ; la répartition repart à parts égales.

### Prix au m² et ventes du secteur

Comparaison du prix au m² du bien et du coût d'acquisition au marché : DVF géolocalisées DGFiP/Etalab et statistiques mensuelles, API Adresse pour situer le bien, sans clé API.

- Même type, surface comparable (±40 %), 3 dernières années publiées.
- Rayon 500 m, puis 1 km, 2 km, puis commune tant qu'il manque 30 ventes ; uniquement les ventes de la commune ou de l'arrondissement à Paris, Lyon, Marseille.
- Actualisation départementale et exclusion des extrêmes ; médiane et quartiles, cache 30 jours, recalcul après changement adresse/type/surface, bouton Actualiser.
- Carte de synthèse avec répartition et 5 ventes comparables ; page détaillée avec filtres période/surface/distance, tris, plan local sans fond tiers, liens Google Maps/carte DVF sur clic et synthèse PDF.

Calcul en arrière-plan à l'ouverture de la fiche. Absence de DVF en Alsace-Moselle (57, 67, 68) et à Mayotte. Ce sont des comparables indicatifs, pas une expertise. Sans accès réseau ou en mode hors ligne, le reste de Patria fonctionne, mais le marché n'est plus actualisé.

### Taux du marché et renégociation

Comparaison à la moyenne BCE/Banque de France des nouveaux prêts français à taux fixe sur plus de 10 ans, taux nominal hors assurance ; donnée actualisée quotidiennement, publication décalée d'environ deux mois.

Sur la fiche du prêt : mensualité recalculée au taux du marché à capital/durée restants identiques, économie d'intérêts, puis économie après indemnité au plafond légal (6 mois d'intérêts, au plus 3 % du capital restant dû). À partir de 0,7 point d'écart avec économie nette, demander une simulation à sa banque peut être utile.

Limites : moyenne nationale regroupant toutes les durées de plus de 10 ans (précision d'environ 0,3 point), comparaison indicative s'il reste moins de 10 ans ; assurance, garantie et frais de dossier exclus. Ce n'est pas une offre bancaire.

## Patrimoine

La page **Patrimoine** montre ce que vous possédez (comptes et livrets, épargne long terme, bourse et cryptos, biens à leur valeur retenue), ce que vous devez (capital restant dû des prêts immobiliers) et le **patrimoine net = ce que vous possédez − ce que vous devez** : ce qui resterait si toutes les dettes étaient remboursées aujourd'hui, sans rien vendre. Les biens y sont comptés avant frais de vente et impôts (la « valeur nette » d'Immobilier → Synthèse les déduit, elle est donc plus basse). Les crédits à la consommation n'y figurent pas : seule leur mensualité est connue. L'immobilier n'apparaît que pour qui voit l'immobilier.

## Affichage et mouvement

- **Chiffres** : montants arrondis à l'euro dans les tuiles, résumés, phrases et graphiques ; centimes seulement dans les listes de lignes et les formulaires. Pourcentages à une décimale au plus.
- **Mouvement sobre** : passage d'une page à l'autre en fondu (l'en-tête reste en place), dépliants et menus qui s'ouvrent en douceur, chiffres clés qui défilent jusqu'à leur valeur, courbes qui se dessinent, barres qui se remplissent, cartes qui apparaissent en défilant, reflet pendant le chargement d'un graphique. Tout est coupé si l'appareil demande moins d'animations (réglage d'accessibilité du système).
- **Sur smartphone**, les tableaux deviennent des fiches : une fiche par ligne, chaque valeur avec son intitulé.
- **Intitulés harmonisés** : chaque page porte le nom de sa rubrique (Budget, Patrimoine, Économies, Bilan, Gérer, Guide de saisie). La page Bourse n'a plus qu'un graphique, « Comparer les performances », qui montre aussi le portefeuille.

## Économies

Le menu a cinq entrées : **Budget**, **Patrimoine** (onglets Vue d'ensemble, Épargne, Bourse, Immobilier), **Économies**, **Bilan** et **Gérer**, plus l'icône du Guide de saisie. Le bouton **+ Ajouter** de l'en-tête ouvre toutes les saisies : relevé bancaire, dépense, revenu, livret, titre en bourse, bien, objectif d'épargne.

La page **Économies** rassemble :

- **Votre niveau** (de « Premiers pas » à « Maître des finances ») et vos **points** : chaque chose utile faite en rapporte (budget complet, relevé importé, contrat renégocié, objectif atteint, mois sans déficit, badge, mission réussie). Une pastille dans l'en-tête montre le niveau et l'avancement ; un niveau gagné est célébré (message et confettis discrets, coupés si l'appareil demande moins d'animations).
- **Missions** en plusieurs étapes avec leur récompense : Mettre de l'ordre, Chasse aux contrats, Bouclier (1, 3 puis 6 mois de précaution), Un projet. **Séries** : mois d'affilée avec un relevé importé, sans déficit, avec une épargne en hausse ; une série cassée repart de zéro sans retirer de points, et le mois en cours ne la casse pas.
- **Votre santé financière sur 100** (À construire, Correcte, Solide, Excellente) : 40 points pour le bilan de santé, 30 pour un budget complet, 30 pour l'épargne de précaution (6 mois = tout). Aucune comparaison avec d'autres foyers.
- **Économies réalisées** (contrats renégociés, sur un an) et **À gagner** (écart entre vos contrats et des offres courantes).
- **Le défi du mois** : une seule chose à faire, la plus utile (renégocier le contrat au plus gros gain, éviter un mois en déficit, constituer 3 mois de précaution).
- **Vos contrats**, reconnus dans vos dépenses : électricité, gaz, eau, box internet, forfait mobile, assurance habitation, assurance auto, mutuelle (selon votre âge, d'après la date de naissance de Mon compte), frais bancaires, abonnements, et l'**assurance de prêt** de chaque prêt immobilier. Chacun est comparé à un repère daté et sourcé (tarif réglementé de l'électricité, Arcep, Observatoire des tarifs bancaires, études 2026), avec le gain possible par an, prudent, et la démarche pas à pas (comparateur officiel énergie-info.fr, loi Hamon, loi Lemoine, portabilité du numéro, mobilité bancaire). « J'ai renégocié » enregistre le nouveau montant : le budget le prend à partir du mois suivant (une ligne seule) et l'économie rejoint le compteur. Les contrats où un gain est possible apparaissent aussi dans le Bilan (« Bon à savoir »).
- **Objectifs d'épargne** illustrés (vacances, voiture, travaux…), qui se remplissent avec le solde du livret choisi.
- **Badges** : budget en place, sans mois difficile, précaution, première économie, 100 € par mois économisés, objectif atteint, santé excellente. Un badge ou une mission obtenu depuis votre dernière visite est célébré une fois.

Les repères sont indicatifs et revus à chaque version ; rien n'est envoyé à un comparateur.

## Épargne et Bourse

**Comparer les performances (Bourse).** « Comparer à » met votre portefeuille face au MSCI World, au S&P 500, au CAC 40, à l'Euro Stoxx 50, ou au Livret A et à l'inflation, sur 1 mois, 1 an ou 5 ans. Une phrase donne le résultat (« Sur 1 an, votre portefeuille fait −11,5 % ; MSCI World fait +16,6 %. Moins bien de 28,2 points. »), puis le graphique montre l'évolution en % depuis le début de la période (0 % marqué, graduations rondes), le portefeuille en trait épais ; au survol, toutes les valeurs de la date. Indices hors dividendes ; portefeuille à sa composition actuelle ; Livret A et inflation à leur taux actuel, en ordre de grandeur.

**Inflation et intérêts.** La tuile **Vos intérêts** donne le taux moyen de votre épargne (pondéré par les soldes) et ce qu'il reste après inflation : en rouge, l'épargne perd du pouvoir d'achat. Le graphique **Inflation et taux de l'épargne** compare, mois par mois sur deux ans, l'inflation INSEE au Livret A, au LEP (depuis leur dernière date vérifiée) et à votre taux moyen. Le pouvoir d'achat de vos revenus est passé dans le Bilan, rubrique Finances.

**Revenu fiscal de référence.** À saisir à l'étape Impôts du guide, sur la page Épargne (« Revenu fiscal de référence ») ou par la ligne `FISCAL;revenu;année;parts` de la réponse de l'IA. Avec le nombre de parts, Patria en déduit seul l'éligibilité au LEP (plafond d'une part, plus un montant par demi-part) : plus besoin de répondre à la question « Éligible au LEP ? ». Le PEL n'a pas de condition de revenu.

L'**Épargne** (comptes, livrets, assurance-vie, PER, PEL) et la **Bourse** (PEA, comptes-titres, cryptos) ont chacune leur page : l'une est disponible ou garantie, l'autre peut perdre de la valeur.

Sur la page **Bourse**, deux boutons suffisent :

- **Importer un relevé** : choisissez le courtier (les étapes pour obtenir l'export sont rappelées), le compte (existant, ou nouveau : type PEA ou compte-titres, nom, titulaire) et déposez le fichier tel quel. Patria reconnaît les colonnes, cumule achats et ventes et montre un aperçu ; un titre déjà présent est mis à jour, l'ISIN est converti en cotation en euros quand elle existe. Avec l'export complet de **Trade Republic** (Profil → Relevés de compte → Export des transactions), les **espèces** du compte sont aussi calculées (versements − achats + ventes, dividendes, frais et impôts). Un PDF passe par le prompt IA, en second choix.
- **Ajouter un titre** : cherchez-le (nom, ISIN ou code : « MSCI World », `FR0010315770`, `BTC`), choisissez-le, puis indiquez la quantité et le **prix moyen par part** (ou le total payé) et le compte. Un nouvel achat d'un titre déjà détenu s'ajoute à sa ligne.

Aucun solde à saisir : un compte de bourse naît avec 0 € d'**espèces** (l'argent non investi), modifiable dans son menu Gérer. Chaque titre se modifie (quantité, prix moyen) ou se supprime depuis son menu Gérer. Tuiles : valeur, plus-value latente, montant investi, espèces. La **courbe du portefeuille** (1 mois, 1 an, 5 ans) valorise les titres actuels aux cours passés ; chaque titre se déplie sur la **courbe de son cours**, avec le prix moyen en pointillé. Source : Yahoo Finance, en différé.


Menu **Épargne** : les placements de chaque membre (compte courant, Livret A, LDDS, LEP, livret jeune, CEL, PEL, assurance-vie, PER, PEA, compte-titres). Chaque placement appartient à un membre : il est visible et modifiable par les comptes qui ont accès à ce membre. Saisissez le solde, puis « Mettre à jour » quand il change.

- **Taux** : ceux des livrets réglementés sont fournis par Patria, datés (source service-public.gouv.fr ; aucune API publique ne les donne livret par livret). Pour une assurance-vie ou un livret bancaire, saisissez le taux.
- **Bourse** : sur un PEA ou un compte-titres, ajoutez des lignes avec leur code Yahoo Finance (ex. `AI.PA`, `CW8.PA`), la quantité et le prix de revient, ou **déposez l'export CSV** de votre courtier ou plateforme crypto tel quel (Trade Republic, Revolut, Degiro, Boursorama, Binance, Coinbase…) : Patria reconnaît les colonnes, cumule achats et ventes, et affiche un aperçu ; une ligne déjà présente est **mise à jour** (quantité et prix de revient), donc il suffit de redéposer un export récent. Les **cryptos** sont des lignes comme les autres (code `BTC`, `ETH`… coté en euros `BTC-EUR`). Pour un PDF ou un format non reconnu, le prompt IA reste disponible : lignes `code;nom;quantité;prix de revient total`, l'ISIN étant converti en code de cotation. Un montant exporté dans une autre devise (dollars chez Revolut) est repris tel quel comme prix de revient : corrigez-le si besoin. Le cours du jour est récupéré automatiquement ; une valeur cotée en dollars ou en livres est convertie en euros.
- **Taux** : pré-rempli avec le taux officiel quand vous choisissez un livret réglementé.
- **Versement programmé** : sur un placement, un montant et une fréquence font avancer le solde tout seul (plafond des livrets respecté) ; le versement apparaît dans le budget (Virements), donc dans le reste à vivre, sans gonfler l'objectif de précaution.
- **Historique** : graphique en barres empilées des 24 derniers mois (disponible, bourse, long terme), rempli à chaque mise à jour et chaque mois.
- **Tuiles** : épargne totale, épargne de précaution (en mois de dépenses), inflation sur 12 mois (INSEE) et **pouvoir d'achat** : l'évolution de vos revenus sur un an corrigée de l'inflation (sans revenus connus il y a un an, à revenus constants face à l'inflation des 12 derniers mois).
- **Plan d'épargne de sécurité** : objectif de précaution (6 mois de dépenses par défaut, réglable), ce qui manque, un versement mensuel conseillé (combler en un an sans dépasser la moitié du reste mensuel) et l'ordre de remplissage : LEP si vous y êtes éligible, Livret A, LDDS, puis le long terme. Indicatif, pas un conseil en investissement.

Le **Bilan** audite l'épargne à chaque ouverture : précaution réelle, argent qui dort sur les comptes courants, plafonds atteints, LEP possible, livrets sous l'inflation, concentration sur une seule valeur, plus ou moins-values, PEA de moins de 5 ans, changement récent d'un taux, soldes de plus de 6 mois, pouvoir d'achat. En mode hors ligne, cours et inflation ne sont pas récupérés.

## Historique et évolution

Patria garde l'historique, sans détail et dans la base chiffrée du foyer :

- **Montants « à partir du »** : en modifiant un revenu ou une dépense (Gérer), « Nouveau montant valable à partir du » est prérempli au 1er du mois prochain ; l'ancien montant reste avant cette date. Videz le champ pour corriger une simple erreur de saisie.
- **Un relevé par mois** du foyer (revenus, dépenses, épargne, bourse, immobilier, dettes, score), enregistré à la première visite du mois. Les 36 mois passés sont reconstitués une fois à partir des lignes datées, des soldes d'épargne, des échéanciers et des estimations (marqués « reconstitués » ; la bourse n'y compte que ses espèces).
- **L'indice des prix INSEE** gardé sur place (depuis 2014), pour corriger les revenus de l'inflation même hors ligne.
- **Le revenu fiscal de référence année par année.**
- **Les dépenses réelles** des relevés bancaires importés : totaux par mois et par catégorie seulement ; une opération déjà comptée (même relevé importé deux fois) ne l'est pas deux fois. Aucun libellé n'est gardé.
- **Les achats et ventes de bourse** des relevés de courtier : la Bourse affiche la **performance réelle** (valeur des titres moins le versé net, achats − ventes ; dividendes non comptés).
- **Les estimations successives de chaque bien** (fiche du bien → Historique des estimations).

En bas du **Bilan**, **Votre évolution** montre : le **pouvoir d'achat** (revenus réguliers en euros courants et en euros d'aujourd'hui, avec la phrase « Depuis janvier 2021 : revenu mensuel … → …, prix … Pouvoir d'achat : … »), le **patrimoine net**, le **reste à vivre**, les **dépenses prévues face aux réelles**, la **santé financière**, et le **revenu fiscal de référence** par avis. Patrimoine, reste à vivre, prévu/réel et santé financière ne s'affichent qu'à qui voit tout le foyer ; le pouvoir d'achat porte sur les revenus que vous voyez.

## Revue annuelle et bilan de santé

Le bilan porte par défaut sur votre compte personnel et les comptes joints que vous voyez. Changez les comptes pris en compte dans l'encart **Comptes pris en compte** du bilan. Il signale les grosses sommes ponctuelles (impôts, assurance…) à mensualiser, le mois le plus serré, les virements qui ne couvrent pas un compte joint et les abonnements en double. Cliquez sur une tuile pour aller à la liste correspondante.

Le Prévisionnel présente d'abord les chiffres du mois, puis les alertes et la barre du guide. Le bilan regroupe d'abord tous les sujets à corriger, puis ceux à surveiller, quel que soit leur domaine ; les points rassurants sont repliables.

Dans le bilan, **Ignorer pour moi** retire un avertissement ou un conseil des compteurs et du résumé du Prévisionnel, sans modifier les finances. Le choix est conservé pour votre compte et ce foyer, sans affecter les autres utilisateurs. Ouvrez **Points ignorés**, puis **Rétablir** pour le remettre. Une alerte dont le niveau de gravité augmente réapparaît. Les points ignorés restent indiqués séparément dans la synthèse PDF : ils ne sont pas présentés comme résolus. Si un problème disparaît, il n'est plus listé ; votre choix reste mémorisé pour son retour au même niveau.

Guide de saisie → Revue annuelle : pour chaque revenu/dépense récurrente, nouveau montant et date d'effet, ou arrêt. L'ancien montant s'arrête la veille et la répartition est reprise. Avec l'accès Immobilier : taxe foncière de l'année et estimation ancienne des biens. Calendrier des mises à jour et rappel sur le Prévisionnel après un an.

L'icône cœur ouvre le bilan : reste à vivre, déficit, endettement, épargne de précaution (3 à 6 mois), dépenses fixes, abonnements et salaires anciens ; estimations, occupation, rendement, dette et taux des prêts ; prélèvement à la source, foncier, loyers à déclarer et repère IFI. Chaque signal explique le problème et propose un lien ; il respecte les vues ouvertes à votre compte. Ce diagnostic est indicatif, pas un conseil fiscal ou financier.

## Affichage

Thème **clair** ou **sombre** : par défaut celui du système ; le bouton demi-cercle de l'en-tête passe de système à clair, puis sombre. Le choix est gardé sur l'appareil.

## Synthèses PDF

Exporter en PDF produit une synthèse A4 de la vue et des droits affichés : Prévisionnel (mois et 12 mois), Budget moyen (catégories et lignes), Immobilier (valeurs, enrichissement, prêts/taxes), bien (bilan, comparables), prêt (années et échéancier), bilan de santé et ventes du secteur. La génération se fait sur votre serveur, sans service externe.

Les PDF téléchargés contiennent des données financières : protégez-les et vérifiez leur contenu avant partage. L'import/export global JSON n'est pas disponible à ce jour.
