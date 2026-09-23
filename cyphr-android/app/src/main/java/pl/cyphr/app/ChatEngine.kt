package pl.cyphr.app

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors

/**
 * Rozmowy i odpowiedzi modelu zyja poza ekranem. Wczesniej odpowiedz liczyla sie
 * w korutynie ekranu, wiec wyjscie z aplikacji na dluzej, blokada odciskiem albo
 * odtworzenie ekranu urywaly ja w polowie. Teraz praca idzie w zakresie calej aplikacji,
 * [ChatService] trzyma proces przy zyciu, a wynik trafia na dysk i na ekran, jesli jest
 * otwarty. Zgoda na polecenie modelu czeka na uzytkownika — model nie ma drogi na skroty.
 *
 * Stan zmienia sie wylacznie na glownym watku. Dysk ma jeden wlasny watek, wiec zapisy
 * i odczyty ida po kolei — odczyt widzi wszystko, co zapisano przed nim.
 */
object ChatEngine {
    /** Tyle najdluzej czeka zgoda na polecenie, gdy nikt nie wraca do aplikacji. */
    private const val APPROVAL_TIMEOUT_MS = 10 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Jeden watek dysku — kolejne wersje rozmowy trafiaja na dysk w kolejnosci. */
    private val disk = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    private lateinit var app: Context

    private val _chats = MutableStateFlow(listOf(Chat()))
    val chats: StateFlow<List<Chat>> = _chats

    private val _activeId = MutableStateFlow(_chats.value.first().id)
    val activeId: StateFlow<String> = _activeId

    /** Rozmowy, w ktorych model wlasnie odpowiada. */
    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy: StateFlow<Set<String>> = _busy

    /** Rzeczywiste zuzycie ostatniej wymiany: wejscie do wyjscia. */
    private val _lastTokens = MutableStateFlow<Pair<Int, Int>?>(null)
    val lastTokens: StateFlow<Pair<Int, Int>?> = _lastTokens

    /** Polecenie, o ktore prosi model w rozmowie [chat], czekajace na zgode uzytkownika. */
    class Approval(val chat: String, val command: String, internal val answer: CompletableDeferred<Boolean>)

    private val _approval = MutableStateFlow<Approval?>(null)
    val approval: StateFlow<Approval?> = _approval
    private val approvals = Mutex()

    sealed interface Event {
        /** Komunikat dla ekranu konta [uid], np. blad odpowiedzi. */
        class Message(val uid: Long, val text: String) : Event
        /** Swieze dane konta po odpowiedzi — saldo sie zmienilo. */
        class Account(val user: User) : Event
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 16)
    val events: SharedFlow<Event> = _events

    /** Czy ekran aplikacji jest widoczny. Gdy nie — o wyniku mowi powiadomienie. */
    @Volatile var visible = false

    /** Konto, ktorego rozmowy sa w pamieci. Dla niego pamiec jest zrodlem prawdy, dysk — kopia. */
    private var owner: Long? = null

    /** Wczytywanie rozmow konta razem ze zmianami, ktore przyszly w jego trakcie. */
    private class Loading(val uid: Long) {
        val early = ArrayList<Pair<String, (Chat) -> Chat>>()
    }

    private var loading: Loading? = null

    /** Odpowiedzi w toku: rozmowa -> konto, ktore pytalo, i praca. */
    private val jobs = HashMap<String, Pair<Long, Job>>()

    fun init(context: Context) {
        if (!::app.isInitialized) app = context.applicationContext
    }

    /**
     * Rozmowy konta [userId] na ekran. Tego samego konta nie wczytujemy od nowa — w pamieci
     * moze byc odpowiedz, ktora jeszcze trwa. Rozmowy poprzedniego konta znikaja od razu,
     * a nie dopiero po wczytaniu nowych.
     */
    fun open(userId: Long) {
        if (userId == owner || loading?.uid == userId) return
        // Przerwane wczytywanie innego konta: zmiany z tego czasu ida prosto na dysk.
        loading?.let { old ->
            loading = null
            old.early.forEach { (id, block) -> update(old.uid, id, block) }
        }
        owner = null
        val blank = Chat()
        _chats.value = listOf(blank)
        _activeId.value = blank.id
        _lastTokens.value = null
        val mine = Loading(userId)
        loading = mine
        scope.launch {
            val list = withContext(disk) { Chats.loadAll(app, userId) }
            // W miedzyczasie zazadano innego konta — to wczytanie jest juz nieaktualne.
            if (loading !== mine) return@launch
            loading = null
            owner = userId
            _chats.value = list.ifEmpty { listOf(Chat()) }
            _activeId.value = _chats.value.first().id
            // Zmiany z czasu wczytywania, np. odpowiedz, ktora wlasnie doszla.
            mine.early.forEach { (id, block) -> update(userId, id, block) }
        }
    }

