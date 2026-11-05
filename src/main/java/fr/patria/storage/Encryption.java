package fr.patria.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.sqlite.mc.SQLiteMCChacha20Config;

/**
 * Chiffrement des bases SQLite (SQLite3 Multiple Ciphers, ChaCha20-Poly1305) avec la cle DB_ENCRYPTION_KEY.
 *
 * <p>Une base encore en clair est chiffree sur place a sa premiere ouverture avec une cle ; les copies
 * « avant-migration » et les bases des foyers supprimes le sont au demarrage. Sans la cle, une base chiffree est
 * illisible : il n'existe aucun moyen de la recuperer.
 */
public final class Encryption {
    private static final Logger LOG = Logger.getLogger(Encryption.class.getName());
    /** Debut de tout fichier SQLite en clair. */
    private static final byte[] PLAIN_HEADER = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);
    public static final int MIN_KEY = 16;
    /** Copies et archives a chiffrer au demarrage, a cote des bases. */
    private static final Pattern LEFTOVER = Pattern.compile(".+\\.sqlite3\\.(avant-migration-.+|supprime-\\d{8}-\\d{6})");

    private Encryption() {
    }

    /** Proprietes de connexion avec la cle. */
    static Properties properties(String key) {
        return SQLiteMCChacha20Config.getDefault().withKey(key).build().toProperties();
    }

    /** Vrai si le fichier existe et est une base SQLite en clair. */
    public static boolean isPlain(Path file) {
        if (!Files.isRegularFile(file)) return false;
        try (InputStream input = Files.newInputStream(file)) {
            return Arrays.equals(input.readNBytes(PLAIN_HEADER.length), PLAIN_HEADER);
        } catch (IOException exception) {
            return false;
        }
    }

    /**
     * Chiffre sur place une base en clair : le journal WAL est d'abord reverse dans la base (mode DELETE), puis
     * {@code PRAGMA rekey} reecrit chaque page chiffree.
     */
    static void encrypt(Path file, String key) {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath())) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode = DELETE");
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA rekey = '" + key.replace("'", "''") + "'");
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Chiffrement de la base " + file.getFileName() + " impossible.", exception);
        }
        if (isPlain(file)) throw new IllegalStateException("La base " + file.getFileName() + " n'a pas été chiffrée.");
        LOG.info("Base chiffrée : " + file.getFileName());
    }

    /**
     * Chiffre les copies « avant-migration » et les bases des foyers supprimes encore en clair dans ce dossier ;
     * renvoie leur nombre. Le journal d'une archive ({@code foyer-2.sqlite3-wal.supprime-...}) est d'abord reverse.
     */
    public static int encryptLeftovers(Path directory, String key) {
        int count = 0;
        List<Path> files;
        try (Stream<Path> listing = Files.list(directory)) {
            files = listing.filter(file -> LEFTOVER.matcher(file.getFileName().toString()).matches()).toList();
        } catch (IOException exception) {
            LOG.warning("Dossier des bases illisible : " + exception.getMessage());
            return 0;
        }
        for (Path file : files) {
            if (!isPlain(file)) continue;
            String name = file.getFileName().toString();
            int cut = name.indexOf(".supprime-");
            if (cut > 0) {
                // Archive : ses journaux portent le suffixe apres « -wal » ; on les remet a l'endroit attendu par SQLite.
                String base = name.substring(0, cut);
                String suffix = name.substring(cut);
                for (String extension : List.of("-wal", "-shm")) {
                    Path journal = directory.resolve(base + extension + suffix);
                    if (Files.exists(journal)) move(journal, directory.resolve(name + extension));
                }
            }
            encrypt(file, key);
            count++;
        }
        return count;
    }

    private static void move(Path from, Path to) {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }
}
