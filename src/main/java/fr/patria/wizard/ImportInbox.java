// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.wizard;

import fr.patria.budget.BudgetItem;
import fr.patria.budget.Category;
import fr.patria.budget.ExpenseCsv;
import fr.patria.common.Frequency;
import fr.patria.common.ValidationException;
import fr.patria.household.Income;
import fr.patria.household.IncomeCsv;
import fr.patria.household.Person;
import fr.patria.savings.PositionCsv;
import fr.patria.savings.SavingsKind;
import fr.patria.storage.Database;
import fr.patria.storage.Sql;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Pile « A trier » du guide. Phase 1 : tout ce que l'IA trouve dans les documents (prompt universel) y est range, meme
 * en trop ; rien n'entre dans le budget. Phase 2 : chaque categorie du guide propose ses lignes a valider ou refuser.
 * Une ligne garde le texte de l'IA (sans son type) et se relit avec le lecteur de l'import correspondant.
 */
public final class ImportInbox {
    /** Types de lignes du prompt universel, et l'etape du guide ou elles se trient. */
    public enum Type {
        REVENU("Revenus", Step.INCOMES), DEPENSE("Dépenses", Step.FIXED), VIREMENT("Virements", Step.TRANSFERS), EPARGNE("Épargne", Step.SAVINGS),
        BOURSE("Bourse", Step.SAVINGS);

        private final String label;
        private final Step step;

        Type(String label, Step step) {
            this.label = label;
            this.step = step;
        }

        public String label() {
            return label;
        }

        public Step step() {
            return step;
        }
    }

    /** Une ligne en attente. */
    public record Item(long id, Type type, String line) {
    }

    /** Resultat d'un collage : lignes rangees par type, et les lignes illisibles (non gardees). */
    public record Added(Map<Type, Integer> counts, List<String> errors) {
        public int total() {
            return counts.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    private final Database database;

    public ImportInbox(Database database) {
        this.database = database;
    }

    public List<Item> pending(String owner) {
        return database.query(connection -> Sql.list(connection, "SELECT id, type, line FROM import_inbox WHERE owner = ? ORDER BY id",
                row -> new Item(row.getLong(1), Type.valueOf(row.getString(2)), row.getString(3)), owner));
    }

    public void remove(String owner, List<Long> ids) {
        database.transaction(connection -> {
            for (long id : ids) Sql.update(connection, "DELETE FROM import_inbox WHERE owner = ? AND id = ?", owner, id);
            return null;
        });
    }

    public void forget(String owner) {
        database.query(connection -> Sql.update(connection, "DELETE FROM import_inbox WHERE owner = ?", owner));
    }

    /** Range dans la pile chaque ligne lisible de la reponse ; les lignes en double avec la pile sont ignorees. */
    public Added add(String owner, String answer, List<Person> people, LocalDate today) {
        Map<Type, Integer> counts = new java.util.EnumMap<>(Type.class);
        List<String> errors = new ArrayList<>();
        List<String> known = pending(owner).stream().map(item -> item.type() + ";" + normalize(item.line())).toList();
        List<String[]> kept = new ArrayList<>();
        String[] lines = answer.replace("\r\n", "\n").replaceAll("(?m)^```[a-zA-Z]*\\s*$", "").split("\n");
        if (lines.length > 2000) throw new ValidationException("Réponse trop longue : collez-la en plusieurs fois.");
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || !line.contains(";")) continue;
            String head = line.substring(0, line.indexOf(';')).strip();
            String rest = line.substring(line.indexOf(';') + 1).strip();
            Optional<Type> type = Arrays.stream(Type.values()).filter(candidate -> normalize(candidate.name()).equals(normalize(head))).findFirst();
            if (type.isEmpty()) {
                if (errors.size() < 20) errors.add(line + " — type « " + head + " » inconnu");
                continue;
            }
            try {
                check(type.get(), rest, people);
            } catch (ValidationException exception) {
                if (errors.size() < 20) errors.add(line + " — " + exception.getMessage());
                continue;
            }
            String key = type.get() + ";" + normalize(rest);
            if (known.contains(key) || kept.stream().anyMatch(pair -> (pair[0] + ";" + normalize(pair[1])).equals(key))) continue;
            kept.add(new String[]{type.get().name(), rest});
            counts.merge(type.get(), 1, Integer::sum);
        }
        database.transaction(connection -> {
            for (String[] pair : kept) {
                Sql.update(connection, "INSERT INTO import_inbox (owner, type, line, created_on) VALUES (?, ?, ?, ?)", owner, pair[0], pair[1], today.toString());
            }
            return null;
        });
        return new Added(counts, errors);
    }

