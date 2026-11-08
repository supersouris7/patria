package fr.patria.pdf;

import fr.patria.common.NotFoundException;
import fr.patria.common.ValidationException;
import fr.patria.household.HouseholdRepository;
import fr.patria.household.Person;
import fr.patria.household.Visibility;
import fr.patria.ui.Components;
import fr.patria.ui.Html;
import fr.patria.ui.Layout;
import fr.patria.ui.Notice;
import fr.patria.web.Request;
import fr.patria.web.Response;
import fr.patria.web.Router;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * « Preparer un document » : une copie caviardee d'un PDF, avant de la confier a une IA. Toute l'interface est dans
 * Patria ; le traitement est fait par le serveur Stirling PDF de l'installation (masque s'il n'est pas configure).
 */
public final class PdfController {
    public static final String PATH = Request.PDF_UPLOAD_PATH;
    private final HouseholdRepository household;

    public PdfController(HouseholdRepository household) {
        this.household = household;
    }

    public void register(Router router) {
        router.userGet(PATH, request -> Response.page(page(request, null, null)));
        router.userPost(PATH, this::prepare);
    }

    private Response prepare(Request request) {
        Stirling stirling = require();
        Map<String, String> form = request.form().values();
        try {
            byte[] pdf = request.form().requiredFile("document", "Document PDF");
            if (!Stirling.looksLikePdf(pdf)) throw new ValidationException("Ce fichier n'est pas un PDF.");
            List<String> words = Arrays.stream(form.getOrDefault("mots", "").split("\\R")).map(String::strip).filter(word -> !word.isEmpty()).distinct().toList();
            if (words.size() > 200) throw new ValidationException("200 mots au plus.");
            List<Stirling.Preset> presets = Arrays.stream(Stirling.Preset.values()).filter(preset -> "on".equals(form.get("repere_" + preset.name()))).toList();
            if (words.isEmpty() && presets.isEmpty()) throw new ValidationException("Indiquez au moins un mot ou cochez un repère à masquer.");
            Stirling.Result result = stirling.prepare(pdf, new Stirling.Options(words, presets, "on".equals(form.get("ocr")), "on".equals(form.get("images"))));
            if (result.matches() == 0) {
                throw new ValidationException("Rien n'a été trouvé à masquer. Si c'est un scan ou une photo, cochez « Document scanné » ; sinon vérifiez les mots.");
            }
            return new Response.Download("copie-caviardee.pdf", "application/pdf", result.pdf());
        } catch (ValidationException exception) {
            return Response.invalid(page(request, form, Notice.error(exception.getMessage())));
        }
    }

    private static Stirling require() {
        Stirling stirling = Stirling.current();
        if (stirling == null) throw new NotFoundException("Page introuvable", "Cette page n'existe pas.");
        return stirling;
    }

    private Html page(Request request, Map<String, String> values, Notice notice) {
        require();
        boolean retry = values != null;
        // Noms connus du foyer (non confidentiels) et nom du compte : proposes a masquer, modifiables.
        Set<String> names = new LinkedHashSet<>();
        if (request.viewer().name() != null) names.add(request.viewer().name());
        Visibility.of(request.viewer(), household.people()).everyone().stream().filter(person -> !person.isAccount()).map(Person::name).forEach(names::add);
        String words = retry ? values.getOrDefault("mots", "") : String.join("\n", names);
        List<Html> presets = new ArrayList<>();
        for (Stirling.Preset preset : Stirling.Preset.values()) {
            boolean checked = !retry || "on".equals(values.get("repere_" + preset.name()));
            presets.add(Html.of("<label class=\"check\"><input type=\"checkbox\" name=\"repere_%s\" value=\"on\"%s> %s</label>", preset.name(),
                    Html.raw(checked ? " checked" : ""), preset.label()));
        }
        boolean ocr = retry && "on".equals(values.get("ocr"));
        boolean images = !retry || "on".equals(values.get("images"));
        Html form = Html.of("""
                <form method="post" action="%s" enctype="multipart/form-data" class="stack">%s
                <label>Document PDF <span class="required-mark">*</span><input name="document" type="file" accept=".pdf,application/pdf" required></label>
                <label class="pdf-words">Mots à masquer, un par ligne <span class="muted small">(noms, prénoms, rue, ville, employeur…)</span>
                <textarea name="mots" rows="5" maxlength="10000">%s</textarea></label>
                <fieldset class="stack"><legend class="eyebrow">Repères à masquer automatiquement</legend><div class="pdf-presets">%s</div></fieldset>
                <label class="check option"><input type="checkbox" name="ocr" value="on"%s><span><b>Document scanné ou photo</b>\
                <span class="help">Reconnaître d'abord le texte (OCR). Plus long ; à cocher si le PDF est une image.</span></span></label>
                <label class="check option"><input type="checkbox" name="images" value="on"%s><span><b>Transformer les pages en images</b>\
                <span class="help">Recommandé : le texte masqué ne peut plus être récupéré sous le rectangle noir.</span></span></label>
                <div class="form-actions"><button type="submit">Caviarder et télécharger la copie</button></div></form>""",
                PATH, Components.csrfField(request.csrf()), words, Html.concat(presets.toArray(Html[]::new)), Html.raw(ocr ? " checked" : ""),
                Html.raw(images ? " checked" : ""));
        Html content = Html.concat(Components.heading("Outils", "Préparer un document",
                        "Une copie caviardée d'un relevé, d'un contrat ou d'un échéancier, avant de la confier à une IA.", Html.EMPTY),
                Components.notice(notice),
                Components.card("Caviarder une copie", null, Html.EMPTY, Html.concat(Html.raw("""
                        <ol class="steps"><li>Choisissez le PDF et vérifiez les mots à masquer : les noms du foyer sont déjà proposés.</li>
                        <li>Patria envoie le document au serveur Stirling PDF de votre installation, qui masque les mots et les repères, puis efface
                        les métadonnées. Rien n'est conservé, ni par Patria, ni dans votre historique.</li>
                        <li><b>Relisez la copie téléchargée</b> page par page avant de l'envoyer : les adresses, signatures, QR codes et écritures
                        manuscrites ne sont pas toujours détectés. N'envoyez jamais l'original.</li></ol>"""), form)));
        return Layout.page("Préparer un document · Patria", Layout.Section.ASSISTANT, request.csrf(), content);
    }
}
