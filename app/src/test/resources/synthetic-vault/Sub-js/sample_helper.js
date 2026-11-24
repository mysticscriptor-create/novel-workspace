const traits = input?.traits || [];
const container = input?.container;
if (container) {
  const summary = document.createElement("div");
  summary.className = "helper-summary";
  summary.textContent = "Loaded traits: " + traits.length;
  container.appendChild(summary);
}
return { count: traits.length };
