"""Calcul indicatif DVF à partir des fichiers communaux de data.gouv.fr."""
import csv
import io
import statistics
from datetime import date

import requests

BASE = 'https://files.data.gouv.fr/geo-dvf/latest/csv'


def median_m2(csv_text, kind):
    """Une mutation = un prix ; ne retenir qu'un seul logement, dépendances permises."""
    groups = {}
    for row in csv.DictReader(io.StringIO(csv_text)):
        if row['nature_mutation'] != 'Vente':
            continue
        key = (row['id_mutation'], row['numero_disposition'])
        group = groups.setdefault(key, {'rows': [], 'date': row['date_mutation'], 'price': row['valeur_fonciere']})
        group['rows'].append(row)
    values, dates = [], []
    for group in groups.values():
        rows = group['rows']
        homes = [r for r in rows if r['type_local'] in ('Appartement','Maison')]
        other = [r for r in rows if r['type_local'] not in ('Appartement','Maison','Dépendance','')]
        # Refuser les lots de plusieurs logements, les transactions mixtes et les terrains.
        if len(homes) != 1 or other or homes[0]['type_local'] != kind:
            continue
        try:
            area = float(homes[0]['surface_reelle_bati'])
            price = float(group['price'])
        except (ValueError, TypeError):
            continue
        if 15 <= area <= 400 and 10000 <= price <= 10_000_000 and 500 <= price / area <= 30000:
            values.append(price / area)
            dates.append(group['date'])
    return (statistics.median(values) if values else None, len(values), max(dates) if dates else None)


def fetch_year(insee, year):
    department = insee[:3] if insee.startswith(('97','98')) else insee[:2]
    url = f'{BASE}/{year}/communes/{department}/{insee}.csv'
    response = requests.get(url, timeout=18, headers={'User-Agent':'PatrimoineImmo/1.0'})
    response.raise_for_status()
    if len(response.content) > 8_000_000:
        raise ValueError('Fichier communal trop volumineux')
    return response.content.decode('utf-8-sig')


def suggestion(prop, fetch=fetch_year):
    base_year = date.fromisoformat(prop['estimate_date']).year
    current_year = date.today().year - 1  # dernier millésime annuel complet
    if base_year < current_year - 4 or base_year >= current_year:
        raise ValueError('Estimation personnelle requise sur un millésime antérieur (4 ans maximum)')
    old = median_m2(fetch(prop['insee'], base_year), prop['kind'])
    new = median_m2(fetch(prop['insee'], current_year), prop['kind'])
    if old[1] < 5 or new[1] < 5:
        raise ValueError(f'Données insuffisantes : {old[1]} ventes en {base_year}, {new[1]} en {current_year} (minimum 5 par année)')
    if not new[2] or (date.today() - date.fromisoformat(new[2])).days > 730:
        raise ValueError('Données locales trop anciennes')
    ratio = new[0] / old[0]
    return dict(amount=round(prop['estimate'] * ratio, 2), ratio=ratio, old=old, new=new, base_year=base_year, current_year=current_year)
