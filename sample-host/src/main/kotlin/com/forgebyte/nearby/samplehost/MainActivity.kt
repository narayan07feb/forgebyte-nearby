package com.forgebyte.nearby.samplehost

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import com.forgebyte.nearby.NearbyAndroid
import com.forgebyte.nearby.NearbyHost
import com.forgebyte.nearby.RoomConfig
import com.forgebyte.nearby.demo.DemoGame
import com.forgebyte.nearby.discoverRooms
import com.forgebyte.nearby.demo.DemoMove
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.security.SecureRandom

/** Hosts a demo room, logs everything to the screen and to logcat (tag NearbyDemo). */
class MainActivity : Activity() {
    private val scope = MainScope()
    private lateinit var host: NearbyHost<*, DemoMove, *>
    private lateinit var log: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        log = TextView(this).apply { setPadding(32, 32, 32, 32) }
        setContentView(ScrollView(this).apply { addView(log) })

        NearbyAndroid.init(this) // nearby play opens here; nothing ran at app start
        val code = (100_000 + SecureRandom().nextInt(900_000)).toString()
        val room = NearbyHost(
            DemoGame(),
            RoomConfig(roomName = "Nearby test ${Build.MODEL}", hostName = "Host", gameVersion = DemoGame.VERSION,
                roomSecret = code, maxSeats = 4),
            scope,
        )
        host = room
        scope.launch {
            val port = room.open()
            say("listening on port $port, join codes ${room.joinCodes(port)}")
            launch { room.advertise(port) }
            // `--ez bot true`: one bot seat, so a match tests bot pacing (botDelayMs); it starts once a joiner is in too.
            val bots = if (intent.getBooleanExtra("bot", false)) 1 else 0
            if (bots > 0) room.addBot()
            // `--ez discover true`: also run Android discovery (NSD) and log what it finds, to test that path on a phone.
            if (intent.getBooleanExtra("discover", false)) launch {
                discoverRooms(DemoGame.ID).collect { rooms ->
                    rooms.forEach { say("discovered '${it.roomName}' at ${it.host}:${it.port}, free ${it.seatsFree}, canJoin ${it.canJoin(DemoGame.VERSION)}") }
                }
            }
            launch {
                room.joinRequests.collect { list ->
                    list.forEach { say("request ${it.id} from ${it.name}, check code ${it.checkCode}: accepting"); room.acceptJoin(it.id) }
                }
            }
            launch {
                room.lobby.collect { l -> say("lobby ${l.phase} ${l.seats.map { "${it.name}:${it.kind}:${if (it.connected) "on" else "off"}" }}") }
            }
            launch {
                room.canStart.collect { ready ->
                    if (ready && room.lobby.value.seats.size >= 2 + bots) {
                        say("starting")
                        room.startGame()
                    }
                }
            }
            launch {
                var lastTurn = -1
                room.view.filterNotNull().collect { v ->
                    // With a bot seat, log each turn change: the bot is seat 1 (added before any joiner), so logcat time from "turn 1" to the next line is its pace.
                    if (bots > 0 && v.turn != lastTurn) { lastTurn = v.turn; say("turn ${v.turn}") }
                    if (v.turn == 0 && v.hand.isNotEmpty()) {
                        delay(100) // like a player's tap, so joiners see each step
                        room.move(DemoMove(v.hand.first()))
                    }
                }
            }
        }
    }

    /** `adb shell am start -n com.forgebyte.nearby.samplehost/.MainActivity --ez leave true` ends the room. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("leave", false)) scope.launch {
            val t0 = android.os.SystemClock.elapsedRealtime()
            host.leave()
            say("host left the room in ${android.os.SystemClock.elapsedRealtime() - t0} ms")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val s = scope
        CoroutineScope(Dispatchers.Default).launch {
            host.leave()
            s.cancel()
        }
    }

    private fun say(text: String) {
        Log.i("NearbyDemo", text)
        log.append(text + "\n")
    }
}
