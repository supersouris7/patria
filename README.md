# Patria — suivi immobilier personnel

Application web en français, auto-hébergée, pour suivre plusieurs biens et prêts. **Périmètre : immobilier uniquement** ; aucune connexion bancaire ni boursière. Base SQLite dans un volume Docker, authentification par mot de passe, sauvegarde JSON.

## Démarrage

Docker et Docker Compose sont nécessaires. Dans ce dossier :

```bash
cp .env.example .env
python3 -c 'import secrets; print(secrets.token_urlsafe(48))'  # reporter la valeur dans SECRET_KEY de .env
# Remplacer également APP_PASSWORD par un mot de passe personnel long dans .env
docker compose up -d --build
```

Ouvrir **http://127.0.0.1:18080** (ou le port `PORT` choisi dans `.env`). Le service écoute seulement en local par défaut ; pour un autre appareil, installer un proxy HTTPS authentifié ou modifier explicitement la liaison `ports` dans `compose.yaml`. Pour arrêter : `docker compose down` (conserve le volume). Pour les journaux : `docker compose logs -f patrimoine`. Pour les tests : `docker compose run --rm patrimoine python -m pytest -q -p no:cacheprovider`.

Les variables `APP_PASSWORD` et `SECRET_KEY` sont obligatoires ; l'application refuse de servir les pages sans elles. Un hash Werkzeug `scrypt:` ou `pbkdf2:` peut remplacer le mot de passe en clair dans `.env`. Ne partagez pas `.env`, le volume ni les sauvegardes JSON.

## Utilisation

1. Dans **Administration**, ajouter un bien : type maison/appartement, commune, **code INSEE**, surface, prix et date d'achat, estimation personnelle et date.
2. Ajouter autant de prêts que nécessaire, chacun associé à un bien. La mensualité saisie est **hors assurance** ; l'assurance est ajoutée séparément à l'échéancier.
3. Saisir un capital restant dû **de référence**, tiré d'un relevé bancaire, avec sa date. L'application projette ensuite les échéances mensuelles (capital + intérêts + assurance) et considère les échéances datées passées comme payées. Si la réalité diffère, ajouter une **correction du capital** avec la date du relevé. Un remboursement anticipé réduit le solde à sa date.
4. Le dashboard présente la valeur nette = **somme des estimations − somme des capitaux restants**. Le capital remboursé est affiché à part. Le tableau d'amortissement et les événements se trouvent dans Administration.
5. Pour une estimation DVF, saisir d'abord une estimation personnelle **datée sur une année antérieure** (au plus quatre ans avant le dernier millésime complet), puis cliquer sur « Calculer une proposition DVF ». Une page détaille les ventes retenues et demande confirmation ; sinon l'estimation personnelle est conservée.
6. Exporter les données via **Télécharger la sauvegarde**. L'import JSON remplace atomiquement les données existantes. Conserver des copies du volume et du JSON hors du serveur.

## DVF : méthode et limites

Source : [DVF géolocalisées, fichiers CSV par commune et millésime](https://files.data.gouv.fr/geo-dvf/latest/csv/) publiés sur `files.data.gouv.fr`, à partir des ventes publiques DGFiP. Exemple d'un [fichier communal 2025](https://files.data.gouv.fr/geo-dvf/latest/csv/2025/communes/75/75101.csv). Les fichiers ne sont pas disponibles pour toutes les communes et années (notamment départements 57, 67, 68, Mayotte). Le service télécharge le fichier communal de l'année de l'estimation et celui du **dernier millésime annuel complet** lors de la demande, côté serveur. Échec réseau, millésime absent, moins de **cinq** transactions comparables dans chaque millésime ou dernière vente de plus de deux ans : **aucune proposition**.

Une transaction n'entre dans la médiane €/m² que si elle concerne exactement **un logement** du bon type, sans local commercial, en vente simple ; les dépendances sont tolérées et les mutations multilots résidentiels écartées. Bornes de surface et de prix filtrent certaines valeurs aberrantes. Proposition = estimation personnelle × (médiane €/m² du dernier millésime / médiane du millésime de l'estimation). Les deux effectifs, médianes, dates de dernière vente et la méthode sont affichés avant validation. Cette méthode ne prend pas en compte l'état du bien, sa rue, la composition des dépendances ni les ventes postérieures à la dernière publication : **ce n'est pas une estimation en temps réel**. Une nouvelle estimation personnelle reste toujours possible.

## Calculs et persistance

L'échéancier commence **après** la date du solde de référence : les échéances antérieures ne sont pas recalculées. Taux mensuel indicatif = taux annuel / 12 ; intérêts = solde × taux mensuel ; capital remboursé = mensualité hors assurance − intérêts, limité au solde. Jour 29/30/31 ramené au dernier jour du mois si nécessaire. Il s'agit d'une simulation à date, pas de la confirmation des prélèvements bancaires, et elle n'intègre pas les clauses contractuelles de modulation ou intérêts intercalaires. Le capital remboursé affiché est capital initial − capital restant estimé, y compris remboursements anticipés et soldes antérieurs au premier relevé saisi.

Les données sont dans `/data/immobilier.sqlite3` (volume Docker nommé `patrimoine_data`). Les instantanés quotidiens sont écrits lors des visites et des changements ; aucune valeur intermédiaire n'est inventée pour les jours sans visite. Les sauvegardes JSON contiennent tous les biens, prêts, événements et instantanés.
