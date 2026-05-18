# Hytale region-file format

How the Hytale server writes world chunks to disk. Reverse-engineered from the
decompiled server (`Hytale-Server-Unpacked-latest`, build 0.5.0-pre.8).
Reference for the planned `meridian-world-downloader`.

## Source map

| Concern | Server class |
|---------|--------------|
| Storage backend selection | `…world.storage.provider.DefaultChunkStorageProvider` |
| Region-file backend (default) | `…world.storage.provider.IndexedStorageChunkStorageProvider` |
| Region-file binary container | `com.hypixel.hytale.storage.IndexedStorageFile` |
| Chunk → blob serialization | `…world.storage.BufferChunkSaver` |
| BSON encoding | `…server.core.util.BsonUtil` |
| Coordinate math | `com.hypixel.hytale.math.util.ChunkUtil` |
| Section voxel component | `…world.chunk.section.BlockSection` |
| Section palettes | `…world.chunk.section.palette.*` (`ByteSectionPalette`, …) |
| Network chunk packet | `…protocol.packets.world.SetChunk` (id 131) |

## Storage backends

`DefaultChunkStorageProvider` picks the backend. Default is
**`IndexedStorageChunkStorageProvider`** — the region-file format described here.
An alternative `RocksDbChunkStorageProvider` exists (a RocksDB database under
`<save>/db/`, chunk key = 8-byte big-endian `x,z`); not the default, not covered
here. A `MigrationChunkStorageProvider` chains backends for format migration.

## On-disk layout

```
<worldSavePath>/
  chunks/
    0.0.region.bin
    0.-1.region.bin
    -1.2.region.bin
    …
```

- Directory: `<worldSavePath>/chunks/`.
- File name: **`{regionX}.{regionZ}.region.bin`** (decimal, signed; produced by
  `IndexedStorageChunkStorageProvider.toFileName`).
- One **region = 32 × 32 chunk columns**:
  - `regionX = chunkX >> 5`, `regionZ = chunkZ >> 5`
  - `localX = chunkX & 31`, `localZ = chunkZ & 31`
- A region file with zero used chunks is deleted by `compact()`.

## Region-file binary format (`IndexedStorageFile`, VERSION 1)

A region file is a generic blob container: a fixed-size **blob index table**
plus a pool of fixed-size **segments**. Each blob = one chunk column.

**All multi-byte integers are big-endian** (Java `ByteBuffer` default; the class
never calls `.order()`). All offsets in bytes from file start.

### 1. Header — 32 bytes, offset 0

| Offset | Size | Field | Value |
|--------|------|-------|-------|
| 0  | 20 | magic | ASCII `"HytaleIndexedStorage"` |
| 20 | 4  | version | int32 = `1` (version 0 no longer supported) |
| 24 | 4  | blobCount | int32, default `1024` |
| 28 | 4  | segmentSize | int32, default `4096` |

`blobCount` is fixed at 1024 — one slot per chunk column in the 32×32 region.

### 2. Blob index table — `blobCount × 4` bytes, offset 32

`blobCount` consecutive int32 entries. Entry `i` = the **first segment index**
of blob `i`, or **`0` = blob unassigned** (that chunk column not stored).

Segment indices are **1-based** (`0` means "none"). The table is memory-mapped
by the server (`MappedByteBuffer`) and updated in place.

### 3. Segment pool — offset `segmentsBase`

```
segmentsBase = 32 + blobCount * 4          // = 4128 for blobCount 1024
segmentOffset(s)   = (s - 1) * segmentSize  // s is 1-based
segmentPosition(s) = segmentsBase + segmentOffset(s)
```

Segments are fixed `segmentSize` bytes. A blob occupies
`ceil((8 + compressedLength) / segmentSize)` **contiguous** segments. Free
segments are tracked by a `BitSet` and reused — **blobs are not stored in chunk
order**, and there are gaps. Never assume sequential layout; always go through
the index table.

### 4. Blob — at `segmentPosition(firstSegment)` of blob `i`

| Offset (within blob) | Size | Field |
|----------------------|------|-------|
| 0 | 4 | `srcLength` int32 — uncompressed payload length |
| 4 | 4 | `compressedLength` int32 — Zstd-compressed length |
| 8 | `compressedLength` | Zstd frame |

Decompress the Zstd frame (`com.github.luben.zstd.Zstd`, level 3 on write) to
exactly `srcLength` bytes → the **chunk payload** (see below).

### Blob index ↔ chunk coordinates

```
blobIndex = indexColumn(localX, localZ) = ((localZ & 31) << 5) | (localX & 31)   // 0..1023
localX    = blobIndex & 31
localZ    = (blobIndex >> 5) & 31
chunkX    = (regionX << 5) | localX
chunkZ    = (regionZ << 5) | localZ
```

## Chunk payload — the decompressed blob

The `srcLength` bytes are a **standard BSON document** (`org.bson`
`BsonBinaryWriter`, little-endian per the BSON spec — independent of the
big-endian container).

