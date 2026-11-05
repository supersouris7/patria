# PDF.js 6.5.438

Distribution officielle `pdfjs-dist`, licence Apache-2.0 (voir LICENSE).
Source : https://github.com/mozilla/pdf.js
Archive : https://registry.npmjs.org/pdfjs-dist/-/pdfjs-dist-6.5.438.tgz
Intégrité SHA-512 de l'archive (base64) :
`vg8Pw1x+AFKy4AMDvhuWl5cOYV8d698UoEANoFFhc79hqXDnZQqnOXoxw3HG907WHOg1JYOvLhP756boddFxpA==`

Seuls le moteur et son worker, les polices, CMaps et décodeurs WASM sont embarqués.
Les licences des polices et décodeurs sont conservées dans leurs dossiers.
`files.txt` liste les fichiers servis par `ui/Assets`, sans CDN ni requête vers Mozilla.
L'outil `/aide/caviardage` charge le moteur uniquement quand on l'ouvre.
Pour mettre à jour : vérifier l'intégrité de l'archive officielle, remplacer ces fichiers et régénérer
`files.txt` (chemins relatifs, un par ligne). Relancer les tests navigateur de `tests/redaction_browser.py`.