    /** Verifie qu'une ligne se lit (memes regles que les imports) ; leve l'erreur sinon. */
    static void check(Type type, String line, List<Person> people) {
        switch (type) {
            case DEPENSE -> expense(line, people, List.of());
            case REVENU -> income(line, people, List.of());
            case VIREMENT -> transfer(line, people);
            case EPARGNE -> account(line, people);
            case BOURSE -> position(line);
        }
    }

    /** Depense : {@code libelle;categorie;montant;frequence;debut;fin;personne;reperes}. */
    record Expense(BudgetItem item, Long person, List<String> hints, boolean duplicate) {
    }

    static Expense expense(String line, List<Person> people, List<BudgetItem> existing) {
        String[] cells = line.split(";", -1);
        if (cells.length < 5) throw new ValidationException("colonnes manquantes : libellé;catégorie;montant;fréquence;début;fin;personne;repères");
        String csv = String.join(";", Arrays.copyOfRange(cells, 0, Math.min(6, cells.length))) + (cells.length > 7 ? ";" + cells[7] : "");
        ExpenseCsv.Row row = ExpenseCsv.parse(csv, existing).getFirst();
        if (!row.valid()) throw new ValidationException(row.error());
        Long person = null;
        if (cells.length > 6 && !cells[6].isBlank() && !normalize(cells[6]).equals("foyer")) {
            person = people.stream().filter(candidate -> normalize(candidate.name()).equals(normalize(cells[6]))).map(Person::id).findFirst()
                    .orElseThrow(() -> new ValidationException("Membre « " + cells[6].strip() + " » inconnu ou non accessible."));
        }
        return new Expense(row.item(), person, row.hints(), row.duplicate());
    }

    /** Revenu : {@code personne;libelle;type;montant;frequence;debut;fin}. */
    static IncomeCsv.Row income(String line, List<Person> people, List<Income> existing) {
        IncomeCsv.Row row = IncomeCsv.parse(line, people, existing).getFirst();
        if (!row.valid()) throw new ValidationException(row.error());
        return row;
    }

    /** Virement entre membres : {@code de;vers;libelle;montant;frequence;debut;fin}. */
    record Transfer(long from, long to, String label, BigDecimal amount, Frequency frequency, LocalDate start, LocalDate end) {
    }

    static Transfer transfer(String line, List<Person> people) {
        String[] cells = line.split(";", -1);
        if (cells.length < 6) throw new ValidationException("colonnes manquantes : de;vers;libellé;montant;fréquence;début;fin");
        long from = member(cells[0], people);
        long to = member(cells[1], people);
        if (from == to) throw new ValidationException("un virement va d'un membre à un autre.");
        // Relu comme un revenu (memes regles de montant, frequence et dates).
        Income income = income(cells[0].strip() + ";" + cells[2] + ";Autre revenu;" + cells[3] + ";" + cells[4] + ";" + cells[5] + ";" + (cells.length > 6 ? cells[6] : ""),
                people, List.of()).income();
        return new Transfer(from, to, cells[2].isBlank() ? "Virement" : cells[2].strip(), income.amount(), income.frequency(), income.start(), income.end());
    }

    /** Placement : {@code personne;type;nom;solde;date;versement mensuel}. */
    record Account(long person, SavingsKind kind, String name, BigDecimal balance, LocalDate date, BigDecimal monthly) {
    }

    static Account account(String line, List<Person> people) {
        String[] cells = line.split(";", -1);
        if (cells.length < 4) throw new ValidationException("colonnes manquantes : personne;type;nom;solde;date;versement mensuel");
        long person = member(cells[0], people);
        SavingsKind kind = Arrays.stream(SavingsKind.values()).filter(candidate -> normalize(candidate.label()).equals(normalize(cells[1]))
                        || normalize(candidate.name()).equals(normalize(cells[1]))).findFirst()
                .orElseThrow(() -> new ValidationException("type de placement « " + cells[1].strip() + " » inconnu"));
        String name = cells[2].isBlank() ? kind.label() : cells[2].strip();
        Income amounts = income(cells[0].strip() + ";x;Autre revenu;" + cells[3] + ";Mensuel;" + (cells.length > 4 && !cells[4].isBlank() ? cells[4] : "01/01/2000") + ";",
                people, List.of()).income();
        BigDecimal monthly = null;
        if (cells.length > 5 && !cells[5].isBlank()) {
            monthly = income(cells[0].strip() + ";x;Autre revenu;" + cells[5] + ";Mensuel;01/01/2000;", people, List.of()).income().amount();
        }
        LocalDate date = cells.length > 4 && !cells[4].isBlank() ? amounts.start() : null;
        return new Account(person, kind, name, amounts.amount(), date, monthly);
    }

    /** Ligne de bourse : {@code compte;code;nom;quantite;prix de revient}. */
    record Position(String account, PositionCsv.Row row) {
    }

