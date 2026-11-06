# forgebyte-nearby

Offline play for ForgeByte card games (Crazy Eights Party, Satti) on the same Wi-Fi or a phone hotspot.
One phone hosts the room and referees the game; the others find it on the local network and join. No internet.

Status: planning and scaffold. Plan and test plan: [docs/PLAN.md](docs/PLAN.md).

Privacy: `RoomConfig.roomName` goes unencrypted to the local network in the discovery advert, so never put a player
name in it. Everything after joining (names, moves, views) is encrypted end to end between the phones.
A join with the full code (`JoinCode`, QR or typed) gets a seat with no host approval and no check code, until the
game starts: share the full code only in person or by QR.
A short code (`ShortCode`, six characters such as `K7Q-2MX`) only finds the host on the same Wi-Fi and holds no
secret, so a short-code join always waits for the host's Accept and shows the check code on both phones.
