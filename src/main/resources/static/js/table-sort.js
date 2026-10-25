document.querySelectorAll("table").forEach((table) => {
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
