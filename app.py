"""Application personnelle de suivi immobilier (aucune connexion bancaire)."""
import json
import math
import os
import secrets
import sqlite3
from datetime import date
from io import BytesIO
from pathlib import Path

from flask import Flask, abort, flash, g, redirect, render_template, request, send_file, session, url_for
from werkzeug.security import check_password_hash

import dvf
from finance import amortization, summary

app = Flask(__name__)
app.secret_key = os.environ.get('SECRET_KEY', 'development-only-change-me')
app.config.update(SESSION_COOKIE_HTTPONLY=True, SESSION_COOKIE_SAMESITE='Lax', MAX_CONTENT_LENGTH=8 * 1024 * 1024)


@app.template_filter('euro')
def euro(value):
    return f"{value:,.2f} €".replace(',', ' ').replace('.', ',')
DB = os.environ.get('DATABASE', '/tmp/patrimoine-immo.sqlite3')
TABLES = ('properties', 'loans', 'loan_events', 'snapshots')
SCHEMA = '''
CREATE TABLE IF NOT EXISTS properties(id INTEGER PRIMARY KEY, name TEXT NOT NULL, kind TEXT NOT NULL CHECK(kind IN ('Appartement','Maison')), commune TEXT NOT NULL, insee TEXT NOT NULL, area REAL NOT NULL CHECK(area>0), purchase REAL NOT NULL CHECK(purchase>=0), purchase_date TEXT NOT NULL, estimate REAL NOT NULL CHECK(estimate>=0), estimate_date TEXT NOT NULL, estimate_source TEXT NOT NULL DEFAULT 'personnelle', dvf_details TEXT NOT NULL DEFAULT '');
CREATE TABLE IF NOT EXISTS loans(id INTEGER PRIMARY KEY, property_id INTEGER NOT NULL REFERENCES properties(id), name TEXT NOT NULL, initial REAL NOT NULL CHECK(initial>0), reference_balance REAL NOT NULL CHECK(reference_balance>=0), reference_date TEXT NOT NULL, rate REAL NOT NULL CHECK(rate>=0), duration_months INTEGER NOT NULL CHECK(duration_months BETWEEN 1 AND 600), start_date TEXT NOT NULL, due_day INTEGER NOT NULL CHECK(due_day BETWEEN 1 AND 31), payment REAL NOT NULL CHECK(payment>0), insurance REAL NOT NULL CHECK(insurance>=0));
CREATE TABLE IF NOT EXISTS loan_events(id INTEGER PRIMARY KEY, loan_id INTEGER NOT NULL REFERENCES loans(id), kind TEXT NOT NULL CHECK(kind IN ('anticipé','correction')), date TEXT NOT NULL, amount REAL NOT NULL CHECK(amount>=0));
CREATE TABLE IF NOT EXISTS snapshots(id INTEGER PRIMARY KEY, date TEXT NOT NULL UNIQUE, property_value REAL NOT NULL, debt REAL NOT NULL, net REAL NOT NULL);
'''


def db():
    if 'db' not in g:
        Path(DB).parent.mkdir(parents=True, exist_ok=True)
        g.db = sqlite3.connect(DB, timeout=20)
        g.db.row_factory = sqlite3.Row
        g.db.execute('PRAGMA foreign_keys=ON')
        g.db.execute('PRAGMA busy_timeout=20000')
    return g.db


def init_db():
    with app.app_context():
        db().executescript(SCHEMA)
        db().execute('PRAGMA journal_mode=WAL')


@app.teardown_appcontext
def close_db(error):
    connection = g.pop('db', None)
    if connection:
        connection.close()


def rows(sql, args=()):
    return [dict(r) for r in db().execute(sql, args)]


def one(sql, args=()):
    result = db().execute(sql, args).fetchone()
    return dict(result) if result else None


def insert(table, data):
    if table not in TABLES:
        abort(400)
    columns = list(data)
    cursor = db().execute('INSERT INTO '+table+' ('+','.join(columns)+') VALUES ('+','.join('?' for _ in columns)+')', tuple(data.values()))
    db().commit()
    return cursor.lastrowid


def amount(key, strictly=False):
    value = float(request.form[key].replace(',', '.'))
    if not math.isfinite(value) or value < 0 or strictly and value == 0:
        raise ValueError('Montant invalide : '+key)
    return value


def day(key):
    value = request.form[key]
    date.fromisoformat(value)
    if value > date.today().isoformat():
        raise ValueError('Date future interdite')
    return value


