#!/usr/bin/env python3
"""Makes the tag writer's device-test fixtures: six short sine tones with made-up tags, in the shapes KJB changes.

    FFMPEG=/path/to/ffmpeg python3 tools/make_tag_fixtures.py

ffmpeg encodes the audio only (libmp3lame, flac, aac, libvorbis, libopus); the tags are written here by hand, so they
come from a writer other than TagLib: every file carries a title, an artist, an album, an album artist, a year, a track
number, a comment, lyrics, ReplayGain and a back cover, as covers-tags-spec "What is written" asks of the round trip.

- paper-comet-v23.mp3: ID3v2.3 (UTF-16), plus an ID3v1 tag, which a write must leave as it is.
- lanterns-over-kelp-v24.mp3: ID3v2.4 (UTF-8), no ID3v1 tag, and a write must not add one.
- salt-clock.flac: Vorbis comments and a PICTURE block, after ffmpeg's STREAMINFO and SEEKTABLE.
- northbound-tide.m4a: ffmpeg's ilst (two covr images, front then back: M4A has no picture types) plus ReplayGain as
  iTunes freeform atoms, added here (moov is at the end, so no chunk offset moves).
- paper-comet-messy.ogg and quiet-breakwater.opus: Vorbis comments by ffmpeg, the back cover as METADATA_BLOCK_PICTURE.

Made-up names only. Output: app/src/androidTest/assets/tags/.
"""
import base64
import os
import struct
import subprocess
import sys
import tempfile

FFMPEG = os.environ.get("FFMPEG", "ffmpeg")
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "androidTest", "assets", "tags")

COMMON = {
    "ALBUM": "Glass Harbour Sessions",
    "ALBUMARTIST": "Mira Solen",
    "DATE": "2021",
    "COMMENT": "made up for KJB's tests",
    "LYRICS": "la la la\nlo lo lo",
    "REPLAYGAIN_TRACK_GAIN": "-6.20 dB",
    "REPLAYGAIN_TRACK_PEAK": "0.912345",
}


def run(*args):
    subprocess.run([FFMPEG, "-hide_banner", "-loglevel", "error", "-y", *args], check=True)


def tone(path, codec, rate, *extra):
    """Two seconds of a sine tone, encoded with no tags of ffmpeg's own."""
    run("-f", "lavfi", "-i", f"sine=frequency=523:duration=2:sample_rate={rate}", "-ac", "1", "-map_metadata", "-1",
        "-c:a", codec, *extra, path)


def image(path, colour):
    run("-f", "lavfi", "-i", f"color=c={colour}:s=48x48:d=1", "-frames:v", "1", path)
    with open(path, "rb") as f:
        return f.read()


# ID3v2 by hand

def syncsafe(n):
    return bytes([(n >> 21) & 0x7F, (n >> 14) & 0x7F, (n >> 7) & 0x7F, n & 0x7F])


def id3_text(version, text):
    if version == 3:
        return b"\x01" + "﻿".encode("utf-16-le") + text.encode("utf-16-le")
    return b"\x03" + text.encode("utf-8")


def id3_term(version):
    return b"\x00\x00" if version == 3 else b"\x00"


def id3_frame(version, frame_id, body):
    size = struct.pack(">I", len(body)) if version == 3 else syncsafe(len(body))
    return frame_id.encode() + size + b"\x00\x00" + body


def id3v2(version, fields, back):
    t = lambda text: id3_text(version, text)
    enc_text = lambda text: t(text)
    frames = [
        id3_frame(version, "TIT2", enc_text(fields["TITLE"])),
        id3_frame(version, "TPE1", enc_text(fields["ARTIST"])),
        id3_frame(version, "TALB", enc_text(fields["ALBUM"])),
        id3_frame(version, "TPE2", enc_text(fields["ALBUMARTIST"])),
        id3_frame(version, "TRCK", enc_text(fields["TRACKNUMBER"])),
        id3_frame(version, "TYER" if version == 3 else "TDRC", enc_text(fields["DATE"])),
        # COMM and USLT: encoding, language, an empty description, the text.
        id3_frame(version, "COMM", t("")[:1] + b"eng" + t("")[1:] + id3_term(version) + t(fields["COMMENT"])[1:]),
        id3_frame(version, "USLT", t("")[:1] + b"eng" + t("")[1:] + id3_term(version) + t(fields["LYRICS"])[1:]),
    ]
    for key in ("REPLAYGAIN_TRACK_GAIN", "REPLAYGAIN_TRACK_PEAK"):
        frames.append(id3_frame(version, "TXXX", t(key) + id3_term(version) + t(fields[key])[1:]))
    # APIC: Latin-1, MIME, type 4 (back cover), an empty description, the image.
    frames.append(id3_frame(version, "APIC", b"\x00image/jpeg\x00\x04\x00" + back))
    body = b"".join(frames) + b"\x00" * 256
    return b"ID3" + bytes([version, 0, 0]) + syncsafe(len(body)) + body


