---
cssclasses:
  - full-page-db
---
```dataviewjs
const traits = dv.pages('"000 A System/Traits"').where(t => t.rank_value >= 30).sort(t => t.rank_value, 'desc');
const root = dv.el("div", "", { cls: "sample-hud-card" });
root.textContent = "Active Traits: " + traits.length;
await dv.view("Meta/Scripts/sample_helper", { container: root, traits: traits.array() });
```
