# État du projet — Patria

> Point de reprise de toute session. Court, daté, mis à jour en fin d'intervention.
> Le détail opérationnel est dans les tickets GitHub ; ici, seulement la synthèse.

- Mis à jour : 2026-11-21
- Maturité : mature — Java/Maven, tests JUnit, CI Docker (Trivy), 5 versions étiquetées.
- Environnement de test : à définir avec le client (l'instance auto-hébergée existante est traitée comme
  production : aucune action sans accord).
- Tableau de suivi : à créer (droit GitHub « project » en attente).

## En cours
- Intégration à la fabrique logicielle (PR `chore/fabrique-integration`).
- 8 PR Dependabot ouvertes, dont des versions majeures (JUnit 6, OpenPDF 3, JDK 22/26) à évaluer.

## Anomalies connues
- Aucune bloquante. Contrôle sécurité du 21/11 : 0 critique/haute ; 3 faux positifs documentés.

## Prochaine action
- Trier les PR Dependabot (mineures d'abord), définir l'environnement de test.
