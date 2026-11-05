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
