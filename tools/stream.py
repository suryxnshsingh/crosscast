"""Stream an audio file to AirSpeaker over classic AirPlay, with metadata and optional artwork.

Uses pyatv (pip install pyatv). Discovery is skipped because pyatv can't see services
published by Android's mDNS; the receiver is addressed directly.

    python tools/stream.py --host 192.168.29.48 --id 1E2E5EB64A10 song.wav --cover cover.jpg --title "Song"
"""
import argparse
import asyncio
from ipaddress import IPv4Address

import pyatv
from pyatv.conf import AppleTV, ManualService
from pyatv.const import Protocol
from pyatv.interface import MediaMetadata

# The TXT records the receiver advertises (see RaopService.kt).
PROPERTIES = dict(txtvers="1", ch="2", cn="0,1", et="0,1", ek="1", sr="44100", ss="16", tp="UDP",
                  vn="65537", vs="105.1", md="0,1,2", am="AirSpeaker1,1", da="true", sv="false", sf="0x4", pw="false")


async def main(args):
    conf = AppleTV(IPv4Address(args.host), "AirSpeaker")
    conf.add_service(ManualService(args.id, Protocol.RAOP, args.port, PROPERTIES))
    atv = await pyatv.connect(conf, asyncio.get_running_loop())
    try:
        artwork = open(args.cover, "rb").read() if args.cover else None
        metadata = MediaMetadata(title=args.title, artist=args.artist, album=args.album, artwork=artwork)
        await atv.stream.stream_file(args.file, metadata=metadata)
    finally:
        atv.close()


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("file")
    p.add_argument("--host", default="192.168.29.48")
    p.add_argument("--port", type=int, default=5000)
    p.add_argument("--id", default="1E2E5EB64A10", help="device ID: the part before @ in the Bonjour name")
    p.add_argument("--cover")
    p.add_argument("--title", default="Test stream")
    p.add_argument("--artist", default="AirSpeaker")
    p.add_argument("--album", default="Tools")
    asyncio.run(main(p.parse_args()))
