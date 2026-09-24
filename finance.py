"""Échéancier indicatif à partir d'un capital restant dû daté."""
from calendar import monthrange
from datetime import date


def next_month(start, count, due_day):
    index = start.year * 12 + start.month - 1 + count
    year, month = divmod(index, 12)
    return date(year, month + 1, min(due_day, monthrange(year, month + 1)[1]))


def amortization(loan, events=(), today=None):
    today = today or date.today()
    start = date.fromisoformat(loan['start_date'])
    reference = date.fromisoformat(loan['reference_date'])
    balance = float(loan['reference_balance'])
    rate = float(loan['rate']) / 1200
    payment = float(loan['payment'])
    insurance = float(loan['insurance'])
    if not (0 <= balance <= float(loan['initial'])) or rate < 0 or payment <= 0 or insurance < 0 or (balance and payment <= balance * rate):
        raise ValueError('Mensualité insuffisante ou paramètres de prêt invalides')
    if reference < start or reference > today:
        raise ValueError('Date de référence hors de la période du prêt')
    adjustments = sorted(events, key=lambda e: (e['date'], e['id']))
    schedule, cursor = [], 0
    current = balance
    last_paid = reference.isoformat()
    # La durée initiale sert d'horizon, avec marge pour allongement après correction.
    for month in range(int(loan['duration_months']) + 240):
        due = next_month(start, month + 1, int(loan['due_day']))
        if due <= reference:
            continue
        while cursor < len(adjustments) and adjustments[cursor]['date'] <= due.isoformat():
            event = adjustments[cursor]
            balance = max(0, balance - float(event['amount'])) if event['kind'] == 'anticipé' else float(event['amount'])
            if balance > float(loan['initial']):
                raise ValueError('Correction supérieure au capital initial')
            cursor += 1
        if balance <= 0:
            break
        interest = balance * rate
        principal = min(balance, payment - interest)
        if principal <= 0:
            raise ValueError('Mensualité insuffisante après correction du capital')
        balance = max(0, balance - principal)
        paid = due <= today
        schedule.append(dict(date=due.isoformat(), principal=principal, interest=interest, insurance=insurance, balance=balance, paid=paid))
        if paid:
            current, last_paid = balance, due.isoformat()
        if balance == 0:
            break
    for event in adjustments:
        if last_paid < event['date'] <= today.isoformat():
            current = max(0, current - float(event['amount'])) if event['kind'] == 'anticipé' else float(event['amount'])
    return schedule, max(0, current)


def summary(properties, loans):
    value = sum(p['estimate'] for p in properties)
    debt = sum(l['balance'] for l in loans)
    repaid = sum(max(0, l['initial'] - l['balance']) for l in loans)
    return dict(value=value, debt=debt, repaid=repaid, net=value-debt)
