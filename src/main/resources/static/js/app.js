"use strict";

const siteHeader = document.querySelector('.topbar');
// Hauteur de l'en-tete fixe, pour placer les menus lateraux juste en dessous.
if (siteHeader && 'ResizeObserver' in window) new ResizeObserver(() => {
  document.documentElement.style.setProperty('--header-height', `${siteHeader.getBoundingClientRect().height}px`);
}).observe(siteHeader);

// Les menus natifs restent utilisables sans JavaScript.
const navigationMenus = document.querySelectorAll(".nav-menu, .audience");
const navText = (text) => text.normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLocaleLowerCase("fr");
const filterNavigation = (menu) => {
  const input = menu.querySelector('.nav-search input');
  if (!input) return;
  const query = navText(input.value.trim());
  const links = menu.querySelectorAll('.nav-entries a[href]');
  links.forEach((link) => { link.hidden = !navText(link.textContent).includes(query); });
  menu.querySelector('.nav-empty').hidden = Array.from(links).some((link) => !link.hidden);
};
const loadNavigationProperties = async (menu) => {
  const target = menu.querySelector('[data-property-links]');
  if (!target || target.dataset.loaded || target.dataset.loading) return;
  target.dataset.loading = 'true';
  target.querySelector('[role="status"]')?.remove();
  const status = document.createElement('p');
  status.setAttribute('role', 'status');
  status.textContent = 'Chargement des biens…';
  target.append(status);
  try {
    const response = await fetch('/navigation/biens');
    if (!response.ok || response.redirected) throw new Error('navigation');
    target.innerHTML = await response.text();
    target.dataset.loaded = 'true';
    target.querySelectorAll('a').forEach((link) => {
      if (link.pathname === location.pathname && !link.hash) link.setAttribute('aria-current', 'page');
    });
    filterNavigation(menu);
  } catch {
    status.textContent = 'Liste indisponible. Utilisez « Mes biens », ou refermez puis rouvrez la recherche pour réessayer.';
  } finally {
    delete target.dataset.loading;
  }
};
navigationMenus.forEach((menu) => {
  menu.addEventListener("toggle", () => {
    if (!menu.open) return;
    navigationMenus.forEach((other) => { if (other !== menu) other.open = false; });
    menu.querySelector('.nav-search input')?.focus({preventScroll: true});
    filterNavigation(menu);
    loadNavigationProperties(menu);
  });
  menu.querySelector('.nav-search input')?.addEventListener('input', () => filterNavigation(menu));
});

const navigationStatus = document.querySelector('[data-navigation-status]');
if (navigationStatus) {
  (async () => {
    try {
      const response = await fetch('/navigation/etat');
      if (!response.ok || response.redirected) throw new Error('navigation');
      const state = await response.json();
      if (!Number.isInteger(state.warnings) || state.warnings < 0 || !Number.isInteger(state.progress) || state.progress < 0 || state.progress > 100) throw new Error('navigation');
      // Barre d'ordinateur et menu burger : les memes indicateurs aux deux endroits.
      navigationStatus.querySelectorAll('[data-warning-count]').forEach((warnings) => {
        warnings.textContent = state.warnings;
        warnings.hidden = state.warnings === 0;
        warnings.closest('a').setAttribute('aria-label', `Bilan de santé, ${state.warnings} points à corriger ou surveiller`);
      });
      navigationStatus.querySelectorAll('[data-guide-progress]').forEach((progress) => {
        progress.textContent = `${state.progress} %`;
        progress.hidden = false;
        progress.closest('a').setAttribute('aria-label', `Assistant, guide complété à ${state.progress} %`);
      });
    } catch {
      navigationStatus.querySelectorAll('[data-warning-count]').forEach((element) => { element.closest('a').title = 'Indicateur indisponible — ouvrir le bilan de santé'; });
      navigationStatus.querySelectorAll('[data-guide-progress]').forEach((element) => { element.closest('a').title = 'Indicateur indisponible — ouvrir le guide de saisie'; });
    }
  })();
}
document.addEventListener("click", (event) => {
  navigationMenus.forEach((menu) => {
    if (!menu.contains(event.target) || event.target.closest("a")) menu.open = false;
  });
});
document.addEventListener("keydown", (event) => {
  if (event.key === "Escape") navigationMenus.forEach((menu) => {
    if (menu.open) { menu.open = false; menu.querySelector("summary").focus(); }
  });
});

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
    // Sans data-save (consultation par un utilisateur), le curseur reste local.
    if (value === saved || !hero.dataset.save) return;
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
    value = Math.min(max, Math.max(min, Math.round(value / 1000) * 1000));
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

