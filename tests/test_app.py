import io
import json
import os
from datetime import date

os.environ.setdefault('APP_PASSWORD','integration-test-password')
os.environ.setdefault('SECRET_KEY','integration-test-session-key-very-long')
import app as module


def test_web_journey(tmp_path, monkeypatch):
    monkeypatch.setenv('APP_PASSWORD','integration-test-password')
    monkeypatch.setattr(module,'DB',str(tmp_path/'integration.sqlite3'))
    module.init_db()
    client = module.app.test_client()
    assert client.get('/').status_code == 302
    client.get('/connexion')
    with client.session_transaction() as sess:
        token = sess['csrf']
    assert client.post('/connexion',data=dict(_csrf=token,password='wrong')).status_code == 200
    assert client.post('/connexion',data=dict(_csrf=token,password='integration-test-password')).status_code == 302
    with client.session_transaction() as sess:
        token = sess['csrf']
    assert client.post('/bien',data=dict(_csrf=token,name='Maison test',kind='Maison',commune='Bordeaux',insee='33063',area='80',purchase='200000',purchase_date='2024-01-01',estimate='250000',estimate_date='2025-01-01')).status_code == 302
    assert client.post('/pret',data=dict(_csrf=token,property_id='1',name='Prêt test',initial='180000',reference_balance='150000',reference_date=date.today().isoformat(),rate='2',duration_months='240',start_date='2024-01-01',due_day='15',payment='1000',insurance='25')).status_code == 302
    response = client.get('/')
    assert b'Maison test' not in response.data  # dashboard : totaux uniquement
    assert '100 000,00 €'.encode() in response.data
    response = client.get('/administration')
    assert 'Maison test'.encode() in response.data
    backup = client.get('/sauvegarde').json
    assert len(backup['tables']['properties']) == 1
    assert client.post('/restaurer',data={'_csrf':token,'file':(io.BytesIO(json.dumps(backup).encode()),'backup.json')},content_type='multipart/form-data').status_code == 302
    with module.app.app_context():
        assert len(module.rows('SELECT * FROM properties')) == 1
        assert module.state()['net'] == 100000
    monkeypatch.setattr(module.dvf,'suggestion',lambda prop:dict(amount=270000,ratio=1.08,old=(2500,10,'2024-12-01'),new=(2700,12,'2025-12-01'),base_year=2024,current_year=2025))
    assert client.post('/dvf/1',data={'_csrf':token}).status_code == 200
    assert client.post('/dvf/confirmer',data={'_csrf':token}).status_code == 302
    with module.app.app_context():
        assert module.state()['net'] == 120000
        assert module.one('SELECT estimate_source FROM properties WHERE id=1')['estimate_source'] == 'DVF indicatif'
    assert client.post('/bien',data={'name':'No CSRF'}).status_code == 400