    fun select(id: String) {
        if (_chats.value.any { it.id == id }) _activeId.value = id
    }

    private fun save(uid: Long, chat: Chat) {
        scope.launch(disk) { Chats.save(app, uid, chat) }
    }

    /**
     * Podmienia rozmowe, liczac od jej aktualnej postaci. Rozmowa konta, ktorego nie ma
     * na ekranie (np. odpowiedz doszla po przelaczeniu konta), zmienia sie na dysku.
     * Rozmowy juz nie ma — niczego nie wskrzeszamy.
     */
    private fun update(uid: Long, id: String, block: (Chat) -> Chat) {
        val now = System.currentTimeMillis()
        val pending = loading
        when {
            uid == owner -> {
                val (list, next) = _chats.value.updated(id, now, block) ?: return
                _chats.value = list
                save(uid, next)
            }
            // Rozmowy tego konta jeszcze sie wczytuja — zmiana dojdzie zaraz po nich.
            pending != null && pending.uid == uid -> pending.early += id to block
            else -> scope.launch(disk) {
                Chats.load(app, uid, id)?.let { Chats.save(app, uid, block(it).copy(updatedAt = now)) }
            }
        }
    }

    fun newChat(uid: Long) {
        if (uid != owner) return
        // Pusta rozmowa nie ma sensu mnozyc — jesli biezaca jest pusta, zostajemy w niej.
        if (_chats.value.firstOrNull { it.id == _activeId.value }?.messages?.isEmpty() == true) return
        val fresh = Chat()
        _chats.value = listOf(fresh) + _chats.value
        _activeId.value = fresh.id
        save(uid, fresh)
    }

    /** Nowa nazwa nie przesuwa rozmowy na gore listy — to nie jest nowa wiadomosc. */
    fun rename(uid: Long, id: String, title: String) {
        if (uid != owner) return
        _chats.value = _chats.value.map { c ->
            if (c.id == id) c.copy(title = title).also { save(uid, it) } else c
        }
    }

    fun deleteChat(uid: Long, id: String) {
        if (uid != owner) return
        // Odpowiedz do usunietej rozmowy nie ma gdzie trafic, a liczylaby sie z salda.
        jobs[id]?.second?.cancel()
        scope.launch(disk) { Chats.delete(app, uid, id) }
        // Zawsze zostaje przynajmniej jedna rozmowa, zeby ekran czatu mial co pokazac.
        _chats.value = _chats.value.filterNot { it.id == id }.ifEmpty { listOf(Chat()) }
        if (_activeId.value == id) _activeId.value = _chats.value.first().id
    }

    /**
     * Usuwa wiadomosc. Nie w trakcie odpowiedzi — ta zapisuje cala historie rozmowy
     * i usunieta wiadomosc by wrocila. Zwraca, czy wiadomosc zniknela.
     */
    fun deleteMessage(uid: Long, chatId: String, index: Int): Boolean {
        if (uid != owner || chatId in _busy.value) return false
        val chat = _chats.value.firstOrNull { it.id == chatId } ?: return false
        if (index !in chat.messages.indices) return false
        update(uid, chatId) { it.withoutMessage(index) }
        return true
    }

    fun noteUsage(tokens: Pair<Int, Int>?) {
        _lastTokens.value = tokens
    }

    /**
     * Odpowiedz uzytkownika na te konkretna prosbe. Nie „na biezaca”: podwojne dotkniecie
     * „Zezwol” mogloby wtedy zatwierdzic kolejne polecenie, ktorego nikt jeszcze nie widzial.
     */
    fun answer(request: Approval, allowed: Boolean) {
        request.answer.complete(allowed)
    }

    /** Przerywa odpowiedzi konta — po wylogowaniu nie powinny dalej isc z jego salda. */
    fun stop(uid: Long) {
        jobs.values.filter { it.first == uid }.forEach { it.second.cancel() }
    }

    /** Zgoda na polecenie: true, false albo null, gdy nikt nie odpowiedzial na czas. */
    private suspend fun ask(chat: String, name: String, command: String): Boolean? = approvals.withLock {
        val answer = CompletableDeferred<Boolean>()
        _approval.value = Approval(chat, command, answer)
        if (!visible) Notifications.approvalNeeded(app, name)
        try {
            withTimeoutOrNull(APPROVAL_TIMEOUT_MS) { answer.await() }
        } finally {
            _approval.value = null
            Notifications.cancelApproval(app)
        }
    }