// ---- Prompt IA des imports : copie dans le presse-papiers ----
// L'API presse-papiers n'existe qu'en HTTPS (ou sur localhost) : sinon, le prompt est affiche et selectionne.
document.querySelectorAll("button[data-copy]").forEach((button) => {
  button.addEventListener("click", async () => {
    const source = document.getElementById(button.dataset.copy);
    const status = button.parentElement.querySelector(".copy-status");
    let copied = false;
    try {
      await navigator.clipboard.writeText(source.value);
      copied = true;
    } catch {
      const details = source.closest("details");
      if (details) details.open = true;
      source.focus();
      source.select();
      try { copied = document.execCommand("copy"); } catch { copied = false; }
    }
    status.textContent = copied ? "Prompt copié : collez-le dans votre IA avec vos documents, puis collez sa réponse dans Patria."
      : "Copie automatique impossible : le prompt est sélectionné ci-dessous, copiez-le (Ctrl+C).";
  });
});

// ---- Selecteur de foyer (en-tete) : changement immediat ----
document.querySelectorAll("select[data-autosubmit]").forEach((select) => {
  select.addEventListener("change", () => select.form.submit());
});

// ---- Droits d'un utilisateur : sans « Acces a ce foyer », les autres cases sont grisees ----
document.querySelectorAll("form[data-access-form]").forEach((form) => {
  const access = form.elements.access;
  const details = form.querySelector("[data-access-details]");
  if (!access || !details) return;
  const update = () => { details.disabled = !access.checked; };
  access.addEventListener("change", update);
  update();
});

// ---- Assistant : taper un montant coche la ligne ----
document.querySelectorAll(".check-row").forEach((row) => {
  const box = row.querySelector("input[data-check-row]");
  const amount = row.querySelector("input[name^='amount_']");
  if (!box || !amount) return;
  amount.addEventListener("input", () => { if (amount.value.trim() !== "") box.checked = true; });
});

// Bilan : une tuile mene a sa liste et la deplie (aussi a l'ouverture d'un lien avec ancre).
const openTarget = (hash) => {
  const target = hash && document.getElementById(decodeURIComponent(hash.slice(1)));
  if (!target) return;
  if (target.tagName === 'DETAILS') target.open = true;
  target.querySelectorAll('details').forEach((details) => { details.open = true; });
  target.scrollIntoView({behavior: 'smooth', block: 'start'});
};
document.querySelectorAll('a[data-open-target]').forEach((link) => link.addEventListener('click', (event) => {
  event.preventDefault();
  history.replaceState(null, '', link.hash);
  openTarget(link.hash);
}));
if (location.hash && document.querySelector(`a[data-open-target][href="${CSS.escape(location.hash)}"]`)) openTarget(location.hash);

// Tuile avec explication (?) : toute la tuile la deplie, pas seulement le texte.
document.querySelectorAll('.metric').forEach((tile) => {
  const help = tile.querySelector('details.metric-help');
  if (!help) return;
  tile.classList.add('has-help');
  tile.addEventListener('click', (event) => {
    if (event.target.closest('a, button, summary, input, select, textarea, label')) return;
    help.open = !help.open;
  });
});