@app.before_request
def auth():
    if request.endpoint == 'static':
        return
    if not os.environ.get('APP_PASSWORD') or app.secret_key == 'development-only-change-me':
        abort(503, 'Configurez APP_PASSWORD et SECRET_KEY dans .env')
    if request.endpoint != 'login' and not session.get('authenticated'):
        return redirect(url_for('login'))
    if request.method == 'POST' and not secrets.compare_digest(request.form.get('_csrf',''), session.get('csrf','')):
        abort(400, 'Formulaire expiré')


@app.context_processor
def template_context():
    return dict(csrf=session.get('csrf',''), today=date.today().isoformat())


@app.route('/connexion', methods=['GET','POST'])
def login():
    session.setdefault('csrf', secrets.token_urlsafe(32))
    if request.method == 'POST':
        expected = os.environ['APP_PASSWORD']
        entered = request.form.get('password','')
        valid = check_password_hash(expected, entered) if expected.startswith(('scrypt:','pbkdf2:')) else secrets.compare_digest(expected, entered)
        if valid:
            session.clear()
            session['csrf'] = secrets.token_urlsafe(32)
            session['authenticated'] = True
            return redirect(url_for('dashboard'))
        flash('Mot de passe incorrect.', 'error')
    return render_template('login.html')


@app.post('/deconnexion')
def logout():
    session.clear()
    return redirect(url_for('login'))


def state():
    properties = rows('SELECT * FROM properties ORDER BY id DESC')
    loans = rows('SELECT * FROM loans ORDER BY id DESC')
    month_due = 0
    for loan in loans:
        loan['events'] = rows('SELECT * FROM loan_events WHERE loan_id=? ORDER BY date,id', (loan['id'],))
        loan['schedule'], loan['balance'] = amortization(loan, loan['events'])
        month_due += sum(r['principal']+r['interest']+r['insurance'] for r in loan['schedule'] if r['date'][:7] == date.today().isoformat()[:7])
    totals = summary(properties, loans)
    totals.update(properties=properties, loans=loans, month_due=month_due, snapshots=rows('SELECT * FROM snapshots ORDER BY date DESC LIMIT 40'))
    return totals


def snapshot():
    s = state()
    db().execute('INSERT INTO snapshots(date,property_value,debt,net) VALUES (?,?,?,?) ON CONFLICT(date) DO UPDATE SET property_value=excluded.property_value,debt=excluded.debt,net=excluded.net', (date.today().isoformat(),s['value'],s['debt'],s['net']))
    db().commit()


@app.get('/')
def dashboard():
    snapshot()
    return render_template('dashboard.html', s=state())


@app.get('/administration')
def admin():
    snapshot()
    return render_template('admin.html', s=state())


def return_admin():
    snapshot()
    return redirect(url_for('admin'))


@app.post('/bien')
def add_property():
    try:
        kind = request.form['kind']
        insee = request.form['insee'].strip().upper()
        if kind not in ('Appartement','Maison') or len(insee) != 5 or not insee.isalnum():
            raise ValueError('Type ou code INSEE invalide')
        purchase_date = day('purchase_date')
        estimate_date = day('estimate_date')
        if estimate_date < purchase_date:
            raise ValueError('Estimation antérieure à l’achat')
        insert('properties', dict(name=request.form['name'].strip()[:100],kind=kind,commune=request.form['commune'].strip()[:100],insee=insee,area=amount('area',True),purchase=amount('purchase'),purchase_date=purchase_date,estimate=amount('estimate'),estimate_date=estimate_date))
        flash('Bien enregistré.', 'success')
    except (ValueError, KeyError, sqlite3.Error) as exc:
        flash(str(exc), 'error')
    return return_admin()


@app.post('/estimation')
def update_estimate():
    try:
        prop = one('SELECT * FROM properties WHERE id=?', (request.form['property_id'],))
        if not prop:
            abort(404)
        estimate_date = day('estimate_date')
        if estimate_date < prop['purchase_date']:
            raise ValueError('Estimation antérieure à l’achat')
        db().execute('UPDATE properties SET estimate=?,estimate_date=?,estimate_source=?,dvf_details=? WHERE id=?', (amount('estimate'),estimate_date,'personnelle','',prop['id']))
        db().commit()
        flash('Estimation personnelle enregistrée.', 'success')
    except (ValueError,KeyError) as exc:
        flash(str(exc), 'error')
    return return_admin()


@app.post('/dvf/<int:property_id>')
def propose_dvf(property_id):
    prop = one('SELECT * FROM properties WHERE id=?',(property_id,))
    if not prop:
        abort(404)
    try:
        result = dvf.suggestion(prop)
        session['dvf'] = dict(property_id=property_id, estimate=prop['estimate'], estimate_date=prop['estimate_date'],result=result)
        return render_template('dvf.html', prop=prop, result=result)
    except Exception as exc:
        flash('DVF non disponible : '+str(exc), 'error')
        return redirect(url_for('admin'))


