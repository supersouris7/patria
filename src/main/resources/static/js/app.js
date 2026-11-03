"use strict";

const euroWhole = new Intl.NumberFormat("fr-FR", {style: "currency", currency: "EUR", maximumFractionDigits: 0, minimumFractionDigits: 0});
const multipleFormat = new Intl.NumberFormat("fr-FR", {minimumFractionDigits: 2, maximumFractionDigits: 2});
const percentFormat = new Intl.NumberFormat("fr-FR", {maximumFractionDigits: 1});
const moneyFormat = new Intl.NumberFormat("fr-FR", {minimumFractionDigits: 2, maximumFractionDigits: 2});
/** Montant signe avec le vrai signe moins : « +20 000 € », « −417 € ». */
const signedEuro = (amount) => (amount > 0 ? "+" : amount < 0 ? "−" : "") + euroWhole.format(Math.abs(amount));
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

// ---- Estimation d'un bien ----
// Le curseur recalcule « Valeur nette » et « Plus-value » (meme formule que Valuation.net cote Java)
// et enregistre la valeur retenue sur le bien (POST data-save) 400 ms apres le dernier mouvement.
document.querySelectorAll("[data-estimate]").forEach((hero) => {
  const slider = hero.querySelector(".slider");
  const valueElement = hero.querySelector("[data-estimate-value]");
  const label = hero.querySelector("[data-estimate-label]");
  const saveState = hero.querySelector("[data-save-state]");
  const chips = Array.from(hero.querySelectorAll("[data-scenario]"));
  const fees = Number(hero.dataset.fees || 0);
  const debt = Number(hero.dataset.debt || 0);
  const purchase = Number(hero.dataset.purchase || 0);
  const csrf = document.querySelector("input[name='csrf']")?.value || "";
  const area = Number(hero.dataset.area || 0);
  const market = Number(hero.dataset.market || 0);
  const perSquareMeter = document.querySelector("[data-derived='sqm']");
  const gap = document.querySelector("[data-derived='gap']");
  const gapLabel = document.querySelector("[data-derived='gap-label']");
  const all = (name) => Array.from(document.querySelectorAll("[data-derived='" + name + "']"));
  const setAll = (name, text, sign) => all(name).forEach((element) => {
    element.textContent = text;
    if (sign !== undefined) {
      element.classList.toggle("positive", sign > 0);
      element.classList.toggle("negative", sign < 0);
    }
  });
  const gainBase = Number(hero.dataset.gainBase || 0);
  const invested = Number(hero.dataset.invested || 0);

  const multiple = document.querySelector("[data-derived='multiple']");

  let saved = Number(slider.value);
  let timer = null;

  const showState = (text, error) => {
    if (!saveState) return;
    saveState.textContent = text;
    saveState.classList.toggle("error", Boolean(error));
  };

  const save = (value) => {
    if (value === saved) return;
    showState("Enregistrement…");
    const body = new URLSearchParams({csrf, value: String(value)});
    fetch(hero.dataset.save, {method: "POST", body, credentials: "same-origin", keepalive: true})
      .then((response) => {
        if (!response.ok) throw new Error(String(response.status));
        saved = value;
        showState("Enregistré");
      })
      .catch(() => showState("Non enregistré, réessayez", true));
  };

  const scheduleSave = (value, delay) => {
    clearTimeout(timer);
    timer = setTimeout(() => save(value), delay);
  };

  const render = (value) => {
    const min = Number(slider.min);
    const max = Number(slider.max);
    value = Math.min(max, Math.max(min, Math.round(value / 10000) * 10000));
    slider.value = value;
    valueElement.textContent = euroWhole.format(value);
    // Les boutons de scenario portent leur nom et leur valeur : le badge affiche le premier qui correspond.
    const match = chips.find((chip) => Number(chip.dataset.value) === value);
    label.textContent = match ? match.dataset.scenario : "Personnalisée";
    chips.forEach((chip) => chip.classList.toggle("active", Number(chip.dataset.value) === value));
    slider.style.setProperty("--fill", (max > min ? ((value - min) / (max - min)) * 100 : 0) + "%");
    const afterSale = value - value * fees;
    setAll("value", euroWhole.format(value));
    setAll("sale-fees", (value * fees > 0 ? "− " : "") + euroWhole.format(value * fees));
    setAll("after-sale", euroWhole.format(afterSale));
    setAll("net", euroWhole.format(afterSale - debt));
    const difference = value - purchase;
    setAll("gain", signedEuro(difference), difference);
    const realAmount = afterSale + gainBase;
    setAll("real-gain", signedEuro(realAmount), realAmount);
    if (multiple && invested > 0) multiple.textContent = "× " + multipleFormat.format((value - value * fees - debt) / invested) + " · ";
    if (area > 0 && perSquareMeter) {
      const sqm = Math.round(value / area);
      perSquareMeter.textContent = euroWhole.format(sqm) + "/m²";
      if (market > 0 && gap) {
        const percent = Math.round(((sqm - market) / market) * 1000) / 10;
        gap.textContent = (percent > 0 ? "+" : percent < 0 ? "−" : "") + percentFormat.format(Math.abs(percent)) + "\u00a0%";
        gap.classList.toggle("positive", percent > 0);
        gap.classList.toggle("negative", percent < 0);
        if (gapLabel) gapLabel.textContent = percent > 0 ? "au-dessus du marché" : percent < 0 ? "en dessous du marché" : "au prix du marché";
      }
    }
    return value;
  };

  slider.addEventListener("input", () => scheduleSave(render(Number(slider.value)), 400));
  slider.addEventListener("change", () => scheduleSave(render(Number(slider.value)), 0));
  chips.forEach((chip) => chip.addEventListener("click", () => scheduleSave(render(Number(chip.dataset.value)), 0)));
  window.addEventListener("pagehide", () => { if (timer) { clearTimeout(timer); save(Number(slider.value)); } });
  render(saved);
});

