#!/usr/bin/env python3
"""Makes audiobook mode's device-test fixtures: silent books with made-up chapters, in each shape KJB reads them from.

    FFMPEG=/path/to/ffmpeg python3 tools/make_book_fixtures.py

Needs mutagen (pip install mutagen). ffmpeg encodes silence only (mono, 22.05 kHz) and writes the book's tags and its
three chapters (0-4 s, 4-8 s, 8-12 s) from one FFMETADATA1 file; the only tags written by hand are the Ogg Vorbis
chapters, which ffmpeg's Ogg Vorbis muxer leaves out (it writes them for Opus). Bit-exact flags and no encoder tag,
so a rerun with the same ffmpeg gives the same bytes. Then every file is read back and checked, and the script exits
non-zero on any mismatch.

The book is "Lantern Keeper", chapters "Chapter One" to "Chapter Three", 12 s. Every file is by Ada Quill, genre
Audiobook.

- lantern-both.m4b: ffmpeg -f ipod writes both kinds of MP4 chapters: a Nero chpl box (moov/udta/chpl) and a
  QuickTime chapter text track, referenced by moov/trak/tref/chap.
- lantern-chpl-only.m4b: lantern-both.m4b with its chap box renamed free (a same-length rename keeps every box size
  valid): chpl only. The text track stays in the file, unreferenced.
- lantern-qt-only.m4b: lantern-both.m4b with its chpl box renamed free: QuickTime chapters only, the shape real
  LibriVox m4b files have. Mutagen reads no chapters from it (it reads chpl only); ffmpeg reads the text track.
- lantern-no-chapters.m4b: the same book's tags and audio, no chapters of either kind.
- lantern-chap.mp3: ID3v2.3 with three CHAP frames (ch0, ch1, ch2, each with a TIT2 title) and one CTOC (toc,
  children ch0 ch1 ch2).
- lantern-chapters.opus: CHAPTER000 to CHAPTER002 and CHAPTER000NAME to CHAPTER002NAME, as ffmpeg numbers them (from
  000; KJB's parser must accept this and the usual numbering from 001).
- lantern-chapters.ogg: Ogg Vorbis, with CHAPTER001 to CHAPTER003 and their NAME tags added here with mutagen.
- plain-book.mp3: "Quiet Orchard", 12 s, no album, no CHAP or CTOC frame at all.
- folder-book/01.mp3, 02.mp3, 10.mp3: album "Folder Book", "Part One", "Part Two", "Part Ten", track tags 1, 2, 10,
  4 s each (the names a ripper makes, to test natural order).
- no-track-book/2.mp3, 10.mp3, 1.mp3: album "No Track Book", "Part 2", "Part 10", "Part 1", no track tags, 4 s each
  (the order must come from the names: 1, 2, 10).

Made-up names only. Output: app/src/androidTest/assets/books/.
"""
import os
import struct
import subprocess
import sys
import tempfile

from mutagen.id3 import ID3
from mutagen.mp3 import MP3
from mutagen.mp4 import MP4
from mutagen.oggopus import OggOpus
from mutagen.oggvorbis import OggVorbis

FFMPEG = os.environ.get("FFMPEG", "ffmpeg")
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "androidTest", "assets", "books")

BOOK = {"title": "Lantern Keeper", "artist": "Ada Quill", "album": "Lantern Keeper", "genre": "Audiobook"}
CHAPTERS = [(0, 4000, "Chapter One"), (4000, 8000, "Chapter Two"), (8000, 12000, "Chapter Three")]
AUTHOR = {"artist": "Ada Quill", "genre": "Audiobook"}
# Files with tags and no chapters: (name, seconds, tags), the tags as ffmpeg -metadata keys.
TAGGED = [("plain-book.mp3", 12, {"title": "Quiet Orchard", **AUTHOR})]
TAGGED += [(f"folder-book/{file}.mp3", 4, {"album": "Folder Book", "title": title, "track": track, **AUTHOR})
           for file, title, track in [("01", "Part One", "1"), ("02", "Part Two", "2"), ("10", "Part Ten", "10")]]
TAGGED += [(f"no-track-book/{file}.mp3", 4, {"album": "No Track Book", "title": title, **AUTHOR})
           for file, title in [("2", "Part 2"), ("10", "Part 10"), ("1", "Part 1")]]
ID3_FRAMES = {"title": "TIT2", "artist": "TPE1", "album": "TALB", "genre": "TCON", "track": "TRCK"}

# Same bytes on a rerun: no random Ogg serials, no version strings, no per-stream encoder tag.
SAME = ["-fflags", "+bitexact", "-flags:a", "+bitexact", "-metadata:s:a:0", "encoder="]
AAC = ["-c:a", "aac", "-b:a", "16k", "-f", "ipod"]
LAME = ["-c:a", "libmp3lame", "-b:a", "16k"]

CHAP_PATH = "moov/trak/tref/chap"
CHPL_PATH = "moov/udta/chpl"
CONTAINERS = {b"moov", b"trak", b"tref", b"udta", b"mdia", b"minf", b"stbl", b"edts", b"dinf", b"meta"}