@app.post('/dvf/confirmer')
def confirm_dvf():
    proposal = session.pop('dvf',None)
    if not proposal:
        abort(400)
    prop = one('SELECT * FROM properties WHERE id=?',(proposal['property_id'],))
    if not prop or prop['estimate'] != proposal['estimate'] or prop['estimate_date'] != proposal['estimate_date']:
        abort(409)
    r = proposal['result']
    details = f"DVF {r['base_year']} : {r['old'][1]} ventes, médiane {r['old'][0]:.0f} €/m² ; {r['current_year']} : {r['new'][1]} ventes, médiane {r['new'][0]:.0f} €/m², dernière vente {r['new'][2]}. Estimation précédente × {r['ratio']:.4f}."
    db().execute('UPDATE properties SET estimate=?,estimate_date=?,estimate_source=?,dvf_details=? WHERE id=?', (r['amount'],date.today().isoformat(),'DVF indicatif',details,prop['id']))
    db().commit()
    flash('Proposition DVF appliquée.', 'success')
    return return_admin()


@app.post('/pret')
def add_loan():
    try:
        if not one('SELECT id FROM properties WHERE id=?',(request.form['property_id'],)):
            abort(404)
        initial = amount('initial',True)
        balance = amount('reference_balance')
        due = int(request.form['due_day'])
        months = int(request.form['duration_months'])
        if not 1 <= due <= 31 or not 1 <= months <= 600:
            raise ValueError('Durée / jour d’échéance invalide')
        data = dict(property_id=int(request.form['property_id']),name=request.form['name'].strip()[:100],initial=initial,reference_balance=balance,reference_date=day('reference_date'),rate=amount('rate'),duration_months=months,start_date=day('start_date'),due_day=due,payment=amount('payment',True),insurance=amount('insurance'))
        amortization(data)
        insert('loans',data)
        flash('Prêt enregistré.', 'success')
    except (ValueError,KeyError,sqlite3.Error) as exc:
        flash(str(exc), 'error')
    return return_admin()


@app.post('/pret/evenement')
def loan_event():
    try:
        loan = one('SELECT * FROM loans WHERE id=?',(request.form['loan_id'],))
        if not loan:
            abort(404)
        kind = request.form['kind']
        if kind not in ('anticipé','correction'):
            abort(400)
        when = day('date')
        if when <= loan['reference_date']:
            raise ValueError('La date doit suivre celle du capital de référence')
        value = amount('amount',kind=='anticipé')
        events = rows('SELECT * FROM loan_events WHERE loan_id=?',(loan['id'],))
        amortization(loan,events+[dict(id=max([e['id'] for e in events],default=0)+1,kind=kind,date=when,amount=value)])
        insert('loan_events',dict(loan_id=loan['id'],kind=kind,date=when,amount=value))
        flash('Prêt mis à jour.', 'success')
    except (ValueError,KeyError) as exc:
        flash(str(exc), 'error')
    return return_admin()


@app.get('/sauvegarde')
def export():
    payload = json.dumps({'version':1,'tables':{t:rows('SELECT * FROM '+t) for t in TABLES}},ensure_ascii=False,indent=2).encode()
    return send_file(BytesIO(payload),mimetype='application/json',as_attachment=True,download_name='immobilier-'+date.today().isoformat()+'.json')


@app.post('/restaurer')
def restore():
    try:
        data = json.load(request.files['file'].stream)
        if data['version'] != 1 or set(data['tables']) != set(TABLES):
            raise ValueError('Version de sauvegarde inconnue')
        for table in TABLES:
            columns = {c['name'] for c in db().execute('PRAGMA table_info('+table+')')}
            if not isinstance(data['tables'][table],list) or any(not isinstance(row,dict) or set(row) != columns for row in data['tables'][table]):
                raise ValueError('Colonnes invalides dans '+table)
        db().execute('BEGIN')
        for table in reversed(TABLES):
            db().execute('DELETE FROM '+table)
        for table in TABLES:
            for row in data['tables'][table]:
                keys = list(row)
                db().execute('INSERT INTO '+table+' ('+','.join(keys)+') VALUES ('+','.join('?' for _ in keys)+')',tuple(row.values()))
        state()
        db().commit()
        flash('Sauvegarde restaurée.', 'success')
    except (KeyError,ValueError,TypeError,sqlite3.Error) as exc:
        db().rollback()
        flash('Import refusé : '+str(exc),'error')
    return redirect(url_for('admin'))


init_db()
if __name__ == '__main__':
    app.run(port=int(os.environ.get('PORT',8000)))
