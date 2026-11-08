# Confidentialité et sécurité

[Présentation](../README.md) · [Utiliser](utilisation.md) · [Administrer](administration.md)

## Ce qui reste sur le serveur

Membres, revenus, dépenses, virements, biens, prêts et charges sont stockés en SQLite sur le serveur choisi : une base par foyer, plus `comptes.sqlite3` pour comptes, droits et journaux. Aucun traceur, statistique d'usage, publicité ni contenu tiers embarqué dans les pages. Les PDF sont produits localement sur le serveur.

Un cookie de session, supprimé à la déconnexion ou expirant après 12 heures ; et, si vous cochez « Se souvenir de cet appareil » à la double authentification, un jeton d'appareil gardé 30 jours (la base n'en garde que l'empreinte SHA-256). Les clés de double authentification (TOTP) restent dans `comptes.sqlite3`. Mots de passe utilisateurs **hachés avec bcrypt**, pas chiffrés de manière réversible. Le secret administrateur vient de la configuration (clair ou empreinte `{bcrypt}…`).

## Qui peut consulter

- **Utilisateur** : seulement ce que l'administrateur lui coche dans le tableau des droits (membres, totaux du foyer, immobilier), pas les autres foyers sans accès ; lignes partagées avec un membre non coché en lecture seule.
- **Compte administrateur** : comptes, noms des membres, droits, avancement de configuration sans montants et sécurité ; aucune finance dans l'interface avec ce compte. Il peut cependant créer un compte et lui ouvrir des droits.
- **Exploitant du serveur** : accès technique aux fichiers, mémoire et clé ; il peut lire les données ou modifier le programme et les journaux. L'isolation applicative n'est pas une protection contre lui.

« Qui voit quoi » expose les accès et leurs changements aux comptes du foyer ; le journal des droits en garde la trace. Les mots de passe provisoires sont changés avant toute autre page. Ces mécanismes améliorent la transparence, sans constituer un journal inviolable ni une garantie contre l'hébergeur.

## Appels extérieurs

| Destinataire | Données envoyées | Déclenchement |
|---|---|---|
| API Adresse (`api-adresse.data.gouv.fr`) | Adresse ou commune d'un bien | Géolocalisation pour le marché |
| DVF (`files.data.gouv.fr`, `tabular-api.data.gouv.fr`) | Codes commune/département | Calcul des comparables et indices ; résultat gardé 30 jours, actualisation possible |
| BCE (`data-api.ecb.europa.eu`) | Requête de taux sans chiffres personnels | Actualisation quotidienne du taux moyen |
| INSEE (`api.insee.fr`) | Rien de personnel | Inflation sur 12 mois (indice des prix, série 011814630), une fois par jour |
| Yahoo Finance (`query1.finance.yahoo.com`) | Code d'une valeur de bourse (ex. `AI.PA`), jamais la quantité ni le montant | Cours du jour des lignes saisies, au plus une fois par heure |
| `ipwho.is` | IP publique d'une connexion, pas l'identifiant | Localisation pays/ville du journal de sécurité, une fois par IP, cache local |
| Google Maps / carte DVF | Coordonnées associées au lien et données de connexion du navigateur | Seulement sur clic |
| Ko-fi | Données de connexion du navigateur | Seulement sur clic sur Soutenir, si le lien est configuré |

Tout appel réseau révèle aussi l'IP du serveur à son destinataire, et les clics externes celle du navigateur. Patria n'envoie ni nom de membre, ni revenu, dépense ou montant de prêt à ces services. Les journaux d'erreurs du marché n'incluent pas l'adresse du bien dans les paramètres d'URL.

`OFFLINE=true` coupe les appels **automatiques** au marché, aux taux et à la localisation des IP ; plus de mise à jour du marché ou des estimations par l'indice. Cela ne bloque pas les liens externes sur lesquels vous cliquez ni vos propres envois à une IA. Les connexions restent enregistrées localement pendant 90 jours.