PROBLEMS = []


def run(*args):
    subprocess.run([FFMPEG, "-hide_banner", "-loglevel", "error", "-y", *args], check=True)


def path(name):
    return os.path.join(OUT, name)


def silence(seconds):
    """-t comes before -i, as an input option: after it, anullsrc never ends."""
    return ["-f", "lavfi", "-t", str(seconds), "-i", "anullsrc=r=22050:cl=mono"]


def stamp(ms):
    return f"{ms // 3_600_000:02d}:{ms // 60_000 % 60:02d}:{ms // 1000 % 60:02d}.{ms % 1000:03d}"


def ffmeta():
    text = ";FFMETADATA1\n" + "".join(f"{key}={value}\n" for key, value in BOOK.items())
    for start, end, title in CHAPTERS:
        text += f"[CHAPTER]\nTIMEBASE=1/1000\nSTART={start}\nEND={end}\ntitle={title}\n"
    return text


def vorbis_chapters(first):
    """The Vorbis comment chapter tags, numbered from first: CHAPTER001=00:00:04.000, CHAPTER001NAME=Chapter Two."""
    tags = {}
    for i, (start, _, title) in enumerate(CHAPTERS, first):
        tags[f"CHAPTER{i:03d}"] = stamp(start)
        tags[f"CHAPTER{i:03d}NAME"] = title
    return tags


def from_meta(name, meta, *codec):
    """12 s of silence with the book's tags and, unless codec has -map_chapters -1, its chapters."""
    run(*silence(12), "-i", meta, "-map", "0:a", "-map_metadata", "1", *codec, *SAME, path(name))


def tagged_mp3(name, seconds, tags):
    """An MP3 of silence with these tags and nothing else."""
    args = []
    for key, value in tags.items():
        args += ["-metadata", f"{key}={value}"]
    run(*silence(seconds), "-map_metadata", "-1", *LAME, *args, *SAME, path(name))


# MP4 boxes

def boxes(data, start=0, end=None, prefix=""):
    """Every box on the way down to chap and chpl, as (path, offset): moov/trak/tref/chap, moov/udta/chpl, ..."""
    end = len(data) if end is None else end
    found = []
    while start < end:
        size, kind = struct.unpack(">I4s", data[start:start + 8])
        if size < 8 or start + size > end:
            raise SystemExit(f"bad box size {size} at {start}")  # 0 (to the end) and 1 (64-bit) never occur here
        box = prefix + kind.decode("latin-1")
        found.append((box, start))
        if kind in CONTAINERS:
            found += boxes(data, start + 8 + (4 if kind == b"meta" else 0), start + size, box + "/")
        start += size
    return found


def renamed(src, name, box, new):
    """A copy of src with the one box at this path given another type of the same length, so no size changes."""
    data = bytearray(open(path(src), "rb").read())
    kind = box.split("/")[-1]
    same = [(found, offset) for found, offset in boxes(data) if found.split("/")[-1] == kind]
    if [found for found, _ in same] != [box]:
        raise SystemExit(f"{src}: expected one {kind} box, at {box}, found {[found for found, _ in same]}")
    offset = same[0][1]
    data[offset + 4:offset + 8] = new
    with open(path(name), "wb") as f:
        f.write(data)


def ffmpeg_chapters(name):
    """The chapters ffmpeg reads back (chpl, or else the QuickTime text track), as (start ms, title)."""
    out = subprocess.run([FFMPEG, "-hide_banner", "-loglevel", "error", "-i", path(name), "-f", "ffmetadata", "-"],
                         check=True, capture_output=True, text=True).stdout
    chapters = []
    for block in out.split("[CHAPTER]")[1:]:
        fields = dict(line.split("=", 1) for line in block.strip().splitlines() if "=" in line)
        num, den = (int(n) for n in fields["TIMEBASE"].split("/"))
        chapters.append((round(int(fields["START"]) * num * 1000 / den), fields.get("title")))
    return chapters


# Checks

def expect(name, what, got, want):
    if got != want:
        PROBLEMS.append(f"{name}: {what}: got {got!r}, expected {want!r}")
    return what


def expect_length(name, info, seconds):
    if abs(info.length - seconds) >= 0.25:
        PROBLEMS.append(f"{name}: lasts {info.length:.2f} s, expected {seconds} s")
    return f"{seconds} s"


def check_m4b(name, chpl, chap):
    found = {box for box, _ in boxes(open(path(name), "rb").read())}
    starts = [(start, title) for start, _, title in CHAPTERS]
    m4b = MP4(path(name))
    tags = m4b.tags or {}
    by_mutagen = [(round(c.start * 1000), c.title) for c in (m4b.chapters or [])]
    by_ffmpeg = ffmpeg_chapters(name)
    return [
        expect(name, "chpl " + ("present" if chpl else "absent"), CHPL_PATH in found, chpl),
        expect(name, "chap " + ("present" if chap else "absent"), CHAP_PATH in found, chap),
        expect(name, f"Mutagen {len(by_mutagen)} chapters", by_mutagen, starts if chpl else []),
        expect(name, f"ffmpeg {len(by_ffmpeg)} chapters", by_ffmpeg, starts if chpl or chap else []),
        expect(name, "title, artist, genre", [tags.get(k) for k in ("©nam", "©ART", "©gen")],
               [[BOOK["title"]], [BOOK["artist"]], [BOOK["genre"]]]),
        expect_length(name, m4b.info, 12),
    ]


