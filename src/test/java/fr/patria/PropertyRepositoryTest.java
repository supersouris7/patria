package fr.patria;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "app.password=test-password",
        "spring.datasource.url=jdbc:sqlite:target/patria-test.sqlite"
})
class PropertyRepositoryTest {
    @Autowired
    private PropertyRepository properties;

    @Test
    void persistsAProperty() {
        properties.insert(new Property(0, "Logement test", "Maison", "Commune", "12345",
                new BigDecimal("95.5"), new BigDecimal("250000"), LocalDate.of(2020, 1, 1),
                new BigDecimal("300000"), LocalDate.of(2025, 1, 1)));

        assertThat(properties.findAll()).anySatisfy(property -> {
            assertThat(property.name()).isEqualTo("Logement test");
            assertThat(property.estimate()).isEqualByComparingTo("300000");
        });
    }
}
