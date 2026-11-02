package fr.patria;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Verifie le sens des dependances entre modules (voir ARCHITECTURE.md). Un module ne peut importer que les
 * modules listes ici ; toute nouvelle dependance doit etre ajoutee consciemment.
 */
class ArchitectureTest {
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "common", Set.of(),
            "ui", Set.of("common"),
            "storage", Set.of("common"),
            "web", Set.of("common", "ui"),
            "auth", Set.of("common", "ui", "web"),
            "property", Set.of("common", "ui", "web", "storage"),
            "loan", Set.of("common", "ui", "web", "storage", "property"),
            "contribution", Set.of("common", "ui", "web", "storage", "property"),
            "portfolio", Set.of("common", "ui", "web", "storage", "property", "loan", "contribution"));
    private static final Pattern IMPORT = Pattern.compile("^import (?:static )?fr\\.patria\\.(\\w+)\\.", Pattern.MULTILINE);

    @Test
    void modulesOnlyDependOnAllowedModules() throws IOException {
        Path root = Path.of("src/main/java/fr/patria");
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, Set<String>> module : ALLOWED.entrySet()) {
            try (Stream<Path> files = Files.walk(root.resolve(module.getKey()))) {
                for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    Matcher matcher = IMPORT.matcher(Files.readString(file));
                    while (matcher.find()) {
                        String target = matcher.group(1);
                        if (!target.equals(module.getKey()) && !module.getValue().contains(target)) {
                            violations.add(root.relativize(file) + " importe fr.patria." + target);
                        }
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), () -> "Dépendances interdites :\n" + String.join("\n", violations));
    }

    @Test
    void everyModuleIsDeclared() throws IOException {
        try (Stream<Path> directories = Files.list(Path.of("src/main/java/fr/patria"))) {
            List<String> undeclared = directories.filter(Files::isDirectory).map(path -> path.getFileName().toString())
                    .filter(name -> !ALLOWED.containsKey(name)).toList();
            assertTrue(undeclared.isEmpty(), () -> "Modules absents de ArchitectureTest : " + undeclared);
        }
    }
}