def text(id3, frame_id):
    frame = id3.get(frame_id)
    return None if frame is None else str(frame)


def check_chap_mp3(name):
    id3 = ID3(path(name))
    chaps = [(f.element_id, f.start_time, f.end_time, text(f.sub_frames, "TIT2")) for f in id3.getall("CHAP")]
    tocs = [(f.element_id, f.child_element_ids) for f in id3.getall("CTOC")]
    return [
        expect(name, "ID3v2.3", id3.version[:2], (2, 3)),
        expect(name, "3 CHAP (ms, TIT2)", chaps, [(f"ch{i}", s, e, t) for i, (s, e, t) in enumerate(CHAPTERS)]),
        expect(name, "1 CTOC toc: ch0 ch1 ch2", tocs, [("toc", ["ch0", "ch1", "ch2"])]),
        expect(name, "title, genre", [text(id3, "TIT2"), text(id3, "TCON")], [BOOK["title"], BOOK["genre"]]),
        expect_length(name, MP3(path(name)).info, 12),
    ]


def check_ogg(name, opener, first):
    ogg = opener(path(name))
    chapters = {key.upper(): value for key, value in ogg.tags if key.upper().startswith("CHAPTER")}
    return [
        expect(name, f"CHAPTER{first:03d}..{first + 2:03d} and NAME", chapters, vorbis_chapters(first)),
        expect(name, "title, genre", [ogg.tags.get(k) for k in ("title", "genre")], [[BOOK["title"]], [BOOK["genre"]]]),
        expect_length(name, ogg.info, 12),
    ]


def check_tagged_mp3(name, seconds, tags):
    id3 = ID3(path(name))
    return [
        expect(name, "no CHAP or CTOC", id3.getall("CHAP") + id3.getall("CTOC"), []),
        expect(name, "tags " + " ".join(f for k, f in ID3_FRAMES.items() if k in tags),
               {f: text(id3, f) for f in ID3_FRAMES.values()}, {f: tags.get(k) for k, f in ID3_FRAMES.items()}),
        expect_length(name, MP3(path(name)).info, seconds),
    ]


def main():
    os.makedirs(os.path.join(OUT, "folder-book"), exist_ok=True)
    os.makedirs(os.path.join(OUT, "no-track-book"), exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        meta = os.path.join(tmp, "chapters.ffmeta")
        with open(meta, "w") as f:
            f.write(ffmeta())
        from_meta("lantern-both.m4b", meta, *AAC)
        from_meta("lantern-no-chapters.m4b", meta, "-map_chapters", "-1", *AAC)
        from_meta("lantern-chap.mp3", meta, *LAME, "-id3v2_version", "3")
        from_meta("lantern-chapters.opus", meta, "-c:a", "libopus", "-b:a", "16k", "-ar", "48000")
        from_meta("lantern-chapters.ogg", meta, "-c:a", "libvorbis", "-q:a", "0")
    renamed("lantern-both.m4b", "lantern-chpl-only.m4b", CHAP_PATH, b"free")
    renamed("lantern-both.m4b", "lantern-qt-only.m4b", CHPL_PATH, b"free")
    ogg = OggVorbis(path("lantern-chapters.ogg"))
    ogg.update(vorbis_chapters(1))
    ogg.save()
    for name, seconds, tags in TAGGED:
        tagged_mp3(name, seconds, tags)

    checked = [
        ("lantern-both.m4b", check_m4b("lantern-both.m4b", chpl=True, chap=True)),
        ("lantern-chpl-only.m4b", check_m4b("lantern-chpl-only.m4b", chpl=True, chap=False)),
        ("lantern-qt-only.m4b", check_m4b("lantern-qt-only.m4b", chpl=False, chap=True)),
        ("lantern-no-chapters.m4b", check_m4b("lantern-no-chapters.m4b", chpl=False, chap=False)),
        ("lantern-chap.mp3", check_chap_mp3("lantern-chap.mp3")),
        ("lantern-chapters.opus", check_ogg("lantern-chapters.opus", OggOpus, 0)),
        ("lantern-chapters.ogg", check_ogg("lantern-chapters.ogg", OggVorbis, 1)),
    ]
    checked += [(name, check_tagged_mp3(name, seconds, tags)) for name, seconds, tags in TAGGED]

    for name, whats in checked:
        print(f"{name}: {os.path.getsize(path(name))} bytes; checked {', '.join(whats)}")
    for problem in PROBLEMS:
        print(f"MISMATCH {problem}", file=sys.stderr)
    return 1 if PROBLEMS else 0


if __name__ == "__main__":
    sys.exit(main())
