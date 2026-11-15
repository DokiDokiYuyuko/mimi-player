function timestamp(s) {
  const a = s.replace(",", ".").split(":").map(Number);
  return a.reduce((v, n) => v * 60 + n, 0);
}
function plain(s) {
  return s
    .replace(/<[^>]*>/g, "")
    .replace(/\{[^}]*\}/g, "")
    .replace(/\\N/gi, "\n")
    .replace(/&amp;/g, "&")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&nbsp;/g, " ");
}
function parse(text, format) {
  text = text.replace(/^\uFEFF/, "").replace(/\r/g, "");
  let cues = [];
  if (format.toLowerCase() === "lrc") {
    const offset =
      Number(text.match(/\[offset:([+-]?\d+)\]/i)?.[1] || 0) / 1000;
    for (const line of text.split("\n")) {
      const times = [...line.matchAll(/\[(\d+:\d+(?:\.\d+)?)\]/g)];
      const content = plain(
        line
          .replace(/\[[^\]]*\]/g, "")
          .replace(/<\d+:\d+(?:\.\d+)?>/g, "")
          .trim(),
      );
      for (const m of times)
        cues.push({
          start: Math.max(0, timestamp(m[1]) - offset),
          end: null,
          text: content,
        });
    }
    cues.sort((a, b) => a.start - b.start);
    cues = cues.reduce((out, c) => {
      const last = out.at(-1);
      if (last && last.start === c.start) last.text += "\n" + c.text;
      else out.push(c);
      return out;
    }, []);
    cues.forEach((c, i) => (c.end = cues[i + 1]?.start ?? c.start + 20));
  } else if (["ass", "ssa"].includes(format.toLowerCase())) {
    for (const line of text.split("\n"))
      if (line.startsWith("Dialogue:")) {
        const a = line.slice(9).split(",");
        if (a.length >= 10)
          cues.push({
            start: timestamp(a[1]),
            end: timestamp(a[2]),
            text: plain(a.slice(9).join(",")),
          });
      }
  } else {
    for (const block of text.split(/\n\s*\n/)) {
      const lines = block.split("\n");
      const ix = lines.findIndex((l) => l.includes("-->"));
      if (ix < 0) continue;
      const m = lines[ix].match(/([\d:.,]+)\s*-->\s*([\d:.,]+)/);
      if (m)
        cues.push({
          start: timestamp(m[1]),
          end: timestamp(m[2]),
          text: plain(lines.slice(ix + 1).join("\n")),
        });
    }
  }
  return cues
    .filter(
      (c) =>
        Number.isFinite(c.start) &&
        Number.isFinite(c.end) &&
        c.end > c.start &&
        c.text.trim(),
    )
    .sort((a, b) => a.start - b.start);
}
