from datetime import date
import pytest

from finance import amortization, summary
from dvf import median_m2, suggestion


def loan(**overrides):
    data = dict(initial=1200, reference_balance=1200, reference_date='2025-01-01', rate=0, duration_months=12, start_date='2025-01-01', due_day=15, payment=100, insurance=5)
    data.update(overrides)
    return data


def test_amortization_capital_interest_insurance_and_calendar():
    schedule, current = amortization(loan(), today=date(2025,3,1))
    assert len(schedule) == 12
    assert schedule[0]['date'] == '2025-02-15'
    assert schedule[0]['principal'] == 100
    assert schedule[0]['interest'] == 0
    assert schedule[0]['insurance'] == 5
    assert schedule[-1]['balance'] == 0
    assert current == 1100
    interest_schedule, _ = amortization(loan(rate=12, payment=110), today=date(2025,3,1))
    assert interest_schedule[0]['interest'] == pytest.approx(12)
    assert interest_schedule[0]['principal'] == pytest.approx(98)


def test_early_repayment_and_bank_correction():
    events = [dict(id=1, date='2025-02-20', kind='anticipé', amount=200),dict(id=2,date='2025-03-20',kind='correction',amount=800)]
    schedule, current = amortization(loan(), events, today=date(2025,3,25))
    assert schedule[1]['balance'] == 800
    assert current == 800
    later, current = amortization(loan(), events, today=date(2025,4,16))
    assert current == 700
    assert later[2]['balance'] == 700


def test_net_property_separate_from_repaid():
    s = summary([dict(estimate=250000), dict(estimate=100000)], [dict(initial=200000,balance=150000),dict(initial=30000,balance=10000)])
    assert s == dict(value=350000,debt=160000,repaid=70000,net=190000)


def test_dvf_excludes_multi_home_mixed_and_duplicate_dependency():
    columns = 'id_mutation,numero_disposition,date_mutation,nature_mutation,valeur_fonciere,type_local,surface_reelle_bati\n'
    content = columns + 'a,1,2025-01-10,Vente,200000,Appartement,40\n' + 'a,1,2025-01-10,Vente,200000,Dépendance,\n' + 'b,1,2025-02-10,Vente,900000,Appartement,40\n' + 'b,1,2025-02-10,Vente,900000,Maison,80\n' + 'c,1,2025-04-01,Vente,240000,Appartement,40\n' + 'd,1,2025-04-01,Vente,300000,Appartement,40\n' + 'd,1,2025-04-01,Vente,300000,Local industriel. commercial ou assimilé,30\n'
    assert median_m2(content,'Appartement') == (5500,2,'2025-04-01')


def test_dvf_rejects_insufficient_samples():
    prop = dict(insee='75101',kind='Appartement',estimate=200000,estimate_date=f'{date.today().year-2}-04-01')
    csv_data = 'id_mutation,numero_disposition,date_mutation,nature_mutation,valeur_fonciere,type_local,surface_reelle_bati\na,1,2025-01-10,Vente,200000,Appartement,40\n'
    with pytest.raises(ValueError,match='insuffisantes'):
        suggestion(prop,lambda insee, year:csv_data)
