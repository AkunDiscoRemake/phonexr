package com.samrat.cardboardhands

import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Meta Horizon–style home: the Library (title, search, a list of sections on the left, a grid of
 * app tiles, sorting on the right) above a dock bar (profile, status, notifications, search,
 * passthrough, pinned apps, recent apps, the library button).
 *
 * This class only knows the state and where every target is (for pointing); [HomePanelContent]
 * draws it with the same geometry from the companion object.
 */
class HomePanel {
    /** What the compose panel ([HomePanelContent]) shows: taken after every [draw], so it recomposes. */
    data class Snapshot(
        val mode: Mode, val tab: Tab, val library: Boolean, val page: Int, val pages: Int,
        val entries: List<Entry>, val pinned: List<Entry>, val recent: List<Entry>,
        val hovered: Target?, val pressed: Boolean, val dark: Boolean, val sort: Sort,
        val searching: Boolean, val query: String, val symbols: Boolean, val russian: Boolean,
        val suggestions: List<Entry>, val alerts: Int, val passthrough: Boolean,
        val sliders: FloatArray, val showSliders: Boolean, val dnd: Boolean, val car: Boolean,
    )

    var snapshot by androidx.compose.runtime.mutableStateOf<Snapshot?>(null)
        private set

    enum class Mode { HOME, LIBRARY, STORE, MENU, QUICK }

    /** Quick settings, opened from the time in the dock (like Quest). */
    enum class Quick(val title: String) {
        SETTINGS("Настройки"), WIFI("Wi‑Fi"), BLUETOOTH("Bluetooth"), CAR("Режим машины"), DESKTOP("Компьютер"),
        PASSTHROUGH("Реальный мир"), RECENTER("Выровнять"), DND("Не беспокоить"), PHOTO("Снимок"),
    }

    /** The sections on the left of the Library. */
    enum class Tab(val title: String) {
        ALL("Все"), APPS("Приложения"), GAMES("Игры"), WEB("Веб"), PEOPLE("Люди"), ENVIRONMENTS("Миры")
    }

    enum class Sort(val title: String) { RECENT("Недавние"), NAME("По имени") }

    /** The room sliders under the worlds: 0..1 each. */
    enum class Slider(val title: String) { VOLUME("Громкость"), BRIGHTNESS("Яркость"), RAIN("Дождь"), FOG("Туман") }

    /** The buttons at the left of the dock. */
    enum class Control { PROFILE, STATUS, NOTIFICATIONS, SEARCH, PASSTHROUGH }

    /**
     * [glyph]: a Material icon drawn on a themed tile instead of [icon] (PhoneXR's own apps);
     * [tint]: whose tile colour to use (a window takes the colour of the app it came from).
     */
    data class Entry(
        val id: String, val label: String, val icon: Drawable?, val badge: String? = null,
        val glyph: ImageVector? = null, val tint: String? = null,
    )

    sealed class Target {
        data class App(val entry: Entry) : Target()
        data class Page(val index: Int) : Target()
        data class Rail(val tab: Tab) : Target()
        data class Control(val control: HomePanel.Control) : Target()
        /** A key of the search keyboard: a character or one of the KEY_* ids. */
        data class Key(val key: String) : Target()
        data class Slider(val slider: HomePanel.Slider) : Target()
        data class Quick(val quick: HomePanel.Quick) : Target()
        /** The library button of the dock. */
        object Library : Target()
        object Store : Target()
        object Sort : Target()
        object Close : Target()
        /** Somewhere on a card that does nothing (so it does not fall through to what is behind). */
        object Card : Target()
    }

    var mode = Mode.HOME
    /** Only the dock (the "compact" home style); otherwise the Library stays open above it. */
    var compact = false
    var dark = false
    var alerts = 0
    var passthrough = true
    /** Do not disturb: no badge on the bell and no call ringing into VR. */
    var dnd = false
    /** Car mode is on (shown on its tile). */
    var car = false
    /** Values of [Slider], in its order. */
    val sliders = floatArrayOf(.5f, 1f, 0f, 0f)
    var page = 0
        private set
    var tab = Tab.ALL
        private set
    var sort = Sort.RECENT
        private set
    private var home = emptyList<Entry>()
    private var people = emptyList<Entry>()
    private var environments = emptyList<Entry>()
    private var store = emptyList<Entry>()
    private var menu = emptyList<Entry>()
    /** Open windows, first in the recent part of the dock. */
    private var windows = emptyList<Entry>()
    /** Ids opened in this session, newest first: the "recent" order. */
    private val opened = ArrayList<String>()
    private val areas = ArrayList<Pair<RectF, Target>>()

