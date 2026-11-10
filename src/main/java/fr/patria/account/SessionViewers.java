// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.account;

import fr.patria.web.Session;
import fr.patria.web.Viewer;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Qui consulte la page ({@link Viewer}), relu en base une fois par requete : droits, « qui voit quoi » et avis.
 * L'etat du compte tient en une requete ({@link AccountRepository#state}).
 */
public final class SessionViewers {
    private final AccountRepository accounts;
    private final SecurityLog log;
    private final HouseholdDirectory directory;

    public SessionViewers(AccountRepository accounts, SecurityLog log, HouseholdDirectory directory) {
        this.accounts = accounts;
        this.log = log;
        this.directory = directory;
    }

    /**
     * L'administrateur n'a aucune vue dans les foyers (le routeur ne lui en transmet aucune page) ; un utilisateur
     * actif, les foyers et vues ouverts (tout le foyer s'il en est gestionnaire). Le foyer de la session est garde
     * s'il est encore accessible, sinon le premier. Vide si le compte n'existe plus, est desactive ou n'a plus de foyer.
     */
    public Optional<Viewer> of(Session session) {
        if (session.isAdmin()) {
            List<Viewer.Household> households = accounts.households().stream().map(household -> new Viewer.Household(household.id(), household.name())).toList();
            if (households.isEmpty()) return Optional.empty();
            return Optional.of(Viewer.admin("Admin", households.getFirst(), households));
        }
        Optional<AccountRepository.AccountState> state = accounts.state(session.userId()).filter(found -> found.user().active());
        if (state.isEmpty()) return Optional.empty();
        User user = state.get().user();
        List<Access> accesses = accounts.accesses(user.id());
        if (accesses.isEmpty()) return Optional.empty();
        Access access = accesses.stream().filter(candidate -> candidate.householdId() == session.householdId()).findFirst().orElse(accesses.getFirst());
        List<Viewer.Household> households = accesses.stream()
                .map(candidate -> new Viewer.Household(candidate.householdId(), candidate.householdName(), candidate.realEstate())).toList();
        Map<User, Access> shared = new LinkedHashMap<>();
        accounts.accessesTo(access.householdId()).forEach((other, rights) -> {
            if (other.active()) shared.put(other, rights);
        });
        Viewer.Audience audience = AudienceText.of(shared, directory.members(access.householdId()), user.id(),
                log.accessChanges(access.householdId(), state.get().audienceSeen()));
        String notice = state.get().twoFactorRemovedOn() == null ? null : "L'administrateur a retiré votre double authentification le "
                + state.get().twoFactorRemovedOn().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) + ". Si ce n'est pas vous qui l'avez demandé, "
                + "changez votre mot de passe et réactivez-la dans Mon compte.";
        Viewer.Rights rights = Viewer.Rights.user(access.wholeHousehold(), access.members(), access.realEstate(), access.manager());
        return Optional.of(new Viewer(user.id(), user.name(), new Viewer.Household(access.householdId(), access.householdName(), access.realEstate()), households,
                rights, new Viewer.Signals(state.get().mustChangePassword(), audience, notice, emailToConfirm(user.id()))));
    }

    /** E-mail exige a la creation et pas encore confirme ; plus rien d'exige si l'administrateur a coupe l'envoi. */
    private boolean emailToConfirm(long userId) {
        AccountRepository.Email email = accounts.email(userId);
        return email.required() && !email.verified() && accounts.mail().active();
    }
}