Produced by `BufferChunkSaver.saveHolder`:

```java
BsonDocument document = ChunkStore.REGISTRY.serialize(holder);   // ECS holder → BSON
ByteBuffer buffer     = ByteBuffer.wrap(BsonUtil.writeToBytes(document));
```

`ChunkStore.REGISTRY` is a `ComponentRegistry<ChunkStore>`: a chunk **column**
(32×32 footprint, 320 tall = 10 sections of 32³) is an ECS holder.

### The ChunkStore BSON document

A holder serializes (`ComponentRegistry.serialize` → `entityCodec`) to a
**single-field** document — `entityCodec` is an unversioned `BuilderCodec` with
one `KeyedCodec("Components", …)`:

```
{ "Components": { "<componentId>": <component-doc>, … } }
```

`Components` is a map keyed by the component's **registered id string**. The
ids that matter for a chunk column:

| Component | id | Codec |
|-----------|----|-------|
| `WorldChunk` | `"WorldChunk"` | `BuilderCodec` with **no fields** → `{}` |
| `ChunkColumn` | `"ChunkColumn"` | one field `"Sections"` |
| `ChunkSection` | `"ChunkSection"` | **no fields** → `{}` |
| `BlockSection` | **`"Block"`** | versioned 6, one field `"Data"` |
| `EntityChunk` / `BlockComponentChunk` | `"EntityChunk"` / `"BlockComponentChunk"` | entities / block-entities — omit for a geometry dump |

A column's identity (`x`,`z`) is **not stored** — `WorldChunk` and
`ChunkSection` have empty codecs; coordinates are positional, derived from the
region file + blob index on load. So the whole structural wrapper is empty
documents; the only real payload is the `BlockSection` `"Data"` binary.

A `BuilderCodec` encodes to `{ field: value, … }` keyed by each
`KeyedCodec`'s key; if the codec is `versioned` with a non-zero codecVersion it
also writes `"Version": <int>`. So:

```
column holder:
{ "Components": {
    "WorldChunk":  { },
    "ChunkColumn": { "Sections": [ <section holder>, … 10 entries ] } } }

section holder (one per Y-section 0..9):
{ "Components": {
    "ChunkSection": { },
    "Block":        { "Version": 6, "Data": <BsonBinary> } } }
```

`ChunkColumn.Sections` is a BSON array of **nested holder documents** — encoded
by the same `entityCodec` recursively (`StoredCodec` → `HOLDER_CODEC_KEY`). The
`"Data"` binary is the `BlockSection.serialize()` byte array decoded below.

The voxel data is one **`BlockSection` per Y-section**; a section that is solid
air may carry an `Empty`-palette `Data` or be absent.

### `BlockSection` component

`BlockSection.CODEC` is `versioned`, **codecVersion 6**. In BSON the component
is `{ "Data": <binary> }` — the binary is exactly `BlockSection.serialize()`.
That byte array is **not** BSON; it is a hand-rolled `MemorySegment` layout:

```
off 0    int32  BE   migrationVersion   (BlockMigration asset count at save time)
off 4    uint8       blockPalette.type  (0 Empty / 1 HalfByte / 2 Byte / 3 Short)
off 5    …           blockPalette       (keyed by block-type NAME — see below)
         if blockPalette.type != Empty:
           uint16 BE  tickingCount
           uint16 BE  tickingWordCount   N
           int64  BE × N   ticking BitSet words
         uint8       fillerPalette.type
         …           fillerPalette       (keyed by uint16 BE)
         uint8       rotationPalette.type
         …           rotationPalette     (keyed by uint8)
         …           localLight          (ChunkLightData octree)
         …           globalLight         (ChunkLightData octree)
         uint16 BE   localChangeCounter
         uint16 BE   globalChangeCounter
```

### Palette body

Each of the three palettes (block / filler / rotation) serializes the same way
(`AbstractSectionPalette.serialize`):

```
uint16 BE   paletteSize        (distinct entries)
repeat paletteSize:
   uint8    internalId
   <key>    external value     (encoded by the section's key serializer)
   uint16 BE count
<block array>                  blockArrayLen bytes, each cell = internalId
```

- **block array index** = `ChunkUtil.indexBlock(x,y,z) = (y&31)<<10 | (z&31)<<5 | (x&31)`.
- `Byte` palette → 32768-byte array (internalId 0..255).
- `HalfByte` palette → 16384-byte array, nibble-packed (internalId 0..15).
- `Short` palette → 65536 bytes (short array, internalId 0..65535).
- `Empty` palette → **no body at all**; the whole 32³ section is block id 0 (air).

Key serializers per palette:

| Palette | Key on disk |
|---------|-------------|
| block | block-type **name**, UTF-8 string (`MemorySegmentUtil.writeUTF` — length-prefixed; confirm the prefix in that helper) |
| filler | `uint16` BE — numeric filler id |
| rotation | `uint8` — rotation index (`RotationTuple`) |