    static Position position(String line) {
        String[] cells = line.split(";", -1);
        if (cells.length < 5) throw new ValidationException("colonnes manquantes : compte;code;nom;quantité;prix de revient");
        PositionCsv.Row row = PositionCsv.parse(String.join(";", Arrays.copyOfRange(cells, 1, 5)), List.of(), code -> Optional.empty()).getFirst();
        if (!row.valid()) throw new ValidationException(row.error());
        return new Position(cells[0].strip(), row);
    }

    private static long member(String name, List<Person> people) {
        return people.stream().filter(candidate -> normalize(candidate.name()).equals(normalize(name))).map(Person::id).findFirst()
                .orElseThrow(() -> new ValidationException("Membre « " + name.strip() + " » inconnu ou non accessible."));
    }

    static String normalize(String value) {
        return Normalizer.normalize(value.strip(), Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /** Prompt universel : tous les documents d'un coup, toutes les lignes, chacune prefixee de son type. */
    public static String prompt(List<Person> people) {
        String persons = people.stream().filter(person -> !person.isAccount()).map(Person::name).collect(Collectors.joining(", "));
        String accounts = people.stream().filter(Person::isAccount).map(Person::name).collect(Collectors.joining(", "));
        String categories = Category.selectable().stream().map(Category::label).collect(Collectors.joining(", "));
        String kinds = Arrays.stream(SavingsKind.values()).map(SavingsKind::label).collect(Collectors.joining(", "));
        return """
                Tu m'aides à remplir Patria, une application de budget du foyer, à partir de TOUS les documents joints (relevés bancaires de \
                tous les comptes, bulletins de salaire, attestations de retraite ou CAF, avis d'impôt, contrats et échéanciers, relevés de \
                livrets et d'assurance-vie, exports de courtier comme Trade Republic…). Extrais TOUT ce qui peut servir : je trierai ensuite \
                moi-même. Propose large plutôt que d'oublier.

                Membres du foyer : %s%s.

                Réponds par des lignes, une par élément, SANS en-tête, colonnes séparées par un point-virgule. La première colonne est le type :

                REVENU;personne;libellé;type;montant;fréquence;début;fin
                  type : Salaire net, Prime ou Autre revenu ; montant d'un versement (salaire : net à payer avant impôt).
                DEPENSE;libellé;catégorie;montant;fréquence;début;fin;personne;repères
                  catégorie : exactement l'une de : %s ; personne : qui la paie (vide si partagée par le foyer) ;
                  repères facultatifs : Abonnement, Télécom, Streaming, Ponctuel, Hausse récente, Doublon possible, Résilié, À vérifier.
                VIREMENT;de;vers;libellé;montant;fréquence;début;fin
                  virements réguliers entre membres du foyer (ex. vers le compte joint).
                EPARGNE;personne;type;nom;solde;date du solde;versement mensuel
                  type : exactement l'un de : %s ; versement mensuel programmé s'il y en a un, sinon vide.
                BOURSE;compte;code;nom;quantité;prix de revient total
                  compte : le nom donné à la ligne EPARGNE du PEA ou compte-titres ; code : l'ISIN ; titres détenus aujourd'hui.

                Valeurs communes :
                - fréquence : exactement l'une de : Mensuel, Trimestriel, Semestriel, Annuel, Ponctuel ;
                - montants en euros, positifs, virgule décimale, sans € ni espace (ex. 35,69) ;
                - dates au format JJ/MM/AAAA ; fin vide si cela continue.

                Exemples :
                REVENU;Paul;Salaire;Salaire net;2450,00;Mensuel;01/09/2026;
                DEPENSE;Forfait mobile;Télécom et abonnements;19,99;Mensuel;05/01/2026;;Paul;Abonnement,Télécom
                VIREMENT;Paul;Compte joint;Participation;600,00;Mensuel;01/01/2026;
                EPARGNE;Paul;Livret A;Livret A;4200,00;31/10/2026;100,00
                BOURSE;PEA;IE00B4L5Y983;ETF MSCI World;12,4;1050,30

                Règles :
                - regroupe ce qui se répète en une seule ligne avec la bonne fréquence ; un paiement unique : Ponctuel ;
                - ne mets pas les mensualités des prêts immobiliers, la taxe foncière, les charges de copropriété ni l'assurance d'un bien possédé \
                (Patria les reprend de l'immobilier) ; un crédit à la consommation est une DEPENSE (catégorie Crédits) ;
                - ne recopie jamais nom de famille, adresse, IBAN, numéro de compte, de carte, fiscal, de sécurité sociale ou de contrat ;
                - n'invente rien : en cas de doute, mets le repère « À vérifier ».

                Réponse : toutes les lignes dans un seul bloc de code, puis la liste courte des points à vérifier.
                """.formatted(persons.isEmpty() ? "?" : persons, accounts.isEmpty() ? "" : " ; comptes joints : " + accounts, categories, kinds);
    }
}
