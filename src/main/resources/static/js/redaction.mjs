import {getDocument, GlobalWorkerOptions, AnnotationMode} from "../vendor/pdfjs/build/pdf.min.mjs";
import {imagePdf, region, paintMasks} from "./redaction-pdf.mjs";

GlobalWorkerOptions.workerSrc = new URL("../vendor/pdfjs/build/pdf.worker.min.mjs", import.meta.url).href;
const get = name => document.getElementById(`redaction-${name}`);
const canvas = get("canvas");
const context = canvas.getContext("2d");
const status = get("status");
const editor = get("editor");
const maxPixels = 12_000_000;
let pdf = null;
let image = null;
let source = null;
let pages = [];
let current = 0;
let busy = false;
let start = null;
let download = null;

function update() {
  get("page").textContent = `Page ${current + 1} / ${pages.length} — ${pages[current]?.masks.length || 0} zone(s) supprimée(s)`;
  get("previous").disabled = current === 0;
  get("next").disabled = current === pages.length - 1;
  get("undo").disabled = !pages[current]?.masks.length;
  get("reviewed").checked = pages[current]?.reviewed || false;
  get("progress").textContent = `${pages.filter(page => page.reviewed).length} / ${pages.length} pages vérifiées. Relisez aussi le PDF téléchargé avant tout partage.`;
  get("export").disabled = !pages.length || !pages.every(page => page.reviewed);
}

async function run(action) {
  if (busy) return;
  busy = true;
  editor.disabled = true;
  get("file").disabled = true;
  get("clear").disabled = true;
  try { await action(); }
  catch (error) {
    // Ne jamais recopier les messages du parseur : ils peuvent contenir des donnees du document.
    status.textContent = error instanceof Error && error.name === "UserError" ? error.message
      : "Impossible de traiter ce document (PDF invalide, protégé ou fonctionnalité non prise en charge). Aucun fichier n'a été envoyé. Essayez une copie PNG/JPEG.";
  } finally {
    busy = false;
    editor.disabled = false;
    get("file").disabled = false;
    get("clear").disabled = false;
    update();
  }
}

function reject(message) { const error = new Error(message); error.name = "UserError"; throw error; }

async function clear() {
  if (pdf) await pdf.destroy();
  pdf = null;
  image?.close();
  image = null;
  if (source) source.width = source.height = 0;
  source = null;
  pages = [];
  current = 0;
  start = null;
  canvas.width = canvas.height = 0;
  editor.hidden = true;
  get("file").value = "";
  if (download) URL.revokeObjectURL(download);
  download = null;
}

async function raster(index) {
  const page = pdf ? await pdf.getPage(index + 1) : null;
  const viewport = page?.getViewport({scale: 2}); // 144 dpi : lisible pour les tableaux d'amortissement.
  const width = Math.ceil(viewport?.width || image.width);
  const height = Math.ceil(viewport?.height || image.height);
  if (width * height > maxPixels || width > 12000 || height > 12000) {
    reject("Page trop grande : maximum 12 millions de pixels et 12 000 pixels par côté. Réduisez le document avec un outil local.");
  }
  const result = document.createElement("canvas");
  result.width = width;
  result.height = height;
  const ctx = result.getContext("2d", {alpha: false});
  ctx.fillStyle = "white";
  ctx.fillRect(0, 0, width, height);
  if (page) {
    await page.render({canvasContext: ctx, viewport, annotationMode: AnnotationMode.DISABLE, background: "white"}).promise;
    page.cleanup();
  } else ctx.drawImage(image, 0, 0);
  return result;
}

function preview(extra) {
  if (!source) return;
  context.drawImage(source, 0, 0);
  paintMasks(context, canvas.width, canvas.height, extra ? [...pages[current].masks, extra] : pages[current].masks);
}

async function show() {
  status.textContent = "Lecture de la page…";
  if (source) source.width = source.height = 0;
  source = await raster(current);
  canvas.width = source.width;
  canvas.height = source.height;
  preview();
  editor.hidden = false;
  status.textContent = "Encadrez toutes les informations à supprimer, puis vérifiez la page.";
}

