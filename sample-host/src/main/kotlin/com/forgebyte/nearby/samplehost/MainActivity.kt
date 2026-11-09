package com.forgebyte.nearby.samplehost

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.forgebyte.nearby.JoinCode
import com.forgebyte.nearby.Lobby
import com.forgebyte.nearby.NearbyClient
import com.forgebyte.nearby.NearbyAndroid
import com.forgebyte.nearby.NearbyHost
import com.forgebyte.nearby.NearbyTrace
import com.forgebyte.nearby.RoomConfig
import com.forgebyte.nearby.SavedSeat
import com.forgebyte.nearby.SeatStore
import com.forgebyte.nearby.ShortCode
import com.forgebyte.nearby.demo.DemoGame
import com.forgebyte.nearby.discoverRooms
import com.forgebyte.nearby.demo.DemoMove
import com.forgebyte.nearby.demo.DemoView
import com.forgebyte.nearby.demo.playAsJoiner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.security.SecureRandom

/** Hosts a demo room (or joins one), logs everything to the screen and to logcat (tag NearbyDemo). */
class MainActivity : Activity() {
    private val scope = MainScope()
    private var host: NearbyHost<*, DemoMove, *>? = null
    private var client: NearbyClient<DemoMove, DemoView>? = null
    private lateinit var log: TextView
    private lateinit var header: TextView
    private var shownCode = "" // the host's first short code, for the header

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The library's connect and drop lines, for device runs (adb logcat -s NearbyTrace:I); this app is test-only.
        NearbyTrace.sink = { Log.i("NearbyTrace", it) }
        // A big status line on top (room, code, seats, phase, hand) so a screenshot pair shows the same room on both phones.
        header = TextView(this).apply { setPadding(32, 32, 32, 16); textSize = 20f; setTypeface(typeface, android.graphics.Typeface.BOLD) }
        log = TextView(this).apply { setPadding(32, 16, 32, 32) }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(ScrollView(this@MainActivity).apply { addView(log) })
        })

        NearbyAndroid.init(this) // nearby play opens here; nothing ran at app start
        // `--es join <code>`, `--es short <XXX-XXX>` or `--ez find true`: play as a joiner (by the full code, by the short
        // code the host shows, or the first room found nearby) instead of hosting; `--ei think <ms>` waits before each own
        // move (default 700); `--es name <text>` is this phone's player name (default the phone model); with find,
        // `--es findRoom <name>` joins only the room of that name (it logs every room it sees, with its address).
        // Host only: `--es room <text>` room name, `--ei seats <n>` (default 4), `--ei turn <ms>` turn timer (0: off;
        // default the library's), `--ez hold true` waits in the lobby until `--ez start true`, `--ez approveCode true`
        // makes full-code joins wait for the host too (RoomConfig.approveCodeJoins), `--ez decline true` declines every
        // request instead of accepting it.
        // Either side: `--ez leave true` leaves (see onNewIntent).
        val name = intent.getStringExtra("name")
        val joinCode = intent.getStringExtra("join")
        val shortText = intent.getStringExtra("short")
        val findRoom = intent.getStringExtra("findRoom")
        // `--ez resume true`: take back the seat saved before this app was killed (kept in private storage, out of
        // backups and device transfer: see res/xml).
        val resume = intent.getBooleanExtra("resume", false)
        if (joinCode != null || shortText != null || findRoom != null || resume || intent.getBooleanExtra("find", false)) {
            val parsed = joinCode?.let(JoinCode::parse)
            val short = shortText?.let(ShortCode::parse)
            scope.launch {
                if (joinCode != null && parsed == null) return@launch say("FAIL bad join code $joinCode")
                if (shortText != null && short == null) return@launch say("FAIL bad short code $shortText")
                val ok = playAsJoiner(parsed, name ?: Build.MODEL, intent.getIntExtra("think", 700).toLong(), short, findRoom,
                    seatStore = PrefsSeatStore(this@MainActivity), resume = resume, onClient = { c ->
                    client = c
                    launch { combine(c.lobby, c.seqView, c.status) { l, sv, st -> show(l, sv?.view, "JOINER  $st") }.collect {} }
                }, say = ::say)
                say("joiner ${if (ok) "PASS" else "FAIL"}")
            }
            return
        }
        val code = (100_000 + SecureRandom().nextInt(900_000)).toString()
        val room = NearbyHost(
            DemoGame(),
            RoomConfig(
                roomName = intent.getStringExtra("room") ?: "Nearby test ${Build.MODEL}", hostName = name ?: "Host",
                gameVersion = DemoGame.VERSION, roomSecret = code, maxSeats = intent.getIntExtra("seats", 4),
                turnTimeoutMs = intent.getIntExtra("turn", 30_000).toLong(), // 30 s is the library default
                approveCodeJoins = intent.getBooleanExtra("approveCode", false),
            ),
            scope,
        )
        host = room
        scope.launch {
            val port = room.open()
            say("listening on port $port, join codes ${room.joinCodes(port)}, short codes ${room.shortCodes(port)}")
            shownCode = room.shortCodes(port).firstOrNull()?.toString() ?: ""
            launch { combine(room.lobby, room.seqView) { l, sv -> show(l, sv?.view, "HOST") }.collect {} }
            launch { room.advertise(port) }
            // `--ez bot true`: one bot seat, so a match tests bot pacing (botDelayMs); it starts once a joiner is in too.
            val bots = if (intent.getBooleanExtra("bot", false)) 1 else 0
            hold = intent.getBooleanExtra("hold", false)
            if (bots > 0) room.addBot()
            // `--ez discover true`: also run Android discovery (NSD) and log what it finds, to test that path on a phone.
            if (intent.getBooleanExtra("discover", false)) launch {
                discoverRooms(DemoGame.ID).collect { rooms ->
                    rooms.forEach { say("discovered '${it.roomName}' at ${it.host}:${it.port}, free ${it.seatsFree}, canJoin ${it.canJoin(DemoGame.VERSION)}") }
                }
            }
            val decline = intent.getBooleanExtra("decline", false)
            launch {
                room.joinRequests.collect { list ->
                    list.forEach {
                        say("request ${it.id} from ${it.name}, check code ${it.checkCode}: ${if (decline) "declining" else "accepting"}")
                        if (decline) room.declineJoin(it.id) else room.acceptJoin(it.id)
                    }
                }
            }
            launch {
                room.lobby.collect { l -> say("lobby ${l.phase} ${l.seats.map { "${it.name}:${it.kind}:${if (it.connected) "on" else "off"}" }}") }
            }
            launch {
                room.canStart.collect { ready ->
                    if (ready && !hold && room.lobby.value.seats.size >= 2 + bots) {
                        say("starting")
                        room.startGame()
                    }
                }
            }
            launch {
                var lastTurn = -1
                room.seqView.filterNotNull().collect { (seq, v) ->
                    // With a bot seat, log each turn change: the bot is seat 1 (added before any joiner), so logcat time from "turn 1" to the next line is its pace.
                    if (bots > 0 && v.turn != lastTurn) { lastTurn = v.turn; say("turn ${v.turn}") }
                    if (v.turn == 0 && v.hand.isNotEmpty()) {
                        delay(100) // like a player's tap, so joiners see each step
                        room.move(DemoMove(v.hand.first()), seenSeq = seq)
                    }
                }
            }
        }
    }

    private var hold = false

    /**
     * `adb shell am start -n com.forgebyte.nearby.samplehost/.MainActivity --ez leave true` ends the room (host) or
     * leaves it (joiner); `--ez start true` starts a room held in the lobby by `--ez hold true`.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("leave", false)) scope.launch {
            val t0 = android.os.SystemClock.elapsedRealtime()
            val h = host
            if (h != null) h.leave() else client?.leave()
            say("${if (h != null) "host" else "joiner"} left the room in ${android.os.SystemClock.elapsedRealtime() - t0} ms")
        }
        if (intent.getBooleanExtra("start", false)) scope.launch {
            hold = false
            say("starting (held room)")
            host?.startGame()
        }
    }

    /** The header: who I am, the room and its short code, the seats (on/off), the phase and my hand. */
    private fun show(l: Lobby?, v: DemoView?, me: String) {
        val seats = l?.seats?.mapIndexed { i, s -> "${if (i == l.yourSeat) "*" else ""}${s.name}:${if (s.connected) "on" else "off"}" }
        val text = buildString {
            append(me)
            if (shownCode.isNotEmpty()) append("  code ").append(shownCode)
            append('\n').append(l?.roomName ?: "no room yet").append("  ").append(l?.phase ?: "")
            append('\n').append("seats ").append(seats?.joinToString(", ") ?: "-")
            if (v != null) append('\n').append("turn seat ${v.turn}  my hand ${v.hand.size}  pile top ${v.pile.lastOrNull() ?: "-"}")
        }
        runOnUiThread { header.text = text }
    }

    override fun onDestroy() {
        super.onDestroy()
        val s = scope
        CoroutineScope(Dispatchers.Default).launch {
            host?.leave()
            s.cancel()
        }
    }

    private fun say(text: String) {
        Log.i("NearbyDemo", text)
        runOnUiThread { log.append(text + "\n") } // the joiner reports from background threads
    }
}

/** The saved seat in private SharedPreferences (nearby_seat.xml, excluded from backup and device transfer). */
private class PrefsSeatStore(context: android.content.Context) : SeatStore {
    private val prefs = context.getSharedPreferences("nearby_seat", android.content.Context.MODE_PRIVATE)
    override fun load(): SavedSeat? = prefs.getString("seat", null)?.let(SavedSeat::parse)
    // commit, not apply: the app may be killed right after a welcome, and the seat must already be on disk.
    override fun save(seat: SavedSeat) { prefs.edit().putString("seat", seat.toText()).commit() }
    override fun clear() { prefs.edit().remove("seat").commit() }
}