// Apercu de l'import : cases par ligne, boutons « Tout », « Rien » et par type (categorie ou repere de l'IA).
(() => {
  const pickers = document.querySelector("[data-import-pickers]");
  if (!pickers) return;
  const rows = [...document.querySelectorAll("[data-import-row]")];
  const inGroup = (box, group) => (box.dataset.groups || "").split("|").includes(group);
  const refresh = () => {
    const checked = rows.filter(box => box.checked).length;
    const count = pickers.querySelector("[data-import-count]");
    if (count) count.textContent = checked + " ligne" + (checked > 1 ? "s" : "") + " cochée" + (checked > 1 ? "s" : "") + " sur " + rows.length + ".";
    rows.forEach(box => {
      const state = box.closest("tr")?.querySelector("[data-import-state]");
      if (state) {
        state.textContent = box.checked ? "À importer" : "Ignorée";
        state.classList.toggle("paid", box.checked);
      }
    });
    pickers.querySelectorAll("[data-import-group]").forEach(button => {
      const members = rows.filter(box => inGroup(box, button.dataset.importGroup));
      button.setAttribute("aria-pressed", String(members.length > 0 && members.every(box => box.checked)));
      button.classList.toggle("active", members.length > 0 && members.every(box => box.checked));
    });
    const submit = document.querySelector("[data-import-submit]");
    if (submit) {
      submit.disabled = checked === 0;
      submit.textContent = checked === 0 ? "Rien à importer" : "Importer " + checked + " dépense" + (checked > 1 ? "s" : "");
    }
  };
  pickers.addEventListener("click", event => {
    const all = event.target.closest("[data-import-all]");
    const group = event.target.closest("[data-import-group]");
    if (all) rows.forEach(box => { box.checked = all.dataset.importAll === "on"; });
    if (group) {
      const members = rows.filter(box => inGroup(box, group.dataset.importGroup));
      const on = !members.every(box => box.checked);
      members.forEach(box => { box.checked = on; });
    }
    if (all || group) refresh();
  });
  rows.forEach(box => box.addEventListener("change", refresh));
  refresh();
})();

// Ajout d'un placement : taux officiel pre-rempli (et non modifiable) pour un livret reglemente, saisie controlee sinon.
document.querySelectorAll("[data-regulated-rates]").forEach(marker => {
  const form = marker.closest("form");
  const kind = form?.querySelector("select[name=kind]");
  const rate = form?.querySelector("input[name=rate]");
  if (!kind || !rate) return;
  const rates = Object.fromEntries(marker.dataset.regulatedRates.split(";").filter(Boolean).map(entry => entry.split("=")));
  rate.setAttribute("inputmode", "decimal");
  rate.setAttribute("pattern", "\\d{1,2}([.,]\\d{1,3})?");
  rate.title = "Un taux entre 0 et 30, par exemple 2,5";
  const apply = () => {
    const official = rates[kind.value];
    if (official) {
      rate.value = official;
      rate.readOnly = true;
      rate.dataset.auto = "1";
    } else if (rate.dataset.auto) {
      rate.value = "";
      rate.readOnly = false;
      delete rate.dataset.auto;
    }
  };
  kind.addEventListener("change", apply);
  apply();
});

// IA locale : un document depose peut prendre quelques minutes a analyser ; on le dit et on evite le double envoi.
document.addEventListener("submit", event => {
  const form = event.target;
  const file = form.querySelector?.("input[name=document]");
  if (!file || !file.files || file.files.length === 0) return;
  const button = form.querySelector("button[type=submit]");
  if (button) {
    button.disabled = true;
    button.textContent = "Analyse par l'IA locale en cours…";
  }
});

// Tableau des droits : ajouter un compte au foyer fait apparaitre sa ligne (cases a cocher, puis Enregistrer).
document.querySelectorAll("[data-add-account]").forEach(select => {
  select.addEventListener("change", () => {
    const row = select.closest("form")?.querySelector(`tr[data-candidate="${CSS.escape(select.value)}"]`);
    if (!row) return;
    row.hidden = false;
    row.removeAttribute("data-candidate");
    select.querySelector(`option[value="${CSS.escape(select.value)}"]`)?.remove();
    select.value = "";
    row.querySelector("input[type=checkbox]")?.focus();
    if (select.options.length <= 1) select.closest("label").hidden = true;
  });
});
