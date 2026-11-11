// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.web;

import fr.patria.ui.Html;
import java.util.List;

/** Reponse renvoyee par un controleur. Le routeur se charge de l'ecrire. */
public sealed interface Response {
    record Page(int status, Html html) implements Response {
    }

    record Redirect(String location, List<String> cookies) implements Response {
    }

    record Text(int status, String body) implements Response {
    }

    record NoContent() implements Response {
    }

    /** Image servie dans la page (photo de compte), jamais mise en cache partagee. */
    record Image(String contentType, byte[] body) implements Response {
    }

    /** Fichier a enregistrer (modele CSV...), propose au telechargement sous {@code filename}. */
    record Download(String filename, String contentType, byte[] body) implements Response {
    }

    static Response page(Html html) {
        return new Page(200, html);
    }

    /** Page reaffichee apres une saisie invalide. */
    static Response invalid(Html html) {
        return new Page(400, html);
    }

    static Response redirect(String location) {
        return new Redirect(location, List.of());
    }

    static Response noContent() {
        return new NoContent();
    }
}
