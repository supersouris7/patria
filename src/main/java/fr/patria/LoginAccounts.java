package fr.patria;

import fr.patria.account.AccountRepository;
import fr.patria.account.IpLocator;
import fr.patria.account.SecurityLog;
import fr.patria.account.TwoFactorStore;
import fr.patria.account.User;
import fr.patria.auth.AuthController;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Ce que la connexion ({@link AuthController}) demande aux comptes : mot de passe, foyer, accueil, mot de passe
 * provisoire, double authentification et appareils retenus, journal des connexions.
 */
final class LoginAccounts implements AuthController.Users {
    private final AccountRepository accounts;
    private final SecurityLog log;
    private final TwoFactorStore twoFactors;
    private final IpLocator locator;
    private final Clock clock;

    LoginAccounts(AccountRepository accounts, SecurityLog log, TwoFactorStore twoFactors, IpLocator locator, Clock clock) {
        this.accounts = accounts;
        this.log = log;
        this.twoFactors = twoFactors;
        this.locator = locator;
        this.clock = clock;
    }

    /** Empreinte d'un jeton d'appareil : la base ne garde jamais le jeton lui-meme. */
    static String sha256(String text) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Override
    public Optional<Long> authenticate(String login, String password) {
        return accounts.authenticate(login, password).map(User::id);
    }

    @Override
    public OptionalLong firstHousehold(Long userId) {
        if (userId == null) return OptionalLong.of(accounts.households().getFirst().id());
        return accounts.accesses(userId).stream().mapToLong(fr.patria.account.Access::householdId).findFirst();
    }

    @Override
    public boolean welcomed(Long userId) {
        return accounts.welcomed(userId);
    }

    @Override
    public boolean passwordReset() {
        return accounts.mail().active();
    }

    @Override
    public boolean mustChangePassword(Long userId) {
        return userId != null && accounts.passwordToChange(userId).isPresent();
    }

    @Override
    public boolean twoFactor(Long userId) {
        return twoFactors.twoFactor(userId).enabled();
    }

    @Override
    public boolean verifyCode(Long userId, String code) {
        TwoFactorStore.TwoFactor state = twoFactors.twoFactor(userId);
        if (!state.enabled()) return false;
        long step = fr.patria.common.Totp.verify(state.secret(), code, clock.instant().getEpochSecond(), state.lastStep());
        if (step < 0) return false;
        twoFactors.useTwoFactorStep(userId, step);
        return true;
    }

    @Override
    public boolean trusted(Long userId, String token) {
        return token != null && !token.isBlank() && twoFactors.trustedDevice(userId, sha256(token), LocalDateTime.now(clock));
    }

    @Override
    public String trustDevice(Long userId) {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        String token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        LocalDateTime now = LocalDateTime.now(clock);
        twoFactors.trustDevice(userId, sha256(token), now, now.plusDays(30));
        return token;
    }

    @Override
    public void recorded(String login, Long userId, boolean success, String ip) {
        log.recordLogin(LocalDateTime.now(clock), login, userId, success, ip);
        locator.lookUp(ip);
    }
}