get("file").addEventListener("change", () => {
  const file = get("file").files[0];
  if (!file) return;
  run(async () => {
    await clear();
    if (file.size > 15 * 1024 * 1024) reject("Document trop volumineux : maximum 15 Mio.");
    try {
      if (file.type === "application/pdf" || /\.pdf$/i.test(file.name)) {
        const task = getDocument({data: new Uint8Array(await file.arrayBuffer()),
          cMapUrl: new URL("../vendor/pdfjs/cmaps/", import.meta.url).href, cMapPacked: true,
          standardFontDataUrl: new URL("../vendor/pdfjs/standard_fonts/", import.meta.url).href,
          wasmUrl: new URL("../vendor/pdfjs/wasm/", import.meta.url).href,
          isEvalSupported: false, enableXfa: false, useSystemFonts: false,
          maxImageSize: maxPixels, stopAtErrors: true});
        task.onPassword = () => task.destroy();
        pdf = await task.promise;
        if (pdf.numPages > 30) reject("Document trop long : maximum 30 pages. Découpez-le avec un outil local.");
      } else if (["image/png", "image/jpeg"].includes(file.type)) {
        image = await createImageBitmap(file);
      } else reject("Format non accepté : choisissez un PDF, une image PNG ou JPEG.");
      pages = Array.from({length: pdf?.numPages || 1}, () => ({masks: [], reviewed: false}));
      await show();
    } catch (error) { await clear(); throw error; }
  });
});

function add(mask) {
  if (!source || busy || mask.right - mask.x < 0.001 || mask.bottom - mask.y < 0.001) return;
  pages[current].masks.push(mask);
  pages[current].reviewed = false;
  preview();
  update();
}

function point(event) {
  const rect = canvas.getBoundingClientRect();
  return {x: (event.clientX - rect.left) / rect.width, y: (event.clientY - rect.top) / rect.height};
}
canvas.addEventListener("pointerdown", event => {
  if (busy || !source || !event.isPrimary || event.button !== 0) return;
  start = point(event);
  canvas.setPointerCapture(event.pointerId);
});
canvas.addEventListener("pointermove", event => {
  if (!start || busy) return;
  const end = point(event);
  preview(region(start.x, start.y, end.x, end.y));
});
canvas.addEventListener("pointerup", event => {
  if (!start) return;
  const end = point(event);
  const mask = region(start.x, start.y, end.x, end.y);
  start = null;
  add(mask);
  preview();
});
canvas.addEventListener("pointercancel", () => { start = null; preview(); });
get("add").addEventListener("click", () => {
  const inputs = ["x", "y", "width", "height"].map(get);
  if (!inputs.every(input => input.reportValidity())) return;
  const [x, y, w, h] = inputs.map(input => Number(input.value) / 100);
  add(region(x, y, x + w, y + h));
});
get("all").addEventListener("click", () => add(region(0, 0, 1, 1)));
get("undo").addEventListener("click", () => {
  pages[current].masks.pop();
  pages[current].reviewed = false;
  preview();
  update();
});
get("reviewed").addEventListener("change", () => { pages[current].reviewed = get("reviewed").checked; update(); });
get("previous").addEventListener("click", () => run(async () => { current--; await show(); }));
get("next").addEventListener("click", () => run(async () => { current++; await show(); }));
get("clear").addEventListener("click", () => run(async () => { await clear(); status.textContent = "Document fermé. Choisissez un autre document."; }));
get("export").addEventListener("click", () => run(async () => {
  if (!pages.length || !pages.every(page => page.reviewed)) return;
  const output = [];
  for (let index = 0; index < pages.length; index++) {
    status.textContent = `Caviardage de la page ${index + 1} / ${pages.length}…`;
    const page = await raster(index);
    paintMasks(page.getContext("2d"), page.width, page.height, pages[index].masks);
    const blob = await new Promise(resolve => page.toBlob(resolve, "image/jpeg", 0.98));
    if (!blob) reject("Votre navigateur n'a pas pu exporter la page. Aucun fichier n'a été envoyé.");
    output.push({width: page.width, height: page.height, jpeg: new Uint8Array(await blob.arrayBuffer())});
    page.width = page.height = 0;
  }
  if (download) URL.revokeObjectURL(download);
  download = URL.createObjectURL(imagePdf(output));
  const link = document.createElement("a");
  link.href = download;
  link.download = "document-caviarde.pdf";
  link.click();
  status.textContent = "Copie caviardée téléchargée. Ouvrez-la et relisez toutes les pages avant de la joindre à votre IA. N'envoyez pas l'original.";
}));
status.textContent = "Prêt. Choisissez un document ; il restera dans ce navigateur.";