    var searching = false
        private set
    private var query = ""
    private var symbols = false
    private var russian = true

    fun setTab(value: Tab) { tab = value; page = 0 }
    fun setHome(entries: List<Entry>) { home = entries; page = page.coerceAtMost(pages(current()) - 1) }
    fun setPeople(entries: List<Entry>) { people = entries }
    fun setEnvironments(entries: List<Entry>) { environments = entries }
    fun setStore(entries: List<Entry>) { store = entries }
    fun setMenu(entries: List<Entry>) { menu = entries }
    fun setDock(entries: List<Entry>) { windows = entries }
    fun toggleSort() { sort = if (sort == Sort.RECENT) Sort.NAME else Sort.RECENT; page = 0 }

    fun homeIcons(): Map<String, Drawable?> = home.associate { it.id to it.icon }
    fun homeEntries(): List<Entry> = home

    /** Remembers [id] as just opened, for the recent order and the dock. */
    fun opened(id: String) {
        opened.remove(id)
        opened.add(0, id)
    }

    fun library() = (mode != Mode.HOME || !compact) && mode != Mode.QUICK

    /** The worlds show the room sliders under them. */
    private fun showSliders() = library() && !searching && tab == Tab.ENVIRONMENTS &&
        (mode == Mode.HOME || mode == Mode.LIBRARY)

    /** Where a pointer at [v] (0..1 down the panel) puts [slider]. */
    fun sliderValue(slider: Slider, u: Float, v: Float): Float {
        if (mode == Mode.QUICK) {
            val rect = quickSlider(slider)
            return ((u * WIDTH - rect.left) / rect.width()).coerceIn(0f, 1f)
        }
        val rect = sliderRect(slider.ordinal)
        return ((rect.bottom - v * HEIGHT) / rect.height()).coerceIn(0f, 1f)
    }

    private fun ordered(list: List<Entry>): List<Entry> = when (sort) {
        Sort.NAME -> list.sortedBy { it.label.lowercase() }
        Sort.RECENT -> list.sortedBy { opened.indexOf(it.id).let { i -> if (i < 0) Int.MAX_VALUE else i } }
    }

    private fun current(): List<Entry> = when (mode) {
        Mode.HOME, Mode.LIBRARY, Mode.QUICK -> ordered(when (tab) {
            Tab.ALL -> home
            Tab.APPS -> home.filter { !it.id.startsWith("app:") && !it.id.startsWith("web:") }
            Tab.GAMES -> home.filter { it.id.startsWith("app:") }
            Tab.WEB -> home.filter { it.id.startsWith("web:") }
            Tab.PEOPLE -> people
            Tab.ENVIRONMENTS -> environments
        })
        Mode.STORE -> store
        Mode.MENU -> menu
    }