    /**
     * Wysyla wiadomosc i dopisuje odpowiedz do rozmowy, w ktorej padlo pytanie —
     * nawet gdy w trakcie przelaczysz rozmowe, wyjdziesz z aplikacji albo ja zablokujesz.
     */
    fun send(uid: Long, chatId: String, model: String, text: String) {
        if (uid != owner || chatId in _busy.value) return
        // Sesja z chwili wyslania: po przelaczeniu konta odpowiedz nadal liczy sie
        // z salda konta, ktore zadalo pytanie, a nie tego, ktore jest teraz na ekranie.
        val session = Api.token() ?: return
        val chat = _chats.value.firstOrNull { it.id == chatId } ?: return
        val sent = chat.messages + ChatMessage(text, true)
        val label = chat.copy(messages = sent).label
        update(uid, chatId) { it.copy(messages = sent) }
        _busy.value = _busy.value + chatId
        val name = Persona.nameOf(model)
        ChatService.start(app, name)

        // Leniwy start: praca jest na liscie, zanim zacznie sie wykonywac.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                // Starsze wymiany zwijamy w notatke, zanim polecą po raz kolejny.
                var mem = chat.memory
                if (shouldFold(sent, mem)) {
                    mem = Api.fold(model, sent, mem, auth = session)
                    val folded = mem
                    update(uid, chatId) { it.copy(memory = folded) }
                }
                val tools = if (Prefs.agentTerminal) AgentTools.instructions(AgentTools.target(app)) else null
                var history = sent
                var reply = Api.chat(model, history, mem, tools, auth = session)
                var round = 0

                // Model moze poprosic o polecenie. Kazde przechodzi przez zgode uzytkownika,
                // a petla ma twardy limit, zeby nie zapetlic sie na saldzie.
                while (Prefs.agentTerminal && round < AgentTools.MAX_ROUNDS) {
                    val cmd = AgentTools.requestedCommand(reply.text) ?: break
                    // Tura modelu zostaje w historii razem z poleceniem — uzytkownik widzi,
                    // co idzie do terminala, a model wie, o co sam poprosil.
                    val spoken = AgentTools.withoutCall(reply.text)
                    history = history + ChatMessage(
                        listOf(spoken, "$ $cmd").filter { it.isNotBlank() }.joinToString("\n\n"),
                        false,
                    )
                    val asked = history
                    update(uid, chatId) { it.copy(messages = asked) }
                    val result = when (ask(label, name, cmd)) {
                        true -> AgentTools.execute(app, cmd)
                        false -> "Użytkownik odmówił wykonania tego polecenia."
                        null -> "Użytkownik nie odpowiedział na prośbę o zgodę — polecenia nie wykonano."
                    }
                    history = history + ChatMessage("Wynik polecenia `$cmd`:\n$result", true)
                    val answered = history
                    update(uid, chatId) { it.copy(messages = answered) }
                    reply = Api.chat(model, history, mem, tools, auth = session)
                    if (uid == owner) _lastTokens.value = reply.inTokens to reply.outTokens
                    round++
                }

                var shown = AgentTools.withoutCall(reply.text).ifBlank { reply.text }
                // Limit wyczerpany, a model wciaz prosi o kolejne polecenie.
                if (Prefs.agentTerminal && round >= AgentTools.MAX_ROUNDS &&
                    AgentTools.requestedCommand(reply.text) != null
                ) {
                    shown += "\n\n(Przerwano po ${AgentTools.MAX_ROUNDS} poleceniach. " +
                        "Napisz „kontynuuj”, żeby pracował dalej.)"
                }
                // Cala historia z tej odpowiedzi, a nie dopisek do biezacej postaci: nawet gdy
                // ktoras zmiana po drodze przepadla, rozmowa konczy sie kompletna.
                val final = history + ChatMessage(shown, false)
                update(uid, chatId) { it.copy(messages = final) }
                if (uid == owner) _lastTokens.value = reply.inTokens to reply.outTokens
                if (!visible) Notifications.replyReady(app, name)
                // Saldo po kazdej odpowiedzi — o ile to konto wciaz jest zalogowane.
                try {
                    val fresh = Api.me(auth = session)
                    if (session == Api.token()) _events.tryEmit(Event.Account(fresh))
                } catch (_: Exception) {
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Chwilowa awaria modelu nie jest wina aplikacji ani salda — drugi model zwykle
                // dziala. Wlasny tekst, bo serwerowy mowi o tym, co stoi za modelem.
                val message = if (e is ApiError && e.code == "upstream_error") {
                    "$name chwilowo nie odpowiada. Spróbuj ponownie albo wybierz drugi model w zakładce Agenci."
                } else {
                    e.message ?: "Coś poszło nie tak. Spróbuj ponownie."
                }
                _events.tryEmit(Event.Message(uid, message))
                if (!visible) Notifications.failed(app, message)
            } finally {
                jobs.remove(chatId)
                _busy.value = _busy.value - chatId
            }
        }
        jobs[chatId] = uid to job
        job.start()
    }
}