def id3v1(fields):
    def field(text, n):
        return text.encode("latin-1")[:n].ljust(n, b"\x00")
    track = int(fields["TRACKNUMBER"].split("/")[0])
    return (b"TAG" + field(fields["TITLE"], 30) + field(fields["ARTIST"], 30) + field(fields["ALBUM"], 30)
            + field(fields["DATE"], 4) + field("made up", 28) + b"\x00" + bytes([track, 255]))


def strip_id3(data):
    if data[:3] == b"ID3":
        size = 0
        for b in data[6:10]:
            size = (size << 7) | b
        data = data[10 + size:]
    if data[-128:-125] == b"TAG":
        data = data[:-128]
    return data


def mp3(name, version, fields, back, with_v1):
    with tempfile.TemporaryDirectory() as tmp:
        path = os.path.join(tmp, "a.mp3")
        tone(path, "libmp3lame", 44100, "-b:a", "64k", "-id3v2_version", "0", "-write_id3v1", "0")
        audio = strip_id3(open(path, "rb").read())
    data = id3v2(version, fields, back) + audio + (id3v1(fields) if with_v1 else b"")
    write(name, data)


# FLAC by hand

def flac_picture(picture_type, data):
    mime = b"image/jpeg"
    return (struct.pack(">I", picture_type) + struct.pack(">I", len(mime)) + mime + struct.pack(">I", 0)
            + struct.pack(">IIII", 48, 48, 24, 0) + struct.pack(">I", len(data)) + data)


def vorbis_comments(fields):
    vendor = b"KJB test fixture"
    items = [f"{k}={v}".encode("utf-8") for k, v in fields.items()]
    return (struct.pack("<I", len(vendor)) + vendor + struct.pack("<I", len(items))
            + b"".join(struct.pack("<I", len(i)) + i for i in items))


def flac(name, fields, back):
    with tempfile.TemporaryDirectory() as tmp:
        path = os.path.join(tmp, "a.flac")
        tone(path, "flac", 44100)
        data = open(path, "rb").read()
    assert data[:4] == b"fLaC"
    at, kept = 4, []
    while True:
        header = data[at]
        length = int.from_bytes(data[at + 1:at + 4], "big")
        if header & 0x7F in (0, 3):
            kept.append((header & 0x7F, data[at + 4:at + 4 + length]))
        at += 4 + length
        if header & 0x80:
            break
    blocks = kept + [(4, vorbis_comments(fields)), (6, flac_picture(4, back)), (1, b"\x00" * 512)]
    out = b"fLaC"
    for i, (kind, body) in enumerate(blocks):
        out += bytes([kind | (0x80 if i == len(blocks) - 1 else 0)]) + len(body).to_bytes(3, "big") + body
    write(name, out + data[at:])


# M4A: ffmpeg's tags, then ReplayGain as freeform atoms

def atom(kind, body):
    return struct.pack(">I", 8 + len(body)) + kind + body


def freeform(key, value):
    return atom(b"----", atom(b"mean", b"\x00" * 4 + b"com.apple.iTunes") + atom(b"name", b"\x00" * 4 + key.encode())
                + atom(b"data", struct.pack(">II", 1, 0) + value.encode()))


