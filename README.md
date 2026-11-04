# forgebyte-nearby

Offline play for ForgeByte card games (Crazy Eights Party, Satti) on the same Wi-Fi or a phone hotspot.
One phone hosts the room and referees the game; the others find it on the local network and join. No internet.

Status: planning and scaffold. Plan and test plan: [docs/PLAN.md](docs/PLAN.md).

Privacy: `RoomConfig.roomName` goes unencrypted to the local network in the discovery advert, so never put a player
name in it. Everything after joining (names, moves, views) is encrypted end to end between the phones.
