document.querySelectorAll("table").forEach((table) => {
  if (table.querySelector("th")?.textContent.trim() !== "Date") return;
  const filter = document.createElement("input");
  filter.className = "table-filter";
  filter.placeholder = "Filtrer le tableau";
  filter.setAttribute("aria-label", "Filtrer le tableau");
  filter.addEventListener("input", () => Array.from(table.tBodies[0].rows).forEach((row) => {
    row.hidden = !row.textContent.toLocaleLowerCase("fr").includes(filter.value.toLocaleLowerCase("fr"));
  }));
  table.parentNode.insertBefore(filter, table);
  table.querySelectorAll("th").forEach((heading, column) => {
  heading.tabIndex = 0;
  heading.title = "Trier";
  const sort = () => {
    const rows = Array.from(table.tBodies[0].rows);
    const ascending = heading.dataset.order !== "asc";
    table.querySelectorAll("th").forEach((cell) => delete cell.dataset.order);
    heading.dataset.order = ascending ? "asc" : "desc";
    rows.sort((left, right) => left.cells[column].textContent.trim().localeCompare(right.cells[column].textContent.trim(), "fr", {numeric: true}) * (ascending ? 1 : -1));
    rows.forEach((row) => table.tBodies[0].append(row));
  };
  heading.addEventListener("click", sort);
  heading.addEventListener("keydown", (event) => { if (event.key === "Enter" || event.key === " ") { event.preventDefault(); sort(); } });
  });
});

const moneyNames = new Set(["purchase", "estimate", "estimate_high", "amount", "initial", "reference_balance", "rate", "payment", "insurance"]);
const moneyFormat = new Intl.NumberFormat("fr-FR", {minimumFractionDigits: 2, maximumFractionDigits: 2});
const euroFormat = new Intl.NumberFormat("fr-FR", {style: "currency", currency: "EUR"});
document.querySelectorAll("input").forEach((input) => {
  if (!moneyNames.has(input.name)) return;
  input.type = "text";
  input.inputMode = "decimal";
  const value = () => Number(input.value.replaceAll(" ", "").replaceAll("\u202f", "").replace(",", "."));
  const format = () => { if (input.value.trim() && Number.isFinite(value())) input.value = moneyFormat.format(value()); };
  input.addEventListener("blur", format);
  input.form?.addEventListener("submit", () => { if (input.value.trim() && Number.isFinite(value())) input.value = value().toFixed(2); });
  format();
});

const hints = {
  "Cout total d'acquisition": "Montant total engage pour acheter le bien : prix, frais et couts inclus.",
  "Valeur retenue": "Estimation saisie, ou cout total d'acquisition lorsqu'aucune estimation n'est renseignee.",
  "Montant emprunte": "Total des deblocages enregistres pour le financement.",
  "Taux": "Taux annuel du pret indique dans son echeancier.",
  "Capital rembourse": "Part de capital des echeances deja arrivees a leur date.",
  "Rembourse": "Part de capital des echeances deja arrivees a leur date.",
  "Montant a rembourser": "Capital restant du sur les financements, selon les echeanciers.",
  "Restant": "Capital restant du pour ce financement.",
  "Annees restantes": "Duree entre aujourd'hui et la derniere echeance connue.",
  "Apports": "Somme des apports personnels enregistres pour ce bien."
};
document.querySelectorAll("span, th").forEach((element) => {
  const hint = hints[element.textContent.trim()];
  if (hint) element.title = hint;
});

let estimateChoice = localStorage.getItem("patria-estimate-choice") === "optimistic" ? "optimistic" : "prudent";
const applyEstimateChoice = (choice) => {
  document.querySelectorAll("[data-estimate-value]").forEach((element) => {
    element.textContent = euroFormat.format(Number(element.dataset[choice]));
  });
  document.querySelectorAll("[data-estimate-label]").forEach((label) => {
    label.textContent = choice === "optimistic" ? "Estimation optimiste" : "Estimation prudente";
  });
};
document.querySelectorAll("[data-estimate-toggle]").forEach((button) => button.addEventListener("click", () => {
  estimateChoice = estimateChoice === "prudent" ? "optimistic" : "prudent";
  localStorage.setItem("patria-estimate-choice", estimateChoice);
  applyEstimateChoice(estimateChoice);
}));
applyEstimateChoice(estimateChoice);