    private fun matches(): List<Entry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val all = home + people + environments
        return all.filter { it.label.lowercase().startsWith(q) } +
            all.filter { !it.label.lowercase().startsWith(q) && it.label.lowercase().contains(q) }
    }

    private fun pinned() = home.take(PINNED)

    /** Open windows first, then what was opened lately, then the next apps of the home. */
    private fun recent(): List<Entry> {
        val pinned = pinned().map { it.id }.toSet()
        val byId = home.associateBy { it.id }
        val lately = opened.mapNotNull { byId[it] }.filter { it.id !in pinned }
        return (windows + lately + home.drop(PINNED)).distinctBy { it.id }.take(RECENT)
    }

    private fun pages(list: List<Entry>) = maxOf(1, (list.size + PER_PAGE - 1) / PER_PAGE)

    fun turnPage(delta: Int) { page = (page + delta).coerceIn(0, pages(current()) - 1) }
    fun showPage(index: Int) { page = index.coerceIn(0, pages(current()) - 1) }

    // ------------------------------------------------------------------ search

    fun openSearch() {
        searching = true
        query = ""
        symbols = false
        if (mode == Mode.HOME && compact || mode == Mode.MENU) mode = Mode.LIBRARY
    }

    fun closeSearch() { searching = false; query = "" }

    /** A search key was pressed: returns the entry to open for "enter", if any. */
    fun type(key: String): Entry? {
        when (key) {
            KEY_BACKSPACE -> query = query.dropLast(1)
            KEY_ENTER -> return matches().firstOrNull()
            KEY_HIDE -> closeSearch()
            KEY_LANGUAGE -> { russian = !russian; symbols = false }
            KEY_SYMBOLS -> symbols = !symbols
            KEY_SPACE -> if (query.isNotEmpty()) query += " "
            else -> if (query.length < 40) query += key
        }
        return null
    }

    fun hit(u: Float, v: Float): Target? {
        val x = u * WIDTH
        val y = v * HEIGHT
        return areas.firstOrNull { it.first.contains(x, y) }?.second
    }

    fun draw(hovered: Target?, pressed: Boolean) {
        val list = if (searching) matches() else current()
        val pages = if (searching) 1 else pages(list)
        page = page.coerceIn(0, pages - 1)
        val shown = if (searching) list.take(COLUMNS) else list.drop(page * PER_PAGE).take(PER_PAGE)
        layout(shown, pages)
        snapshot = Snapshot(
            mode, tab, library(), page, pages, shown, pinned(), recent(), hovered, pressed, dark, sort,
            searching, query, symbols, russian, list.take(3), alerts, passthrough,
            sliders.copyOf(), showSliders(), dnd, car,
        )
    }

    /** Where every target is, in the same places [HomePanelContent] draws them. */
    private fun layout(shown: List<Entry>, pages: Int) {
        areas.clear()
        dockLayout()
        if (mode == Mode.QUICK) {
            for (item in Quick.entries) areas += quickRect(item) to Target.Quick(item)
            for (item in listOf(Slider.VOLUME, Slider.BRIGHTNESS)) {
                areas += quickSliderTile(item) to Target.Slider(item)
            }
            // Anywhere else on the card does nothing; above it closes it.
            areas += QUICK_CARD to Target.Card
            areas += RectF(0f, 0f, WIDTH.toFloat(), QUICK_CARD.top) to Target.Close
            return
        }
        if (!library()) return
        areas += searchField(searching) to Target.Control(Control.SEARCH)
        for ((index, item) in Tab.entries.withIndex()) areas += railRow(index) to Target.Rail(item)
        areas += railRow(Tab.entries.size, store = true) to Target.Store
        if (searching) {
            for (key in keyboard()) areas += key.rect to Target.Key(key.id)
            val suggestions = matches().take(3)
            for (i in suggestions.indices) areas += suggestion(i) to Target.App(suggestions[i])
        } else {
            if (showSliders()) for (item in Slider.entries) {
                // A little slack above and below, so the ends are easy to reach.
                val rect = sliderRect(item.ordinal)
                areas += RectF(rect.left - 20f, rect.top - 40f, rect.right + 20f, rect.bottom + 30f) to Target.Slider(item)
            }
            else areas += sortButton() to Target.Sort
            if (pages > 1) for (i in 0 until pages) {
                val x = pageDotX(i, pages)
                areas += RectF(x - 24f, PAGE_DOTS_Y - 30f, x + 24f, PAGE_DOTS_Y + 30f) to Target.Page(i)
            }
        }
        shown.forEachIndexed { index, entry ->
            val (x, y) = cell(index)
            areas += RectF(x - CELL_W / 2, y - ICON / 2 - 24f, x + CELL_W / 2, y + ICON / 2 + 90f) to Target.App(entry)
        }
    }

    private fun dockLayout() {
        fun slot(center: Float, halfWidth: Float = 26f) = RectF(dockX(center - halfWidth), DOCK_TOP, dockX(center + halfWidth), DOCK_TOP + DOCK_H)
        areas += slot(AVATAR_AT) to Target.Control(Control.PROFILE)
        areas += RectF(dockX(STATUS_FROM), DOCK_TOP, dockX(STATUS_TO), DOCK_TOP + DOCK_H) to Target.Control(Control.STATUS)
        areas += slot(BELL_AT) to Target.Control(Control.NOTIFICATIONS)
        areas += slot(SEARCH_AT) to Target.Control(Control.SEARCH)
        areas += slot(PASSTHROUGH_AT) to Target.Control(Control.PASSTHROUGH)
        pinned().forEachIndexed { i, entry -> areas += slot(PINNED_AT + i * SLOT, 28f) to Target.App(entry) }
        recent().forEachIndexed { i, entry -> areas += slot(RECENT_AT + i * SLOT, 28f) to Target.App(entry) }
        areas += slot(LIBRARY_AT, 30f) to Target.Library
    }

    /** One key of the search keyboard, in panel pixels. */
    class Key(val id: String, val label: String, val rect: RectF)

    /**
     * The search keyboard, laid out like the Meta Horizon keyboard: letters, backspace at the end of
     * the first row, a dark enter key, and language, space and hide at the bottom.
     */
    fun keyboard(): List<Key> {
        val rows = if (symbols) SYMBOL_ROWS else if (russian) RUSSIAN else ENGLISH
        val keys = ArrayList<Key>()
        val left = KEYBOARD.left + 16f * KB
        val right = KEYBOARD.right - 16f * KB
        val gap = 8f * KB
        fun rowTop(i: Int) = KEYBOARD.top + (68f + i * 56f) * KB
        val keyH = 48f * KB
        for ((i, row) in rows.withIndex()) {
            val special = when (i) { 0 -> KEY_BACKSPACE; 1 -> KEY_ENTER; else -> null }
            val units = row.length + (if (special == KEY_ENTER) 1.5f else if (special != null) 1f else 0f)
            val pitch = minOf(56f * KB, (right - left + gap) / units)
            val width = row.length * pitch + (if (special == KEY_ENTER) 1.5f * pitch else if (special != null) pitch else 0f) - gap
            var x = (left + right) / 2 - width / 2
            for (c in row) {
                keys += Key(c.toString(), c.toString(), RectF(x, rowTop(i), x + pitch - gap, rowTop(i) + keyH))
                x += pitch
            }
            if (special != null) {
                val w = (if (special == KEY_ENTER) 1.5f * pitch else pitch) - gap
                keys += Key(special, "", RectF(x, rowTop(i), x + w, rowTop(i) + keyH))
            }
        }
        val top = rowTop(3)
        val unit = 56f * KB
        keys += Key(KEY_SYMBOLS, if (symbols) "abc" else "?123", RectF(left, top, left + unit * 1.5f - gap, top + keyH))
        keys += Key(KEY_LANGUAGE, "", RectF(left + unit * 1.5f, top, left + unit * 2.5f - gap, top + keyH))
        keys += Key(KEY_SPACE, "", RectF(left + unit * 2.5f, top, right - unit * 2f - gap, top + keyH))
        keys += Key(".", ".", RectF(right - unit * 2f, top, right - unit - gap, top + keyH))
        keys += Key(KEY_HIDE, "", RectF(right - unit + gap, top, right, top + keyH))
        return keys
    }

    companion object {
        const val WIDTH = 1800
        const val HEIGHT = 1350

        // ---------------------------------------------------------------- the Library card
        val CARD = RectF(60f, 20f, 1740f, 1160f)
        const val TITLE_TOP = 58f
        fun searchField(wide: Boolean = false) = if (wide) RectF(600f, 160f, 1200f, 232f) else RectF(710f, 162f, 1090f, 230f)

        const val RAIL_LEFT = 100f
        const val RAIL_RIGHT = 420f
        const val RAIL_TOP = 290f
        const val RAIL_PITCH = 80f
        const val RAIL_ROW_H = 70f
        /** Rows of the section list; the store sits under a separator. */
        fun railRow(index: Int, store: Boolean = false): RectF {
            val top = RAIL_TOP + index * RAIL_PITCH + if (store) 50f else 0f
            return RectF(RAIL_LEFT, top, RAIL_RIGHT, top + RAIL_ROW_H)
        }
        fun railSeparatorY() = RAIL_TOP + Tab.entries.size * RAIL_PITCH + 15f

        const val COLUMNS = 5
        private const val ROWS = 3
        const val PER_PAGE = COLUMNS * ROWS
        const val CELL_W = 200f
        const val ICON = 132f
        private const val GRID_LEFT_X = 575f
        private const val GRID_TOP_Y = 380f
        private const val ROW_PITCH = 250f
        const val GRID_CENTER_X = GRID_LEFT_X + (COLUMNS - 1) * CELL_W / 2
        fun cell(index: Int) = Pair(GRID_LEFT_X + index % COLUMNS * CELL_W, GRID_TOP_Y + index / COLUMNS * ROW_PITCH)

        fun sortButton() = RectF(1450f, 270f, 1710f, 336f)

        /** The room sliders: tall pills in a row under the worlds, like Horizon's room controls. */
        fun sliderRect(index: Int): RectF {
            val cx = GRID_CENTER_X + (index - 1.5f) * 200f
            return RectF(cx - 62f, 620f, cx + 62f, 1085f)
        }

        const val PAGE_DOTS_Y = 1115f
        fun pageDotX(i: Int, pages: Int) = GRID_CENTER_X + (i - (pages - 1) / 2f) * 44f

        /** Search keyboard: the Horizon keyboard (640×299 design units) scaled into the card. */
        val KEYBOARD = RectF(340f, 590f, 1460f, 590f + 299f * 1120f / 640f)
        const val KB = 1120f / 640f
        fun suggestion(i: Int): RectF {
            val w = KEYBOARD.width() / 3
            return RectF(KEYBOARD.left + i * w, KEYBOARD.top, KEYBOARD.left + (i + 1) * w, KEYBOARD.top + 62f * KB)
        }

        // ---------------------------------------------------------------- the dock
        /** The dock bar is the Horizon bar (970×70 design units) scaled by [DOCK_SCALE]. */
        const val DOCK_SCALE = 1.8f
        const val DOCK_W = 970f * DOCK_SCALE
        const val DOCK_H = 70f * DOCK_SCALE
        const val DOCK_LEFT = (WIDTH - DOCK_W) / 2
        const val DOCK_TOP = HEIGHT - DOCK_H - 12f
        const val DOCK_CY = DOCK_TOP + DOCK_H / 2
        /** Design x (0..970) → panel x. */
        fun dockX(design: Float) = DOCK_LEFT + design * DOCK_SCALE

        const val AVATAR_AT = 36f
        const val STATUS_FROM = 76f
        const val STATUS_TO = 192f
        const val CLOCK_AT = 82f
        const val WIFI_AT = 140f
        const val BATTERY_AT = 172f
        const val BELL_AT = 229f
        const val SEARCH_AT = 285f
        const val PASSTHROUGH_AT = 342f
        val DIVIDERS = floatArrayOf(379f, 671f, 903f)
        const val PINNED_AT = 414f
        const val RECENT_AT = 702f
        const val SLOT = 56f
        const val LIBRARY_AT = 934f
        const val PINNED = 5
        const val RECENT = 4

        // ---------------------------------------------------------------- quick settings
        /** The control centre is separate glass tiles, as on Quest; this is where they all are. */
        val QUICK_CARD = RectF(180f, 560f, 1620f, 1180f)
        /** The status row (battery, date) and the two slider tiles. */
        val QUICK_STATUS = RectF(180f, 560f, 1370f, 660f)
        fun quickSliderTile(slider: Slider): RectF =
            if (slider == Slider.VOLUME) RectF(180f, 680f, 890f, 800f) else RectF(910f, 680f, 1620f, 800f)

        /** The long slider inside its tile: volume or brightness. */
        fun quickSlider(slider: Slider): RectF = quickSliderTile(slider).let { RectF(it.left + 48f, it.centerY() - 10f, it.right - 110f, it.centerY() + 10f) }

        fun quickRect(item: Quick): RectF = when (item) {
            Quick.SETTINGS -> RectF(1390f, 560f, 1620f, 660f)
            Quick.WIFI, Quick.BLUETOOTH, Quick.CAR, Quick.DESKTOP -> {
                val i = item.ordinal - Quick.WIFI.ordinal
                val w = (1440f - 3 * 20f) / 4
                RectF(180f + i * (w + 20f), 820f, 180f + i * (w + 20f) + w, 960f)
            }
            else -> {
                val i = item.ordinal - Quick.PASSTHROUGH.ordinal
                RectF(180f + i * 140f, 980f, 180f + i * 140f + 120f, 1100f)
            }
        }

        const val KEY_BACKSPACE = "#backspace"
        const val KEY_ENTER = "#enter"
        const val KEY_HIDE = "#hide"
        const val KEY_LANGUAGE = "#lang"
        const val KEY_SYMBOLS = "#symbols"
        const val KEY_SPACE = "#space"
        private val ENGLISH = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        private val RUSSIAN = listOf("йцукенгшщзх", "фывапролджэ", "ячсмитьбю")
        private val SYMBOL_ROWS = listOf("1234567890", "@#&_-+()/", "*'\":;!?")
    }
}
