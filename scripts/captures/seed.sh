#!/bin/bash
# Foyer fictif pour les captures de la documentation.
set -e
SC=${WORK:?}; B=${BASE:-http://localhost:8096}; CA=$SC/admin.cookies; CM=$SC/claire.cookies; SCRIPTS=$(dirname "$0")
tok() { curl -s -b "$1" $B/compte | grep -o 'name="csrf" value="[^"]*"' | head -1 | sed 's/.*value="//;s/"//'; }
p() { local code; code=$(curl -s -b $CM -o /dev/null -w "%{http_code}" --data-urlencode "csrf=$M" "${@:2}" $B$1); echo -n "$code "; }
curl -s -c $CA -o /dev/null -d "login=admin&password=test" $B/connexion
T=$(tok $CA)
curl -s -b $CA -o /dev/null -d "csrf=$T&type=local" $B/administration/installation
J=$(curl -s -b $CA -d "csrf=$T" $B/administration/foyers/1/lien | grep -o 'jeton=[A-Za-z0-9_-]*' | head -1)
curl -s -c $CM -o /dev/null -d "$J&name=Claire&login=claire&password=claire-motdepasse-1&confirm=claire-motdepasse-1&foyer=Foyer+Martin" $B/invitation
M=$(tok $CM); echo "$M" > $SC/csrf
p /foyer/renommer -d name="Foyer Martin"
p /foyer/membres -d name=Claire -d kind=PERSON
p /foyer/membres -d name=Thomas -d kind=PERSON
p /foyer/membres -d name="Compte joint" -d kind=ACCOUNT
# Revenus
inc() { p /administration/revenus -d person=$1 -d kind=$2 -d label="$3" -d amount=$4 -d frequency=$5 -d start=$6 "${@:7}"; }
inc 1 SALARY "Salaire" 2650 MONTHLY 2024-09-01 -d end=2026-02-28
inc 1 SALARY "Salaire" 2780 MONTHLY 2026-03-01
inc 2 SALARY "Salaire" 2350 MONTHLY 2023-01-01
inc 2 BONUS "Prime annuelle" 1800 ANNUAL 2026-12-01
# Depenses
dep() { p /administration/depenses -d category=$1 -d label="$2" -d amount=$3 -d frequency=$4 -d start=$5; }
dep ENERGY "Électricité EDF" 118 MONTHLY 2024-01-01
dep ENERGY "Eau" 160 QUARTERLY 2024-01-15
dep TELECOM "Box internet" 39.99 MONTHLY 2024-01-01
dep TELECOM "Forfaits mobiles" 34 MONTHLY 2024-01-01
dep INSURANCE "Assurance habitation" 24 MONTHLY 2024-01-01
dep INSURANCE "Assurance auto" 640 ANNUAL 2026-02-01
dep HEALTH "Mutuelle" 96 MONTHLY 2024-01-01
dep TRANSPORT "Carburant" 140 MONTHLY 2024-01-01
dep TRANSPORT "Abonnement TCL" 74.80 MONTHLY 2024-01-01
dep CHILDREN "Crèche" 420 MONTHLY 2025-09-01
dep LEISURE "Streaming et presse" 28 MONTHLY 2024-01-01
dep LEISURE "Vacances d'été" 2200 ANNUAL 2027-07-01
dep TAXES "Impôt sur le revenu (prélèvement à la source)" 310 MONTHLY 2024-01-01
dep OTHER "Courses" 620 MONTHLY 2024-01-01
dep OTHER "Cadeaux de Noël" 600 ANNUAL 2026-12-10
# Epargne
sav() { p /epargne/compte -d person=$1 -d kind=$2 -d name="$3" -d balance=$4 "${@:5}"; }
sav 1 LIVRET_A "Livret A de Claire" 14200
sav 2 LDDS "LDDS de Thomas" 6100
sav 1 LIFE_INSURANCE "Assurance-vie" 18400 -d rate=2.6 -d montant=150
sav 2 PEA "PEA de Thomas" 350 -d opened_on=2022-05-10 -d montant=200
p /bourse/ajouter -d symbole=CW8.PA -d nom="Amundi MSCI World" -d quantity=12 -d montant=430 -d prix=pru -d compte=4
p /bourse/ajouter -d symbole=AI.PA -d nom="Air Liquide" -d quantity=8 -d montant=152 -d prix=pru -d compte=4
# Immobilier : appartement et son pret
LOC=$(curl -s -b $CM -o /dev/null -w "%{redirect_url}" --data-urlencode "csrf=$M" -d name="Appartement Croix-Rousse" -d kind=APARTMENT -d purchase=268000 \
  -d purchase_date=2019-03-01 -d area=68 -d estimate=305000 -d estimate_high=325000 -d estimate_date=2026-09-01 -d sale_fees_pct=7 $B/bien)
PP=$(echo "$LOC" | sed 's|^http://[^/]*||; s|?.*||'); echo; echo "bien: $PP"
curl -s -b $CM -o /dev/null -w "pret %{http_code} " -F "csrf=$M" -F "name=Prêt immobilier" -F "csv=@$SCRIPTS/pret.csv;type=text/csv" $B$PP/prets
# Objectifs
p /economies/objectifs -d emoji=🏖️ -d nom="Vacances 2027" -d montant=3000 -d compte=1
p /economies/objectifs -d emoji=🚗 -d nom="Nouvelle voiture" -d montant=12000 -d compte=2
echo
