package fr.patria.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class HtmlTest {
    @Test
    void textArgumentsAreEscapedAndHtmlArgumentsAreKept() {
        Html link = Html.of("<a href=\"/bien/%s\">%s</a>", 42, "<script>alert('x')</script>");
        Html wrapped = Html.of("<p>%s</p>", link);

        assertEquals("<p><a href=\"/bien/42\">&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;</a></p>", wrapped.toString());
    }

    @Test
    void nullIsRenderedAsEmptyText() {
        assertEquals("<b></b>", Html.of("<b>%s</b>", (Object) null).toString());
    }

    @Test
    void argumentCountMustMatchTemplate() {
        assertThrows(IllegalArgumentException.class, () -> Html.of("%s %s", "un"));
        assertThrows(IllegalArgumentException.class, () -> Html.of("%s", "un", "deux"));
    }
}