## IA

L'IA est facultative : les CSV peuvent être préparés manuellement. Patria ne transmet pas vos documents à une IA. Si vous en utilisez une, ses conditions de conservation et d'entraînement s'appliquent ; une conversation temporaire ne garantit pas l'absence de conservation.

**Attention aux données confiées à une IA commerciale** : elle reçoit tout ce que vous lui envoyez (noms, adresses, IBAN, numéros de compte, montants) et, selon ses conditions, peut les conserver ou s'en servir pour s'entraîner. Retirez vous-même les informations sensibles, désactivez l'historique et l'entraînement, ou préférez un modèle local. Patria rappelle cet avertissement à côté de chaque prompt. Avec une **IA locale** (Ollama installé par l'administrateur à côté de Patria), le document est analysé sur votre serveur : rien ne sort de l'installation. Un prompt qui demande des libellés génériques ne retire rien du document transmis.

### Régler l'assistant IA avant d'envoyer

Les noms des menus changent souvent ; cherchez l'équivalent dans votre assistant.

1. **Conversation temporaire** : « Discussion temporaire » (ChatGPT, Gemini), conversation « incognito » (Claude). Elle n'apparaît pas dans l'historique et, selon le service, n'entraîne pas le modèle.
2. **Entraînement refusé** : ChatGPT → Paramètres → Contrôle des données → « Améliorer le modèle pour tout le monde » désactivé ; Claude → Paramètres → Confidentialité → « Aider à améliorer Claude » désactivé ; Gemini → « Activité dans les applications Gemini » désactivée ; Le Chat → paramètres de confidentialité.
3. **Jamais de partage public** : pas de lien « Partager » ni d'option « rendre visible / découvrable » ; des conversations partagées ont déjà été indexées par des moteurs de recherche. Vérifiez et supprimez vos liens existants (ChatGPT → Contrôle des données → Liens partagés).
4. **Mémoire désactivée** (ChatGPT → Personnalisation → Mémoire ; équivalents ailleurs) : l'assistant ne retient pas votre situation financière.
5. **Effacer après usage** : supprimez la conversation et les fichiers joints une fois les lignes copiées dans Patria ; le service peut garder une copie quelque temps (souvent jusqu'à 30 jours).
6. **Envoyer le moins possible** : masquez noms, adresse, IBAN et numéros ; une partie du relevé suffit souvent.

Le plus sûr reste une IA locale (voir le guide d'administration) : rien ne sort.

## Protections à configurer

- **HTTPS** protège le trajet navigateur–serveur, pas les fichiers du disque. Nécessaire pour un accès distant ; l'avertissement HTTP ne sécurise pas la connexion.
- **`DB_ENCRYPTION_KEY`** protège les bases, copies avant migration et foyers archivés au repos. **Sans clé au premier lancement, les bases sont en clair.** Activation ultérieure possible ; anciennes sauvegardes externes non converties automatiquement.
- **Clé perdue** : les bases chiffrées deviennent irrécupérables. **Clé momentanément absente** : remettre la clé d'origine suffit ; ne pas en générer une autre. Le mot de passe de connexion est indépendant.
- **Sauvegardes externes** : nécessaires même avec le chiffrement. Les copies avant migration sur le même disque ne protègent pas contre sa perte. Conservez la clé séparément et testez la restauration.

[Installation, chiffrement et restauration](administration.md).

## Conservation et suppression

Supprimer une ligne la retire de la base active, sans effacement physique garanti des journaux SQLite, anciens blocs ou sauvegardes. Un foyer supprimé est d'abord archivé sur le disque ; l'administrateur peut ensuite l'effacer dans Foyers supprimés. Supprimer un compte ne supprime pas les finances du foyer.

Les 3 dernières copies avant migration de chaque base sont conservées. Les sauvegardes externes, documents originaux, CSV et PDF téléchargés restent sous votre responsabilité : chiffrement, accès et durée de rétention adaptés à leur sensibilité.
