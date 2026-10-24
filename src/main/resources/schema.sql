CREATE TABLE IF NOT EXISTS properties (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    kind TEXT NOT NULL CHECK(kind IN ('Appartement', 'Maison')),
    commune TEXT NOT NULL,
    insee TEXT NOT NULL,
    area REAL NOT NULL CHECK(area > 0),
    purchase REAL NOT NULL CHECK(purchase >= 0),
    purchase_date TEXT NOT NULL,
    estimate REAL NOT NULL CHECK(estimate >= 0),
    estimate_date TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS loans (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    property_id INTEGER NOT NULL REFERENCES properties(id),
    name TEXT NOT NULL,
    initial REAL NOT NULL CHECK(initial > 0),
    reference_balance REAL NOT NULL CHECK(reference_balance >= 0),
    reference_date TEXT NOT NULL,
    rate REAL NOT NULL CHECK(rate >= 0),
    duration_months INTEGER NOT NULL CHECK(duration_months BETWEEN 1 AND 600),
    start_date TEXT NOT NULL,
    due_day INTEGER NOT NULL CHECK(due_day BETWEEN 1 AND 31),
    payment REAL NOT NULL CHECK(payment > 0),
    insurance REAL NOT NULL CHECK(insurance >= 0)
);
