// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.storage;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Connexion SQLite unique, partagee par toute l'application. SQLite n'accepte qu'un ecrivain a la fois :
 * un verrou serialise les acces, ce qui suffit largement pour une application personnelle.
 */
public final class Database implements AutoCloseable {
    @FunctionalInterface
    public interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    private final Path path;
    private final Connection connection;
    /** Cle de chiffrement, pour attacher une autre base chiffree avec la meme cle ; {@code null} : bases en clair. */
    private final String key;
    private final ReentrantLock lock = new ReentrantLock();

    private Database(Path path, Connection connection, String key) {
        this.path = path;
        this.connection = connection;
        this.key = key;
    }

    /** Base en clair (tests, ou DB_ENCRYPTION_KEY absente). */
    public static Database open(Path path) {
        return open(path, null);
    }

    /**
     * Ouvre la base, chiffree avec {@code key} si elle n'est pas nulle ; une base existante encore en clair est
     * alors chiffree d'abord. Une base chiffree ouverte sans la bonne cle est refusee avec un message clair.
     */
    public static Database open(Path path, String key) {
        try {
            Class.forName("org.sqlite.JDBC");
            if (key != null && Encryption.isPlain(path)) Encryption.encrypt(path, key);
            Connection connection = key == null ? DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath())
                    : DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath(), Encryption.properties(key));
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = ON");
                statement.execute("PRAGMA busy_timeout = 5000");
                statement.execute("PRAGMA journal_mode = WAL");
            }
            return new Database(path, connection, key);
        } catch (SQLException exception) {
            if (exception.getMessage() != null && exception.getMessage().contains("SQLITE_NOTADB")) {
                throw new IllegalStateException("La base " + path.getFileName() + " est chiffrée, ou n'est pas une base SQLite : DB_ENCRYPTION_KEY est "
                        + (key == null ? "absente." : "différente de celle utilisée pour la chiffrer."), exception);
            }
            throw new IllegalStateException("Impossible d'ouvrir la base " + path + ".", exception);
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("Impossible d'ouvrir la base " + path + ".", exception);
        }
    }

    public Path path() {
        return path;
    }

    /** {@code ATTACH DATABASE} d'une autre base de l'installation, avec la cle si les bases sont chiffrees. */
    public String attachStatement(Path file, String alias) {
        String sql = "ATTACH DATABASE '" + file.toAbsolutePath().toString().replace("'", "''") + "' AS " + alias;
        return key == null ? sql : sql + " KEY '" + key.replace("'", "''") + "'";
    }

    /** Lecture ou ecriture simple. */
    public <T> T query(Work<T> work) {
        lock.lock();
        try {
            return work.run(connection);
        } catch (SQLException exception) {
            throw new IllegalStateException("Erreur SQLite : " + exception.getMessage(), exception);
        } finally {
            lock.unlock();
        }
    }

    /** Plusieurs ecritures appliquees ensemble ou pas du tout. */
    public <T> T transaction(Work<T> work) {
        return query(connection -> {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        });
    }

    @Override
    public void close() {
        query(connection -> {
            connection.close();
            return null;
        });
    }
}
