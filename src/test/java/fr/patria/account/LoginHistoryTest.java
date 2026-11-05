package fr.patria.account;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class LoginHistoryTest {
    @Test
    void saysTodayYesterdayOrTheDate() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 5, 9, 30);
        assertEquals("aujourd'hui 08:05", LoginHistory.when(now.withHour(8).withMinute(5), now));
        assertEquals("hier 21:12", LoginHistory.when(LocalDateTime.of(2026, 11, 4, 21, 12), now));
        assertEquals("02/11/2026 18:40", LoginHistory.when(LocalDateTime.of(2026, 11, 2, 18, 40), now));
    }
}
