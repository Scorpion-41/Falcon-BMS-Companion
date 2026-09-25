/**
 * The outline of a filled grid, as closed rings.
 *
 * Every side of a filled cell whose neighbour is empty is a piece of the boundary. Collect those pieces, oriented
 * so the filled side is always on the same hand, and chain them end to end: every corner then has as many pieces
 * arriving as leaving, so the chains close of their own accord and a ring can never be left open. Walking the
 * boundary cell by cell instead — the obvious way — gets stuck wherever a shape narrows to a single cell, and
 * closes the ring with a straight line across the field.
 */

/**
 * Rings of grid corners for the filled parts of [mask], which is [w] x [h] cells of 0 or 1.
 *
 * Outer edges come back one way round and holes the other, so the whole set fills correctly even-odd.
 */
export function outlineRings(mask, w, h) {
  const on = (x, y) => (x < 0 || y < 0 || x >= w || y >= h ? 0 : mask[y * w + x]);

  // A corner is (x, y) with 0 <= x <= w, 0 <= y <= h, keyed by a single number.
  const key = (x, y) => y * (w + 1) + x;
  const next = new Map();          // corner -> list of corners it leads to

  const add = (x0, y0, x1, y1) => {
    const k = key(x0, y0);
    const list = next.get(k);
    if (list) list.push(key(x1, y1));
    else next.set(k, [key(x1, y1)]);
  };

  // Filled on the right of travel: top edges run east, right edges south, bottom edges west, left edges north.
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      if (!on(x, y)) continue;
      if (!on(x, y - 1)) add(x, y, x + 1, y);
      if (!on(x + 1, y)) add(x + 1, y, x + 1, y + 1);
      if (!on(x, y + 1)) add(x + 1, y + 1, x, y + 1);
      if (!on(x - 1, y)) add(x, y + 1, x, y);
    }
  }

  const rings = [];
  for (const [start, list] of next) {
    while (list.length) {
      const ring = [];
      let at = start;
      let guard = 0;
      while (guard++ < 4 * (w + 1) * (h + 1)) {
        const outs = next.get(at);
        if (!outs || !outs.length) break;
        const to = outs.pop();
        ring.push(at % (w + 1), Math.floor(at / (w + 1)));
        at = to;
        if (at === start) break;
      }
      if (ring.length >= 8) rings.push(ring);
    }
  }
  return rings;
}

/** Douglas-Peucker on a closed ring of points, in the ring's own units. */
export function simplifyRing(pts, tol) {
  const n = pts.length / 2;
  if (n < 4) return pts;
  const keep = new Uint8Array(n);
  keep[0] = 1;
  keep[n - 1] = 1;
  const stack = [[0, n - 1]];
  while (stack.length) {
    const [a, b] = stack.pop();
    if (b <= a + 1) continue;
    const ax = pts[a * 2], ay = pts[a * 2 + 1], bx = pts[b * 2], by = pts[b * 2 + 1];
    const dx = bx - ax, dy = by - ay;
    const len = Math.hypot(dx, dy) || 1;
    let far = -1, fd = tol;
    for (let i = a + 1; i < b; i++) {
      const d = Math.abs((pts[i * 2] - ax) * dy - (pts[i * 2 + 1] - ay) * dx) / len;
      if (d > fd) { fd = d; far = i; }
    }
    if (far > 0) { keep[far] = 1; stack.push([a, far], [far, b]); }
  }
  const out = [];
  for (let i = 0; i < n; i++) if (keep[i]) out.push(pts[i * 2], pts[i * 2 + 1]);
  return out;
}

/** The area a ring encloses, which says whether it is worth drawing and which way round it runs. */
export function ringArea(pts) {
  let a = 0;
  for (let i = 0, n = pts.length / 2; i < n; i++) {
    const j = (i + 1) % n;
    a += pts[i * 2] * pts[j * 2 + 1] - pts[j * 2] * pts[i * 2 + 1];
  }
  return a / 2;
}