// ---- Prix du marche : calcul en arriere-plan, la page se recharge des qu'il est termine ----
document.querySelectorAll("[data-market-pending]").forEach((element) => {
  let attempts = 0;
  const check = () => {
    attempts += 1;
    fetch(element.dataset.marketPending, {credentials: "same-origin", cache: "no-store"})
      .then((response) => response.text())
      .then((state) => {
        if (state.trim() === "ready") window.location.reload();
        else if (attempts < 40) setTimeout(check, 1500);
        else element.textContent = "Le calcul prend plus de temps que prévu. Rechargez la page plus tard.";
      })
      .catch(() => { if (attempts < 40) setTimeout(check, 3000); });
  };
  setTimeout(check, 1500);
});

// ---- « Voir tout » : affiche les lignes masquees du tableau precedent ----
document.querySelectorAll("[data-show-more]").forEach((button) => {
  button.addEventListener("click", () => {
    const table = button.closest(".show-more").previousElementSibling.querySelector("table");
    table.querySelectorAll("tr[data-more]").forEach((row) => { row.hidden = false; row.removeAttribute("data-more"); });
    button.closest(".show-more").remove();
  });
});

// ---- Modeles de depenses : un clic remplit libelle, categorie et frequence ----
document.querySelectorAll("[data-preset-label]").forEach((button) => {
  button.addEventListener("click", () => {
    const form = button.closest(".section").querySelector("form");
    form.elements.label.value = button.dataset.presetLabel;
    form.elements.category.value = button.dataset.presetCategory;
    form.elements.frequency.value = button.dataset.presetFrequency;
    form.elements.amount.focus();
  });
});

// ---- Graphique du budget : un clic sur un mois ouvre son detail dans la liste ----
const openMonth = (id, scroll) => {
  const month = id ? document.getElementById(id) : null;
  if (!month || !month.classList.contains("month")) return;
  document.querySelectorAll("details.month").forEach((other) => {
    other.open = other === month;
    other.classList.toggle("highlight", other === month);
  });
  if (scroll) month.scrollIntoView({behavior: "smooth", block: "start"});
};
document.querySelectorAll("svg.chart a.month-link").forEach((link) => {
  link.addEventListener("click", (event) => {
    event.preventDefault();
    const id = link.getAttribute("href").slice(1);
    history.replaceState(null, "", "#" + id);
    openMonth(id, true);
  });
});
if (location.hash.startsWith("#mois-")) openMonth(location.hash.slice(1), true);
