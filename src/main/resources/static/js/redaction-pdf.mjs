// Un nouveau PDF compose uniquement de pixels : aucun objet du document original n'est recopie.
export function imagePdf(pages) {
  if (!pages.length) throw new Error("Document vide.");
  const encoder = new TextEncoder();
  const chunks = [];
  const offsets = [0];
  let length = 0;
  const write = value => {
    const bytes = typeof value === "string" ? encoder.encode(value) : value;
    chunks.push(bytes);
    length += bytes.length;
  };
  const object = (id, content, stream) => {
    offsets[id] = length;
    write(`${id} 0 obj\n${content}`);
    if (stream) { write("\nstream\n"); write(stream); write("\nendstream"); }
    write("\nendobj\n");
  };
  write("%PDF-1.4\n");
  object(1, "<< /Type /Catalog /Pages 2 0 R >>");
  object(2, `<< /Type /Pages /Count ${pages.length} /Kids [${pages.map((_, i) => `${3 + i * 3} 0 R`).join(" ")}] >>`);
  pages.forEach(({width, height, jpeg}, index) => {
    if (!Number.isInteger(width) || !Number.isInteger(height) || width < 1 || height < 1 || !(jpeg instanceof Uint8Array)) {
      throw new Error("Page invalide.");
    }
    const id = 3 + index * 3;
    object(id, `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${width / 2} ${height / 2}] /Resources << /XObject << /PageImage ${id + 1} 0 R >> >> /Contents ${id + 2} 0 R >>`);
    object(id + 1, `<< /Type /XObject /Subtype /Image /Width ${width} /Height ${height} /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpeg.length} >>`, jpeg);
    const command = encoder.encode(`q ${width / 2} 0 0 ${height / 2} 0 0 cm /PageImage Do Q\n`);
    object(id + 2, `<< /Length ${command.length} >>`, command);
  });
  const xref = length;
  write(`xref\n0 ${offsets.length}\n0000000000 65535 f \n`);
  offsets.slice(1).forEach(offset => write(`${String(offset).padStart(10, "0")} 00000 n \n`));
  write(`trailer\n<< /Size ${offsets.length} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`);
  return new Blob(chunks, {type: "application/pdf"});
}

export function region(x1, y1, x2, y2) {
  const clamp = n => Math.max(0, Math.min(1, n));
  return {x: clamp(Math.min(x1, x2)), y: clamp(Math.min(y1, y2)),
    right: clamp(Math.max(x1, x2)), bottom: clamp(Math.max(y1, y2))};
}

export function paintMasks(context, width, height, masks) {
  context.fillStyle = "#000000";
  masks.forEach(mask => {
    // Arrondi vers l'exterieur + 2 pixels : aucune frange antialiassee ne subsiste sur le bord selectionne.
    const x = Math.max(0, Math.floor(mask.x * width) - 2);
    const y = Math.max(0, Math.floor(mask.y * height) - 2);
    context.fillRect(x, y, Math.ceil(mask.right * width) + 2 - x, Math.ceil(mask.bottom * height) + 2 - y);
  });
}

export function sensitiveTypes(text, terms) {
  const normalize = value => value.normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLowerCase().replace(/\s+/g, " ").trim();
  const normalized = normalize(text);
  const tests = [
    ["adresse e-mail", /[\w.+-]+\s*@\s*[\w.-]+\s*\.\s*[a-z]{2,}/i],
    ["IBAN", /\b[a-z]{2}\s*\d{2}(?:\s*[a-z0-9]){11,30}\b/i],
    ["téléphone", /(?:\+33|\b0)[\s().-]*[1-9](?:[\s().-]*\d){8}\b/],
    ["numéro long (compte, identifiant…)", /\b\d(?:[\s.-]*\d){9,}\b/],
    ["libellé identifiant (nom, adresse, compte…)", /\b(nom|prenom|adresse|iban|titulaire|emprunteur|beneficiaire|telephone|courriel|numero fiscal|securite sociale|numero de contrat)\b/]
  ];
  const found = tests.filter(([, pattern]) => pattern.test(normalized)).map(([label]) => label);
  if (terms.split("\n").map(term => term.trim()).filter(Boolean).some(term =>
    normalized.includes(normalize(term)))) found.push("mot ou information que vous avez indiqué");
  return found;
}

export function fullyMasked(box, masks) {
  // Un seul masque doit couvrir toute la boite : une couverture incertaine reste a relire (faux positif prefere).
  return masks.some(mask => mask.x <= box.x && mask.y <= box.y && mask.right >= box.right && mask.bottom >= box.bottom);
}

export function textBox(item, viewport) {
  const [a, b, c, d, x, y] = item.transform;
  const length = Math.hypot(a, b);
  if (!item.transform.every(Number.isFinite) || !length || !Math.hypot(c, d) || !Number.isFinite(item.width) || item.width < 0) return null;
  // Boite volontairement large au-dessus ET sous la ligne de base, y compris sur les pages tournees.
  const points = [-1, 1].flatMap(sign => [0, 1].map(end => viewport.convertToViewportPoint(
    x + end * item.width * a / length + sign * c,
    y + end * item.width * b / length + sign * d)));
  return {x: Math.min(...points.map(point => point[0])) / viewport.width,
    y: Math.min(...points.map(point => point[1])) / viewport.height,
    right: Math.max(...points.map(point => point[0])) / viewport.width,
    bottom: Math.max(...points.map(point => point[1])) / viewport.height};
}
