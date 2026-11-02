"use strict";

const euroWhole = new Intl.NumberFormat("fr-FR", {style: "currency", currency: "EUR", maximumFractionDigits: 0, minimumFractionDigits: 0});
const moneyFormat = new Intl.NumberFormat("fr-FR", {minimumFractionDigits: 2, maximumFractionDigits: 2});
const storage = {
  get(key) { try { return localStorage.getItem(key); } catch { return null; } },
  set(key, value) { try { localStorage.setItem(key, value); } catch { /* stockage indisponible */ } }
};
const parseNumber = (text) => {
  const cleaned = String(text).replace(/[\s  €]/g, "").replace(",", ".");
  return cleaned === "" ? NaN : Number(cleaned);
};

// ---- Tableaux : tri (valeur data-sort prioritaire) et filtre ----
document.querySelectorAll("table.sortable").forEach((table) => {
  const body = table.tBodies[0];
  if (!body) return;
  const key = (row, column) => {
    const cell = row.cells[column];
    return cell ? (cell.dataset.sort ?? cell.textContent.trim()) : "";
  };
  table.querySelectorAll("thead th").forEach((heading, column) => {
    if (!heading.textContent.trim()) return;
    heading.tabIndex = 0;
    heading.setAttribute("aria-sort", "none");
    const sort = () => {
      const ascending = heading.dataset.order !== "asc";
      table.querySelectorAll("thead th").forEach((cell) => { delete cell.dataset.order; cell.setAttribute("aria-sort", "none"); });
      heading.dataset.order = ascending ? "asc" : "desc";
      heading.setAttribute("aria-sort", ascending ? "ascending" : "descending");
      const rows = Array.from(body.rows);
      rows.sort((left, right) => {
        const a = key(left, column);
        const b = key(right, column);
        const na = Number(a);
        const nb = Number(b);
        const result = a !== "" && b !== "" && Number.isFinite(na) && Number.isFinite(nb) ? na - nb : a.localeCompare(b, "fr", {numeric: true});
        return ascending ? result : -result;
      });
      rows.forEach((row) => body.append(row));
    };
    heading.addEventListener("click", sort);
    heading.addEventListener("keydown", (event) => {
      if (event.key === "Enter" || event.key === " ") { event.preventDefault(); sort(); }
    });
  });
});

document.querySelectorAll("table[data-filter]").forEach((table) => {
  const filter = document.createElement("input");
  filter.type = "search";
  filter.className = "table-filter";
  filter.placeholder = "Filtrer…";
  filter.setAttribute("aria-label", "Filtrer le tableau");
  filter.addEventListener("input", () => {
    const query = filter.value.toLocaleLowerCase("fr");
    Array.from(table.tBodies[0].rows).forEach((row) => {
      row.hidden = !row.textContent.toLocaleLowerCase("fr").includes(query);
    });
  });
  table.closest(".table-wrap").before(filter);
});

// ---- Champs monetaires : affichage « 250 000,00 », envoi « 250000.00 » ----
document.querySelectorAll("input[data-money]").forEach((input) => {
  input.inputMode = "decimal";
  input.autocomplete = "off";
  const format = () => {
    const value = parseNumber(input.value);
    if (input.value.trim() && Number.isFinite(value)) input.value = moneyFormat.format(value);
  };
  input.addEventListener("blur", format);
  input.form?.addEventListener("submit", () => {
    const value = parseNumber(input.value);
    if (input.value.trim() && Number.isFinite(value)) input.value = value.toFixed(2);
  });
  format();
});

// ---- Confirmation des suppressions ----
document.querySelectorAll("form[data-confirm]").forEach((form) => {
  form.addEventListener("submit", (event) => {
    if (!window.confirm(form.dataset.confirm)) event.preventDefault();
  });
});

// ---- Estimation : le curseur pilote toutes les valeurs dependantes de la page ----
const SCENARIOS = {pessimistic: "Pessimiste", prudent: "Prudent", middle: "Fourchette", optimistic: "Optimiste"};
document.querySelectorAll("[data-estimate]").forEach((hero) => {
  const slider = hero.querySelector(".slider");
  const valueElement = hero.querySelector("[data-estimate-value]");
  const label = hero.querySelector("[data-estimate-label]");
  const chips = Array.from(hero.querySelectorAll("[data-scenario]"));
  const fees = Number(hero.dataset.fees || 0);
  const debt = Number(hero.dataset.debt || 0);
  const purchase = Number(hero.dataset.purchase || 0);
  const storageKey = "patria-estimate-" + hero.dataset.estimate;
  const net = document.querySelector("[data-derived='net']");
  const gain = document.querySelector("[data-derived='gain']");

  const update = (value, remember) => {
    const min = Number(slider.min);
    const max = Number(slider.max);
    value = Math.min(max, Math.max(min, Math.round(value / 10000) * 10000));
    slider.value = value;
    valueElement.textContent = euroWhole.format(value);
    const match = Object.keys(SCENARIOS).find((name) => Number(hero.dataset[name]) === value);
    label.textContent = match ? SCENARIOS[match] : "Personnalisé";
    chips.forEach((chip) => chip.classList.toggle("active", chip.dataset.scenario === match));
    const percent = max > min ? ((value - min) / (max - min)) * 100 : 0;
    slider.style.setProperty("--fill", percent + "%");
    if (net) net.textContent = euroWhole.format(value - value * fees - debt);
    if (gain) {
      const difference = value - purchase;
      gain.textContent = (difference > 0 ? "+" : "") + euroWhole.format(difference);
      gain.classList.toggle("positive", difference > 0);
      gain.classList.toggle("negative", difference < 0);
    }
    if (remember) storage.set(storageKey, String(value));
  };

  slider.addEventListener("input", () => update(Number(slider.value), true));
  chips.forEach((chip) => chip.addEventListener("click", () => update(Number(chip.dataset.value), true)));
  const saved = Number(storage.get(storageKey));
  update(Number.isFinite(saved) && saved > 0 ? saved : Number(hero.dataset.prudent), false);
});
