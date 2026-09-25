# The .bml mesh format

How Falcon BMS's 3D models are read, and how each part of it was checked. The reader is `src/bml.mjs`; the
pavement built on top of it is `src/pavement.mjs`. Kept here so none of this has to be worked out twice.

Tools, none of them part of a build:

```
node bml-check.mjs  <theater> <gfx>...   what one model contains
node bml-rate.mjs   <theater> [limit]    how many of a theater's models read
node bml-tile.mjs   <theater> <field>    whether a field's models tile (the correctness test)
node bml-probe.mjs  <theater> <gfx>      every plausible reading of one model, scored
node bml-why.mjs    <theater> <gfx>      where the reader gives up on a model
node bml-decode.mjs <theater> <gfx> [head|chunks|region <off> <len>]
```

## Container

```
0x00  "BML\0"
0x04  u32 version
0x08  u32
0x0C  u64 uncompressed size
0x14  u64 compressed size
0x1C  LZMA properties byte + dictionary size, then the raw LZMA stream
```

Rebuild a `.lzma` as `props (5 bytes at 0x1C) + uncompressed size (u64 LE) + the rest` and decompress with
`xz --format=lzma -d`. Everything below is offsets into the **decompressed** bytes.

## Header

Nothing sits at a fixed offset, because the file opens with a list whose length it declares:

```
0x00      u32
0x04      u32 n              how many u32s follow
0x08      n x u32
at=8+4n   u32 1
at+4      u32 0
at+8      u32 total vertex count      Osan: 180228
at+12     u32 a mesh count            unreliable — see below
at+16     1, 0, 1, 4
at+32     20 bytes of zeros
at+52     the mesh table
```

Osan has n=10 so its table is at 100; Anshan has n=6 so its table is at 84. Reading the counts at fixed offsets
works for one model and quietly gives nonsense for the next.

**The mesh count is not the number of table entries.** Anshan says sixteen and has fourteen, and those fourteen
account for all 24,207 of its vertices. The table ends where the counts have used up the vertex total, and that is
what `src/bml.mjs` walks to.

## Mesh table

One entry per mesh, beginning with that mesh's vertex count and its vertex stride. Entries are usually 78 bytes,
sometimes 78 plus one or more 48-byte blocks, so the chain is walked trying each length and kept only if it comes
out exactly right. Strides of 32, 36 and 40 all appear.

```
+0   u32   vertex count of this mesh      always a multiple of 3
+4   u32   vertex stride                  32, 36 or 40
+8   3 x f32   a position — not this mesh's first vertex; a bounding box or origin
+34  u32   an index that differs per mesh (material or texture?)
+46  u32   another such index
+58  zeros
```

## Vertex array

Follows the table, but the gap is not fixed, so it is **found**: the first offset at which a long run of records
all carry a unit-length normal. Nothing else in the file does that, and being wrong by even one record shows up at
once. For Osan it lands 80 bytes past the end of the table, not the 8 a first reading suggested — and being two
records out was exactly what made its triangles look wrong.

Some models give every mesh its own record size and some use one size throughout, and nothing says which, so both
are read and the one with more unit normals is kept.

```
+0   3 x f32   position x, y, z        feet, model space
+12  3 x f32   normal                  (0, 1, 0) for pavement, which is flat
+24  u32       colour or flags         0x33333333 on every pavement vertex seen
+28  2 x f32   texture coordinates
```

## Faces

**There is no index buffer, and none is needed: each mesh is a plain triangle list.** Every mesh count divides
exactly by three, and the meshes sit end to end in the vertex array in table order, so a mesh of *n* vertices is
*n/3* triangles taken three consecutive vertices at a time.

## Telling a correct reading from a wrong one

Five checks, in order of how much they prove:

1. The mesh counts sum to exactly the header's vertex total.
2. Every mesh count divides by three.
3. Every record carries a unit-length normal.
4. **The ground triangles tile.** A real surface covers each patch of ground once, so the sum of its triangle
   areas is within a few per cent of the area of their union. This is the one that matters, because a model can
   pass the first three and still be read wrongly. Measured values:

   | model | triangles | sum ÷ union | verdict |
   |---|---|---|---|
   | Osan "RKSO Taxiways" | 60,058 | **0.99** | correct |
   | Anshan "ZYAS Taxiways" | 8,069 | **0.99** | correct |
   | Cheongju "RKTU Taxiways" | 17,347 | **1.01** | correct |
   | Gunsan "RKJK Taxiways" | 71,925 | **1.62** | wrong — drawn, it is a fan of wedges across the field |

   `src/pavement.mjs` applies this at 1.25 and throws away any model that fails, so a field whose pavement cannot
   be trusted falls back to its taxi network rather than being given a wrong picture.
5. Drawn, the pavement lies under BMS's own taxi network — the sim's ground AI drives on pavement by definition.

The share of triangles that span the field is **not** a reliable test: Anshan is 0.8% and correct, Gunsan 1.3% and
wrong. Only a loose backstop at 5% is kept.

## What still does not read

Gunsan's and a few other fields' taxiway models pass every structural check and then fail the tiling test — all
five candidate chains and every candidate array start give the same overlapping result, so those meshes are not
plain triangle lists. A strip or a fan would explain it; that has not been worked out. Those fields keep the taxi
network drawn at real width, as before.

Also unknown: what bytes +20..77 of a mesh entry mean; why a few entries are longer than 78 bytes; and the 108
bytes Osan leaves after its vertex array.
