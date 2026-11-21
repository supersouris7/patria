# Patria — règles du projet

> Complète le socle commun de la fabrique (`~/.claude/CLAUDE.md`, `~/.claude/fabrique/standards/`),
> sans jamais l'affaiblir en sécurité. Point de reprise : `docs/projet/ETAT.md`.

## Produit
- Vision : `docs/projet/VISION.md`. Spécifications : `README.md`, `ARCHITECTURE.md`, `docs/` (utilisation, administration, confidentialité, développement).
- Décisions d'architecture : `docs/adr/`.

## Pile et commandes de contrôle
- Java / Maven
  - `mvn -o -q compile`
  - `mvn -o test`
- Python
- Docker (image analysée par Trivy en CI si configuré)
- CI GitHub Actions : docker.yml 

## Stratégie de tests
- JUnit (`mvn -o test`)
- `python3 -m unittest discover -s tests`

## Environnements
- Test (déploiement autorisé aux agents, sauvegarde avant mise à jour) : instance auto-hébergée du client ;
  détails et procédure dans `CLAUDE.local.md` (local, non versionné : dépôt public).
- Production : uniquement avec accord explicite du client.

## Suivi
- Dépôt : GitHub supersouris7/patria · Tableau : à créer (gh-projet.sh) · Branche principale : main
- Branches : `feat|fix|chore/<n° ticket>-<slug>` ; une PR par ticket, `Closes #n`.

## Règles spécifiques
- Dépôt **public** : aucune donnée personnelle, hôte, IP ou secret (même fictif réaliste) dans le dépôt ;
  captures et exemples avec des données fictives uniquement.
- Données **financières des foyers** : sensibilité élevée. Chiffrement de la base, isolation par foyer,
  droits de partage vérifiés côté serveur ; toute PR touchant stockage, import ou partage passe par `securite`.
- Aucune connexion aux comptes bancaires (principe du produit) ; pas d'envoi de données à des tiers.
- Graphify : si `graphify-out/graph.json` existe localement, `graphify query` avant toute recherche.
- Faux positifs de sécurité examinés : `docs/projet/faux-positifs-semgrep.txt`.