def m4a(name, fields, front, back):
    with tempfile.TemporaryDirectory() as tmp:
        cover_front, cover_back = os.path.join(tmp, "f.jpg"), os.path.join(tmp, "b.jpg")
        open(cover_front, "wb").write(front)
        open(cover_back, "wb").write(back)
        path = os.path.join(tmp, "a.m4a")
        run("-f", "lavfi", "-i", "sine=frequency=523:duration=2:sample_rate=44100", "-i", cover_front, "-i", cover_back,
            "-map", "0:a", "-map", "1:v", "-map", "2:v", "-ac", "1", "-map_metadata", "-1", "-c:a", "aac", "-b:a", "48k",
            "-c:v", "copy", "-disposition:v", "attached_pic",
            "-metadata", f"title={fields['TITLE']}", "-metadata", f"artist={fields['ARTIST']}",
            "-metadata", f"album={fields['ALBUM']}", "-metadata", f"album_artist={fields['ALBUMARTIST']}",
            "-metadata", f"date={fields['DATE']}", "-metadata", f"track={fields['TRACKNUMBER']}",
            "-metadata", f"comment={fields['COMMENT']}", "-metadata", f"lyrics={fields['LYRICS']}", path)
        data = bytearray(open(path, "rb").read())
    extra = freeform("REPLAYGAIN_TRACK_GAIN", fields["REPLAYGAIN_TRACK_GAIN"]) + freeform("REPLAYGAIN_TRACK_PEAK", fields["REPLAYGAIN_TRACK_PEAK"])

    def find(start, end, path):
        at = start
        while at < end:
            size, kind = struct.unpack(">I4s", data[at:at + 8])
            if kind == path[0]:
                if len(path) == 1:
                    return [at]
                body = at + 8 + (4 if kind == b"meta" else 0)
                return [at] + find(body, at + size, path[1:])
            at += size
        raise SystemExit(f"no {path[0]!r} in {name}")

    chain = find(0, len(data), [b"moov", b"udta", b"meta", b"ilst"])
    mdat = find(0, len(data), [b"mdat"])[0]
    assert mdat < chain[0], "moov must follow mdat, so no chunk offset moves"
    ilst = chain[-1]
    ilst_end = ilst + struct.unpack(">I", data[ilst:ilst + 4])[0]
    data[ilst_end:ilst_end] = extra
    for at in chain:
        size = struct.unpack(">I", data[at:at + 4])[0]
        data[at:at + 4] = struct.pack(">I", size + len(extra))
    write(name, bytes(data))


# Ogg Vorbis and Opus: ffmpeg's Vorbis comments

def ogg(name, codec, rate, fields, back, *extra):
    picture = base64.b64encode(flac_picture(4, back)).decode()
    tags = []
    for key, value in fields.items():
        tags += ["-metadata", f"{key}={value}"]
    with tempfile.TemporaryDirectory() as tmp:
        path = os.path.join(tmp, "a" + os.path.splitext(name)[1])
        run("-f", "lavfi", "-i", f"sine=frequency=523:duration=2:sample_rate={rate}", "-ac", "1", "-map_metadata", "-1",
            "-c:a", codec, *extra, *tags, "-metadata", f"METADATA_BLOCK_PICTURE={picture}", path)
        write(name, open(path, "rb").read())


def write(name, data):
    with open(os.path.join(OUT, name), "wb") as f:
        f.write(data)
    print(f"{name}: {len(data)} bytes")


def main():
    os.makedirs(OUT, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        front = image(os.path.join(tmp, "front.jpg"), "0xaa6633")
        back = image(os.path.join(tmp, "back.jpg"), "0x3366aa")

    def song(title, track, artist="Mira Solen"):
        return {"TITLE": title, "ARTIST": artist, "TRACKNUMBER": f"{track}/6", **COMMON}

    mp3("paper-comet-v23.mp3", 3, song("Paper Comet", 1), back, with_v1=True)
    mp3("lanterns-over-kelp-v24.mp3", 4, song("Lanterns Over Kelp", 2), back, with_v1=False)
    ogg("paper-comet-messy.ogg", "libvorbis", 44100, song("03 - paper comet (final)", 3), back, "-q:a", "0")
    flac("salt-clock.flac", song("Salt Clock", 4), back)
    m4a("northbound-tide.m4a", song("Northbound Tide", 5), front, back)
    ogg("quiet-breakwater.opus", "libopus", 48000, song("Quiet Breakwater", 6, artist="Mira Sollen"), back, "-b:a", "32k")


if __name__ == "__main__":
    sys.exit(main())
