// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.ui;

import io.nayuki.qrcodegen.QrCode.Ecc;

/** QR code dessine en SVG par le serveur (rien n'est envoye a un service exterieur). */
public final class QrCode {
    private QrCode() {
    }

    public static Html svg(String text, String label) {
        io.nayuki.qrcodegen.QrCode code = io.nayuki.qrcodegen.QrCode.encodeText(text, Ecc.MEDIUM);
        int border = 4;
        int size = code.size + border * 2;
        StringBuilder path = new StringBuilder();
        for (int y = 0; y < code.size; y++) {
            for (int x = 0; x < code.size; x++) {
                if (code.getModule(x, y)) path.append('M').append(x + border).append(',').append(y + border).append("h1v1h-1z");
            }
        }
        return Html.of("""
                <svg class="qr-code" viewBox="0 0 %s %s" width="220" height="220" role="img" aria-label="%s" shape-rendering="crispEdges">\
                <rect width="100%" height="100%" fill="#fff"/><path d="%s" fill="#000"/></svg>""", size, size, label, path.toString());
    }
}
