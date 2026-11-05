"""Real Chromium test: synthetic secrets must not survive the exported PDF.

Run against a local test instance: python tests/redaction_browser.py http://127.0.0.1:8080
Requires playwright, pymupdf, pillow and system Chromium; no real documents are used.
"""
import io
import os
from pathlib import Path
import sys
import tempfile
from urllib.parse import urlparse

import pymupdf
from PIL import Image
from playwright.sync_api import sync_playwright, expect


def check_pdf(path):
    with pymupdf.open(path) as document:
        assert len(document) == 2
        assert not document.embfile_names(), "original attachment survived"
        assert not any(document.metadata.get(key) for key in ("author", "title", "subject", "keywords"))
        for page in document:
            assert not page.get_text(), "hidden text survived"
            assert not list(page.annots() or []), "annotation survived"
            assert not list(page.widgets() or []), "form survived"
            assert not page.get_links(), "link survived"
        first = document[0].get_pixmap(matrix=pymupdf.Matrix(2, 2))
        pixels = Image.frombytes("RGB", (first.width, first.height), first.samples)
        assert max(pixels.crop((10, 10, 780, 190)).getextrema()[0]) < 10, "masked pixels are not black"
        # The unmasked financial row remains visible, not a blank export.
        assert min(pixels.crop((35, 370, 500, 420)).getextrema()[0]) < 100
        second = document[1].get_pixmap()
        assert max(second.samples) < 10, "whole rotated page was not removed"


def main():
    base = sys.argv[1].rstrip("/")
    with tempfile.TemporaryDirectory(prefix="patria-redaction-", dir="/tmp/opencode") as tmp, sync_playwright() as playwright:
        directory = Path(tmp)
        source = directory / "original-secret.pdf"
        with pymupdf.open() as fixture:
            first = fixture.new_page(width=400, height=400)
            first.insert_text((20, 50), "SECRET-NAME-IBAN-12345", fontsize=15)
            first.insert_text((20, 200), "Mensualite 1234 EUR", fontsize=15)
            first.add_text_annot((350, 300), "SECRET-ANNOTATION")
            first.insert_link({"kind": pymupdf.LINK_URI, "from": pymupdf.Rect(0, 0, 100, 20), "uri": "https://example.invalid/SECRET"})
            second = fixture.new_page(width=500, height=300)
            second.insert_text((20, 50), "SECRET-ROTATED-PAGE")
            second.set_rotation(90)
            fixture.set_metadata({"author": "SECRET-AUTHOR", "title": "SECRET-TITLE"})
            fixture.embfile_add("SECRET.txt", b"SECRET-ATTACHMENT")
            fixture.save(source)
        browser = playwright.chromium.launch(executable_path="/usr/bin/chromium", headless=True, args=["--no-sandbox"])
        context = browser.new_context(accept_downloads=True, viewport={"width": 1280, "height": 1000})
        page = context.new_page()
        errors, requests = [], []
        page.on("pageerror", lambda error: errors.append(str(error)))
        page.on("request", lambda request: requests.append((request.method, request.url, request.post_data)))
        page.goto(base + "/aide/caviardage")
        expect(page.locator("#redaction-status")).to_contain_text("Prêt.")
        page.locator("#redaction-file").set_input_files(source)
        expect(page.locator("#redaction-page")).to_contain_text("Page 1 / 2", timeout=30000)
        expect(page.locator("#redaction-export")).to_be_disabled()
        # Keyboard path: top quarter contains the secret.
        for field, value in {"x": "0", "y": "0", "width": "100", "height": "25"}.items():
            page.locator("#redaction-" + field).fill(value)
        page.locator("#redaction-add").click()
        page.locator("#redaction-reviewed").check()
        expect(page.locator("#redaction-export")).to_be_disabled()
        # Pointer selection, backwards drag, then undo: both must invalidate verification.
        box = page.locator("#redaction-canvas").bounding_box()
        page.mouse.move(box["x"] + 200, box["y"] + 250)
        page.mouse.down()
        page.mouse.move(box["x"] + 120, box["y"] + 220)
        page.mouse.up()
        expect(page.locator("#redaction-reviewed")).not_to_be_checked()
        page.locator("#redaction-undo").click()
        page.locator("#redaction-reviewed").check()
        page.locator("#redaction-next").click()
        expect(page.locator("#redaction-page")).to_contain_text("Page 2 / 2")
        page.locator("#redaction-all").click()
        page.locator("#redaction-reviewed").check()
        expect(page.locator("#redaction-export")).to_be_enabled()
        with page.expect_download() as event:
            page.locator("#redaction-export").click()
        exported = directory / "export.pdf"
        event.value.save_as(exported)
        check_pdf(exported)
        assert b"SECRET" not in exported.read_bytes()
        page.locator("#redaction-previous").click()
        expect(page.locator("#redaction-page")).to_contain_text("Page 1 / 2")
        expect(page.locator("#redaction-reviewed")).to_be_checked()
        if os.environ.get("PATRIA_SCREENSHOTS"):
            target = Path(os.environ["PATRIA_SCREENSHOTS"])
            target.mkdir(parents=True, exist_ok=True)
            page.locator("#redaction-editor").screenshot(path=target / "caviardage.png")
        page.locator("#redaction-clear").click()
        expect(page.locator("#redaction-editor")).to_be_hidden()
        # PNG/JPEG path, plus reimport of the image-only PDF.
        for extension in ("PNG", "JPEG"):
            data = io.BytesIO()
            Image.new("RGB", (120, 80), "red").save(data, format=extension)
            page.locator("#redaction-file").set_input_files({"name": "test." + extension.lower(), "mimeType": "image/" + extension.lower(), "buffer": data.getvalue()})
            expect(page.locator("#redaction-page")).to_contain_text("Page 1 / 1")
            page.locator("#redaction-all").click()
            page.locator("#redaction-reviewed").check()
            with page.expect_download() as image_event:
                page.locator("#redaction-export").click()
            with pymupdf.open(image_event.value.path()) as image_pdf:
                assert not image_pdf[0].get_text()
                assert max(image_pdf[0].get_pixmap().samples) < 10
        page.locator("#redaction-file").set_input_files(exported)
        expect(page.locator("#redaction-page")).to_contain_text("Page 1 / 2")
        # Fail closed: a corrupt or oversized input must clear the previous document.
        for payload, message in [(b"%PDF-broken", "Impossible de traiter"), (b"x" * (15 * 1024 * 1024 + 1), "trop volumineux")]:
            page.locator("#redaction-file").set_input_files({"name": "invalid.pdf", "mimeType": "application/pdf", "buffer": payload})
            expect(page.locator("#redaction-status")).to_contain_text(message)
            expect(page.locator("#redaction-editor")).to_be_hidden()
            expect(page.locator("#redaction-export")).to_be_disabled()
        assert not errors, errors
        assert all(method == "GET" and body is None for method, url, body in requests), "file upload detected"
        assert all(urlparse(url).netloc == urlparse(base).netloc for _, url, _ in requests), "external network request detected"
        browser.close()
        print("OK: PDF/image caviardage, masks, validation, rotation, metadata, hidden text, attachments, corrupt/oversized files, no uploads or external calls")


if __name__ == "__main__":
    main()
