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
    private final ReentrantLock lock = new ReentrantLock();

    private Database(Path path, Connection connection) {
        this.path = path;
        this.connection = connection;
    }

    public static Database open(Path path) {
        try {
            Class.forName("org.sqlite.JDBC");
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = ON");
                statement.execute("PRAGMA busy_timeout = 5000");
                statement.execute("PRAGMA journal_mode = WAL");
            }
            return new Database(path, connection);
        } catch (ClassNotFoundException | SQLException exception) {
            throw new IllegalStateException("Impossible d'ouvrir la base " + path + ".", exception);
        }
    }

    public Path path() {
        return path;
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
