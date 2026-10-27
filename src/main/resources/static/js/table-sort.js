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
  "Apports": "Somme des apports personnels enregistres pour ce bien.",
  "Plus-value / moins-value": "Ecart entre le prix affiche et le prix d'achat. Hors frais de vente et fiscalite."
};
document.querySelectorAll("span, th").forEach((element) => {
  const hint = hints[element.textContent.trim()];
  if (hint) element.title = hint;
});

const estimateLabels = {pessimistic: "Pessimiste", prudent: "Prudent", middle: "Fourchette", optimistic: "Optimiste"};
let estimateChoice = localStorage.getItem("patria-estimate-choice") || "prudent";
if (!["pessimistic", "prudent", "middle", "optimistic"].includes(estimateChoice)) estimateChoice = "prudent";
const formatMoney = (value) => euroFormat.format(Math.round(Number(value) / 10000) * 10000);
const round10k = (v) => Math.round(Number(v) / 10000) * 10000;
const updateEstimateValues = (choice) => {
  estimateChoice = choice;
  localStorage.setItem("patria-estimate-choice", estimateChoice);
  document.querySelectorAll("[data-estimate-value]").forEach((element) => {
    if (element.dataset[choice] !== undefined && element.dataset[choice] !== "") {
      element.textContent = formatMoney(Number(element.dataset[choice]));
    }
  });
  document.querySelectorAll("[data-estimate-label]").forEach((label) => {
    if (label.closest("[data-estimate-tile]")) {
      label.textContent = estimateLabels[choice];
    } else {
      label.textContent = "Valeur estimée";
    }
  });
};
document.querySelectorAll("[data-estimate-toggle]").forEach((button) => button.addEventListener("click", () => {
  const choices = ["pessimistic", "prudent", "middle", "optimistic"];
  estimateChoice = choices[(choices.indexOf(estimateChoice) + 1) % choices.length];
  updateEstimateValues(estimateChoice);
}));
document.querySelectorAll("[data-estimate-tile]").forEach((tile) => {
  const slider = tile.querySelector(".estimate-tile-slider");
  const main = tile.querySelector("[data-estimate-main]");
  const label = tile.querySelector("[data-estimate-label]");
  const values = {
    pessimistic: Number(tile.dataset.pessimistic || 0),
    prudent: Number(tile.dataset.prudent || 0),
    middle: Number(tile.dataset.middle || 0),
    optimistic: Number(tile.dataset.optimistic || 0)
  };
  const findChoice = (num) => {
    const v = round10k(num);
    const order = ["pessimistic", "prudent", "middle", "optimistic"];
    let best = order[0];
    let bestDiff = Math.abs(v - round10k(values[best]));
    for (let i = 1; i < order.length; i++) {
      const d = Math.abs(v - round10k(values[order[i]]));
      if (d < bestDiff) { bestDiff = d; best = order[i]; }
    }
    return best;
  };
  if (slider) {
    slider.addEventListener("input", () => {
      const v = round10k(slider.value);
      const choice = findChoice(v);
      estimateChoice = choice;
      localStorage.setItem("patria-estimate-choice", estimateChoice);
      if (main) main.textContent = formatMoney(v);
      if (label) label.textContent = estimateLabels[choice];
      document.querySelectorAll("[data-estimate-value]").forEach((element) => {
        if (element.dataset[choice] !== undefined && element.dataset[choice] !== "") {
          element.textContent = formatMoney(Number(element.dataset[choice]));
        }
      });
    });
  }
  if (main) main.textContent = formatMoney(round10k(values[estimateChoice]));
  if (label) label.textContent = estimateLabels[estimateChoice];
  if (slider) slider.value = round10k(values[estimateChoice]);
});
updateEstimateValues(estimateChoice);