`migrationVersion` lets a reader apply `BlockMigration` block renames; for a
recent save it is current and migrations are a no-op.

### Disk vs. network — the same palettes, different encoding

The wire `SetChunk` packet (id 131) carries the **same three palettes** for a
section in `SetChunk.data`, built by `BlockSection.serializeForPacket()`. The
proxy already decodes that variant (`ChunkDecoder` in meridian-core). The
on-disk variant differs only in encoding:

| | Disk (`serialize`) | Network (`serializeForPacket` / `SetChunk.data`) |
|--|--|--|
| palette size / counts | `uint16` **big-endian** | `int16` **little-endian** |
| block-palette key | UTF-8 **name** string | `int32` **little-endian** numeric id |
| per-entry layout | `internalId, key, count` | `internalId(1), id(4 LE), count(2 LE)` = 7 B |
| section framing | migration ver + 3 palettes + ticking + light + counters | just 3 palettes (`[type][palette]` ×3) |

So a region-file reader is the proxy's `ChunkDecoder` with two changes:
big-endian palette headers, and the block palette resolves to **names**, not
ids. Names are version-stable — a downloader needs no server id table.

`SetChunk` itself: `x/y/z` are **section** coordinates (per 32³ section, not per
column); `localLight`/`globalLight`/`data` are nullable byte arrays.

### Lighting (`ChunkLightData`)

`localLight` / `globalLight` are an **octree** (`serializeOctree`): `changeId`
`int16` BE, a 1-byte present flag, an `int32` BE octree length, then octree
nodes. Only needed for faithful lighting — a geometry dump ignores it.

## Read algorithm (for `meridian-world-downloader`)

```
for each file matching chunks/{rx}.{rz}.region.bin:
    read 32-byte header; assert magic == "HytaleIndexedStorage", version == 1
    read blobCount, segmentSize
    segmentsBase = 32 + blobCount*4
    read blobCount int32 → indexTable
    for i in 0 .. blobCount-1:
        seg = indexTable[i]; if seg == 0: continue          // empty column
        pos = segmentsBase + (seg-1)*segmentSize
        read srcLength, compressedLength at pos, pos+4
        read compressedLength bytes at pos+8
        bson = BSON.parse( Zstd.decompress(.., srcLength) )
        chunkX = (rx<<5) | (i & 31)
        chunkZ = (rz<<5) | ((i>>5) & 31)
        → emit (chunkX, chunkZ, bson)
```

No locking, no segment-free-list logic needed for a read-only tool — the index
table plus per-blob headers are self-describing.

## Write algorithm (to produce a Hytale-loadable save)

To make a save the real Hytale client/server opens, a writer must:

1. Build each section's `BlockSection.serialize()` byte array (layout above) —
   the format is now known; the remaining unknown is the **exact `ChunkStore`
   BSON document shape** (which component keys the versioned ECS `BuilderCodec`
   emits, and the minimum set a chunk needs to load).
2. Wrap into the `ChunkStore` BSON document, BSON-encode, Zstd-compress (level 3).
3. Lay it out: 32-byte header, 1024-entry index table, segment pool. Simplest
   correct strategy — assign segments sequentially, no reuse, no gaps; the
   server tolerates any valid segment placement.

A bare geometry dump (block names per position, no entities/lighting) is far
cheaper and does **not** need a loadable save — see the roadmap's
world-downloader entry.

## Notes / gotchas

- **Endianness split:** container ints big-endian; BSON payload little-endian.
- **Segment indices are 1-based**; `0` in the index table means "no blob".
- Blobs are scattered — iterate the index table, never the file linearly.
- `flushOnWrite` (config) forces fsync per write; irrelevant to a reader.
- Backend may be RocksDB instead — check for `<save>/db/` vs `<save>/chunks/`.
- The on-disk section format **is** the same three-palette structure as the
  wire `SetChunk` — confirmed. Differences: big-endian palette headers, and the
  block palette is keyed by name instead of numeric id.
- A `BlockSection` byte array uses **mixed endianness**: BSON little-endian,
  container big-endian, palette headers big-endian, but the wire `SetChunk`
  palette little-endian. Track which layer you are in.

## Next steps

The format is fully decoded — container, BSON document shape, and section
bytes. Remaining work is the `.mwd` &rarr; `.region.bin` converter:

1. Group captured `SetChunk` sections by column; per Y-section, re-encode the
   wire palette (LE, numeric ids) into the disk palette (BE, block names via
   the captured catalog) → a `BlockSection.serialize()` byte array.
2. Wrap: section holders → `ChunkColumn.Sections` → column holder → BSON,
   Zstd (level 3), blob into an `IndexedStorageFile` region.
3. Test-load: whether the server tolerates a column with only
   `WorldChunk` + `ChunkColumn` + `Block` sections (no `EntityChunk` /
   `BlockComponentChunk`); add empty ones if it rejects the chunk.
