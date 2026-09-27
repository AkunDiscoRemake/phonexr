package com.samrat.cardboardhands

import android.content.Context

/**
 * PhoneXR's languages. The interface is written in Russian; [tr] gives the chosen language's text
 * for a Russian source string (untranslated strings stay Russian).
 */
object L10n {
    enum class Lang(val code: String, val title: String, val speech: String) {
        RU("ru", "Русский", "ru-RU"),
        EN("en", "English", "en-US"),
        PT_BR("pt-BR", "Português (Brasil)", "pt-BR"),
        PT_PT("pt-PT", "Português (Portugal)", "pt-PT"),
    }

    private const val PREFS = "language"
    @Volatile var current = Lang.RU
        private set

    fun init(context: Context) {
        current = runCatching { Lang.valueOf(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("lang", Lang.RU.name)!!) }
            .getOrDefault(Lang.RU)
    }

    fun set(context: Context, lang: Lang) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("lang", lang.name).apply()
        current = lang
    }

    fun tr(ru: String): String {
        val index = when (current) { Lang.RU -> return ru; Lang.EN -> 0; Lang.PT_BR -> 1; Lang.PT_PT -> 2 }
        return DICT[ru]?.get(index) ?: ru
    }

    /** Russian → English, Portuguese (Brazil), Portuguese (Portugal). */
    private val DICT: Map<String, Array<String>> = mapOf(
        // Tabs and main screens
        "Меню" to arrayOf("Menu", "Menu", "Menu"),
        "Магазин" to arrayOf("Store", "Loja", "Loja"),
        "Друзья" to arrayOf("Friends", "Amigos", "Amigos"),
        "Настройки" to arrayOf("Settings", "Ajustes", "Definições"),
        "Войти в VR" to arrayOf("Enter VR", "Entrar no VR", "Entrar em VR"),
        "Игры" to arrayOf("Games", "Jogos", "Jogos"),
        // VR home (Horizon library and dock)
        "Библиотека" to arrayOf("Library", "Biblioteca", "Biblioteca"),
        "Вы готовы?" to arrayOf("Are you ready?", "Está pronto?", "Está pronto?"),
        "Лента" to arrayOf("Feed", "Feed", "Feed"),
        "Друзья в сети" to arrayOf("Friends online", "Amigos online", "Amigos online"),
        "Новое" to arrayOf("What's new", "Novidades", "Novidades"),
        "Попробуйте" to arrayOf("Try these", "Experimente", "Experimente"),
        "Касание пальцем" to arrayOf("Finger touch", "Toque com o dedo", "Toque com o dedo"),
        "Видеть комнату" to arrayOf("See your room", "Ver o ambiente", "Ver o espaço"),
        "Микрофон выкл." to arrayOf("Mic off", "Microfone desligado", "Microfone desligado"),
        "Скриншот" to arrayOf("Screenshot", "Captura", "Captura"),
        "Снять видео" to arrayOf("Record video", "Record video", "Record video"),
        "Остановить запись" to arrayOf("Stop recording", "Stop recording", "Stop recording"),
        "Видео сохранено в «Фото»" to arrayOf("Video saved to Photos", "Video saved to Photos", "Video saved to Photos"),
        "Не удалось сохранить видео" to arrayOf("Couldn't save the video", "Couldn't save the video", "Couldn't save the video"),
        "Не удалось начать запись" to arrayOf("Couldn't start recording", "Couldn't start recording", "Couldn't start recording"),
        "Запись началась" to arrayOf("Recording", "Recording", "Recording"),
        "Зеркало" to arrayOf("Mirror", "Espelho", "Espelho"),
        "Полное тело" to arrayOf("Full body", "Corpo inteiro", "Corpo inteiro"),
        "Голова, руки и торс" to arrayOf("Head, arms and chest", "Cabeça, braços e tronco", "Cabeça, braços e tronco"),
        "В VR" to arrayOf("In VR", "No VR", "Em VR"),
        "Смотрит вместе" to arrayOf("Watching together", "Assistindo juntos", "A ver juntos"),
        "Присоединиться" to arrayOf("Join", "Entrar", "Juntar-se"),
        "Смотреть вместе" to arrayOf("Watch together", "Assistir juntos", "Ver juntos"),
        "Вместе" to arrayOf("Together", "Juntos", "Juntos"),
        "Сделать в VRoid" to arrayOf("Make in VRoid", "Criar no VRoid", "Criar no VRoid"),
        "Сделайте персонажа в VRoid, экспортируйте .vrm и нажмите «Загрузить файл .glb / .vrm»" to arrayOf(
            "Make a character in VRoid, export it as .vrm and tap “Load a .glb / .vrm file”",
            "Crie um personagem no VRoid, exporte como .vrm e toque em “Carregar arquivo .glb / .vrm”",
            "Crie uma personagem no VRoid, exporte como .vrm e toque em “Carregar ficheiro .glb / .vrm”"),
        "Выберите шлем" to arrayOf("Choose your headset", "Escolha o headset", "Escolha o headset"),
        "Другой шлем" to arrayOf("Other headset", "Outro headset", "Outro headset"),
        "Линзы" to arrayOf("Lenses", "Lentes", "Lentes"),
        "мм" to arrayOf("mm", "mm", "mm"),
        "Да" to arrayOf("Yes", "Sim", "Sim"),
        "Вставьте телефон в VR‑шлем" to arrayOf("Put the phone in the VR headset", "Coloque o celular no headset VR", "Coloque o telemóvel no headset VR"),
        "Аккаунт PhoneXR" to arrayOf("PhoneXR account", "Conta PhoneXR", "Conta PhoneXR"),
        "Войдите, чтобы звонить друзьям, видеть их в VR и сохранять настройки. Можно и позже — в приложении на телефоне." to arrayOf("Sign in to call friends, see them in VR and keep your settings. You can also do it later in the phone app.", "Entre para ligar para amigos, vê-los em VR e salvar suas configurações. Você também pode fazer isso depois no app do celular.", "Inicie sessão para ligar a amigos, vê-los em VR e guardar as definições. Também pode fazê-lo mais tarde na app do telemóvel."),
        "Создать аккаунт" to arrayOf("Create account", "Criar conta", "Criar conta"),
        "Вход" to arrayOf("Sign in", "Entrar", "Iniciar sessão"),
        "Регистрация" to arrayOf("Create account", "Criar conta", "Criar conta"),
        "Почта" to arrayOf("E-mail", "E-mail", "E-mail"),
        "Пароль" to arrayOf("Password", "Senha", "Palavra-passe"),
        "Имя" to arrayOf("Name", "Nome", "Nome"),
        "Создать" to arrayOf("Create", "Criar", "Criar"),
        "Введите почту и пароль (не короче 6 символов)" to arrayOf("Enter your e-mail and a password (6+ characters)", "Digite seu e-mail e uma senha (6+ caracteres)", "Introduza o e-mail e uma palavra-passe (6+ caracteres)"),
        "Введите имя" to arrayOf("Enter your name", "Digite seu nome", "Introduza o seu nome"),
        "Добро пожаловать" to arrayOf("Welcome", "Bem-vindo", "Bem-vindo"),
        "Сканирование комнаты" to arrayOf("Room scan", "Escaneamento do ambiente", "Digitalização da sala"),
        "Осмотрите пол, стены и стол — PhoneXR покроет их сеткой и запомнит, где стоит стол: на нём будет клавиатура. Потом обойдите свободное место по краю; круг замкнётся сам, или сожмите кулак." to arrayOf(
            "Look at the floor, the walls and the table — PhoneXR covers them with a grid and remembers where the table is: the keyboard will lie on it. Then walk around the edge of the free space; the loop closes by itself, or make a fist.",
            "Olhe para o chão, as paredes e a mesa — o PhoneXR os cobre com uma grade e lembra onde fica a mesa: o teclado ficará nela. Depois contorne o espaço livre; o círculo fecha sozinho, ou feche a mão.",
            "Olhe para o chão, as paredes e a mesa — o PhoneXR cobre-os com uma grelha e lembra-se de onde está a mesa: o teclado ficará nela. Depois contorne o espaço livre; o círculo fecha-se sozinho, ou feche a mão."),
        "Стол найден ✓" to arrayOf("Table found ✓", "Mesa encontrada ✓", "Mesa encontrada ✓"),
        "Ищу стол…" to arrayOf("Looking for a table…", "Procurando uma mesa…", "À procura de uma mesa…"),
        "Приложение и VR выглядят одинаково; тема общая с VR‑домом." to arrayOf("The app and VR look the same; the theme is shared with the VR home.", "O app e o VR têm o mesmo visual; o tema é compartilhado com a casa VR.", "A app e o VR têm o mesmo aspeto; o tema é partilhado com a casa VR."),
        "Все" to arrayOf("All", "Todos", "Todos"),
        "Приложения" to arrayOf("Apps", "Apps", "Aplicações"),
        "Веб" to arrayOf("Web", "Web", "Web"),
        "Люди" to arrayOf("People", "Pessoas", "Pessoas"),
        "Миры" to arrayOf("Worlds", "Mundos", "Mundos"),
        "Недавние" to arrayOf("Recent", "Recentes", "Recentes"),
        "По имени" to arrayOf("Name", "Nome", "Nome"),
        "Поиск" to arrayOf("Search", "Pesquisar", "Pesquisar"),
        "Оформление" to arrayOf("Appearance", "Aparência", "Aspeto"),
        "Светлое" to arrayOf("Light", "Claro", "Claro"),
        "Тёмное" to arrayOf("Dark", "Escuro", "Escuro"),
        "Магазин загружается…" to arrayOf("Loading the store…", "Carregando a loja…", "A carregar a loja…"),
        "Здесь будут друзья — войдите в аккаунт в приложении PhoneXR" to arrayOf("Your friends will be here — sign in in the PhoneXR app", "Seus amigos aparecerão aqui — entre na sua conta no app PhoneXR", "Os seus amigos aparecerão aqui — inicie sessão na app PhoneXR"),
        "Здесь будут VR‑игры, подготовленные в приложении PhoneXR" to arrayOf("VR games prepared in the PhoneXR app will be here", "Os jogos VR preparados no app PhoneXR aparecerão aqui", "Os jogos VR preparados na app PhoneXR aparecerão aqui"),
        "Добавьте веб‑приложения из магазина" to arrayOf("Add web apps from the store", "Adicione apps web da loja", "Adicione aplicações web da loja"),
        "Установка" to arrayOf("Install", "Instalação", "Instalação"),
        "Установить игру из файла" to arrayOf("Install a game from a file", "Instalar jogo de um arquivo", "Instalar jogo a partir de ficheiro"),
        "Магазин игр" to arrayOf("Game store", "Loja de jogos", "Loja de jogos"),
        "Трекинг" to arrayOf("Tracking", "Rastreamento", "Rastreio"),
        "Остановить трекинг" to arrayOf("Stop tracking", "Parar rastreamento", "Parar rastreio"),
        "Управление" to arrayOf("Controls", "Controles", "Controlos"),
        "Управление и Joy‑Con" to arrayOf("Controls and Joy‑Con", "Controles e Joy‑Con", "Controlos e Joy‑Con"),
        "Joy‑Con через камеру" to arrayOf("Joy‑Con via camera", "Joy‑Con pela câmera", "Joy‑Con pela câmara"),
        "Проверка" to arrayOf("Checks", "Verificação", "Verificação"),
        "Проверить гироскоп Joy‑Con" to arrayOf("Test Joy‑Con gyro", "Testar giroscópio do Joy‑Con", "Testar giroscópio do Joy‑Con"),
        "Аккаунт" to arrayOf("Account", "Conta", "Conta"),
        "Войти" to arrayOf("Sign in", "Entrar", "Iniciar sessão"),
        "Выйти" to arrayOf("Sign out", "Sair", "Terminar sessão"),
        "Язык" to arrayOf("Language", "Idioma", "Idioma"),
        "Обновление ПО" to arrayOf("Software Update", "Atualização de Software", "Atualização de software"),
        "О приложении" to arrayOf("About", "Sobre", "Acerca de"),
        "Сервер" to arrayOf("Server", "Servidor", "Servidor"),
        "Обновить" to arrayOf("Refresh", "Atualizar", "Atualizar"),
        "Обновление…" to arrayOf("Refreshing…", "Atualizando…", "A atualizar…"),
        "Загрузить" to arrayOf("Get", "Obter", "Obter"),
        "Скачать" to arrayOf("Download", "Baixar", "Transferir"),
        "Играть" to arrayOf("Play", "Jogar", "Jogar"),
        "Открыть" to arrayOf("Open", "Abrir", "Abrir"),
        "Получить" to arrayOf("Get", "Obter", "Obter"),
        "Удалить" to arrayOf("Remove", "Remover", "Remover"),
        "Добавить" to arrayOf("Add", "Adicionar", "Adicionar"),
        "Закрыть" to arrayOf("Close", "Fechar", "Fechar"),
        "Готово" to arrayOf("Done", "Concluído", "Concluído"),
        "Отмена" to arrayOf("Cancel", "Cancelar", "Cancelar"),
        "Позже" to arrayOf("Later", "Depois", "Mais tarde"),
        "Подробнее" to arrayOf("Details", "Detalhes", "Detalhes"),
        "Пропустить" to arrayOf("Skip", "Pular", "Saltar"),
        "Продолжить" to arrayOf("Continue", "Continuar", "Continuar"),
        "Не получилось" to arrayOf("Something went wrong", "Não deu certo", "Não foi possível"),
        "VR‑режимы" to arrayOf("VR modes", "Modos VR", "Modos VR"),
        "Приложения PhoneXR" to arrayOf("PhoneXR apps", "Apps PhoneXR", "Apps PhoneXR"),
        "Android‑приложения" to arrayOf("Android apps", "Apps Android", "Apps Android"),
        "Веб‑приложения" to arrayOf("Web apps", "Apps web", "Apps web"),
        "Моды Minecraft" to arrayOf("Minecraft mods", "Mods do Minecraft", "Mods do Minecraft"),
        "Установить мод из файла" to arrayOf("Install a mod from a file", "Instalar mod de um arquivo", "Instalar mod a partir de ficheiro"),
        "Установить" to arrayOf("Install", "Instalar", "Instalar"),
        "Установить мод" to arrayOf("Install mod", "Instalar mod", "Instalar mod"),
        "Пока пусто" to arrayOf("Nothing here yet", "Nada aqui ainda", "Ainda vazio"),
        "Загрузка…" to arrayOf("Loading…", "Carregando…", "A carregar…"),
        // VR home
        "Браузер" to arrayOf("Browser", "Navegador", "Navegador"),
        "Фото" to arrayOf("Photos", "Fotos", "Fotografias"),
        "Звонки" to arrayOf("Calls", "Chamadas", "Chamadas"),
        "Главная" to arrayOf("Home", "Início", "Início"),
        "Снять фото" to arrayOf("Take photo", "Tirar foto", "Tirar fotografia"),
        "Выровнять" to arrayOf("Recenter", "Recentralizar", "Recentrar"),
        "Граница" to arrayOf("Boundary", "Limite", "Limite"),
        "Выйти из VR" to arrayOf("Exit VR", "Sair do VR", "Sair de VR"),
        "Настроить" to arrayOf("Customize", "Personalizar", "Personalizar"),
        "Светлые" to arrayOf("Light", "Claro", "Claro"),
        "Тёмные" to arrayOf("Dark", "Escuro", "Escuro"),
        "О гарнитуре" to arrayOf("About headset", "Sobre o headset", "Sobre o headset"),
        "Лицо" to arrayOf("Persona", "Persona", "Persona"),
        "Добавить лицо" to arrayOf("Add Persona", "Adicionar Persona", "Adicionar Persona"),
        "Показать лицо" to arrayOf("Show Persona", "Mostrar Persona", "Mostrar Persona"),
        "Сканирование лица" to arrayOf("Face scan", "Escaneamento facial", "Digitalização facial"),
        "Начать сканирование" to arrayOf("Start scanning", "Iniciar escaneamento", "Iniciar digitalização"),
        "Сканировать камерой" to arrayOf("Scan with camera", "Escanear com a câmera", "Digitalizar com a câmara"),
        "Сканировать заново" to arrayOf("Scan again", "Escanear novamente", "Digitalizar novamente"),
        "Не вынимайте телефон · покажите лицо камере шлема" to arrayOf("Keep the phone in the headset · show your face to its camera", "Mantenha o celular no headset · mostre o rosto à câmera", "Mantenha o telemóvel no headset · mostre o rosto à câmara"),
        "Смотрите прямо" to arrayOf("Look straight ahead", "Olhe para frente", "Olhe em frente"),
        "Медленно поверните голову влево" to arrayOf("Slowly turn your head left", "Vire a cabeça devagar para a esquerda", "Rode lentamente a cabeça para a esquerda"),
        "Теперь поверните голову вправо" to arrayOf("Now turn your head right", "Agora vire a cabeça para a direita", "Agora rode a cabeça para a direita"),
        "Слегка поднимите подбородок" to arrayOf("Raise your chin slightly", "Levante um pouco o queixo", "Levante ligeiramente o queixo"),
        "Слегка опустите подбородок" to arrayOf("Lower your chin slightly", "Abaixe um pouco o queixo", "Baixe ligeiramente o queixo"),
        "Лицо не видно" to arrayOf("Face not visible", "Rosto não visível", "Rosto não visível"),
        "Лицо в центр рамки" to arrayOf("Center your face in the frame", "Centralize o rosto", "Centre o rosto"),
        "Приблизьте лицо к камере шлема" to arrayOf("Move your face closer to the headset camera", "Aproxime o rosto da câmera do headset", "Aproxime o rosto da câmara do headset"),
        "Камера шлема недоступна" to arrayOf("Headset camera unavailable", "Câmera do headset indisponível", "Câmara do headset indisponível"),
        "Создаю персону…" to arrayOf("Building your Persona…", "Criando sua Persona…", "A criar a sua Persona…"),
        "Покажите руки" to arrayOf("Show your hands", "Mostre as mãos", "Mostre as mãos"),
        "Держите обе руки перед собой, пальцы раскрыты. Не двигайтесь пару секунд." to arrayOf(
            "Hold both hands in front of you with fingers open. Keep still for a few seconds.",
            "Mantenha as duas mãos à frente, com os dedos abertos. Fique parado por alguns segundos.",
            "Mantenha as duas mãos à frente, com os dedos abertos. Fique parado durante alguns segundos."
        ),
        "Не вынимайте телефон из шлема. Покажите лицо внешней камере и медленно поворачивайте голову. Затылок сканировать не нужно." to arrayOf(
            "Keep the phone in the headset. Show your face to its outward camera and slowly turn your head. The back is not needed.",
            "Mantenha o celular no headset. Mostre o rosto à câmera externa e vire a cabeça devagar. Não é preciso mostrar a parte de trás.",
            "Mantenha o telemóvel no headset. Mostre o rosto à câmara externa e rode a cabeça devagar. Não é necessário mostrar a parte de trás."
        ),
        "Следуйте подсказкам камеры: прямо, влево, вправо, вверх и вниз." to arrayOf(
            "Follow the camera prompts: front, left, right, up and down.",
            "Siga as instruções da câmera: frente, esquerda, direita, cima e baixo.",
            "Siga as instruções da câmara: frente, esquerda, direita, cima e baixo."
        ),
        "Настроить границу" to arrayOf("Set up boundary", "Configurar limite", "Configurar limite"),
        "Удалить границу" to arrayOf("Remove boundary", "Remover limite", "Remover limite"),
        "Калибровка рук" to arrayOf("Hand calibration", "Calibração das mãos", "Calibração das mãos"),
        "Автообновление" to arrayOf("Automatic Updates", "Atualizações Automáticas", "Atualizações automáticas"),
        "Бета‑обновления" to arrayOf("Beta Updates", "Atualizações Beta", "Atualizações beta"),
        "Обновить сейчас" to arrayOf("Update Now", "Atualizar Agora", "Atualizar agora"),
        "Установлена последняя версия" to arrayOf("PhoneXR is up to date", "O PhoneXR está atualizado", "O PhoneXR está atualizado"),
        // Calls and friends
        "Позвонить" to arrayOf("Call", "Ligar", "Ligar"),
        "Принять" to arrayOf("Accept", "Aceitar", "Aceitar"),
        "Отклонить" to arrayOf("Decline", "Recusar", "Recusar"),
        "Завершить" to arrayOf("End", "Encerrar", "Terminar"),
        "Микрофон" to arrayOf("Mute", "Microfone", "Microfone"),
        "Отменить" to arrayOf("Cancel", "Cancelar", "Cancelar"),
        "В сети" to arrayOf("Online", "Online", "Online"),
        "Не в сети" to arrayOf("Offline", "Offline", "Offline"),
        "Мои друзья" to arrayOf("My friends", "Meus amigos", "Os meus amigos"),
        "Добавили вас" to arrayOf("Added you", "Adicionaram você", "Adicionaram-no"),
        "Найти по юзернейму" to arrayOf("Find by username", "Buscar por usuário", "Procurar por utilizador"),
        "Сейчас никого нет в сети" to arrayOf("Nobody is online", "Ninguém online agora", "Ninguém online agora"),
        // Elix
        "Спросите Elix" to arrayOf("Ask Elix", "Pergunte à Elix", "Pergunte à Elix"),
        "Слушаю…" to arrayOf("Listening…", "Ouvindo…", "A ouvir…"),
        "Думаю…" to arrayOf("Thinking…", "Pensando…", "A pensar…"),
        "Говорить" to arrayOf("Speak", "Falar", "Falar"),
        "Клавиатура" to arrayOf("Keyboard", "Teclado", "Teclado"),
        "Отправить" to arrayOf("Send", "Enviar", "Enviar"),
        "Новый разговор" to arrayOf("New chat", "Nova conversa", "Nova conversa"),
        "Привет" to arrayOf("Hi", "Oi", "Olá"),
        "Чем помочь?" to arrayOf("How can I help?", "Como posso ajudar?", "Como posso ajudar?"),
        "Отлично! Готова помочь." to arrayOf("Great! Ready to help.", "Ótima! Pronta para ajudar.", "Ótima! Pronta para ajudar."),
        "Пожалуйста!" to arrayOf("You're welcome!", "De nada!", "De nada!"),
        "Я Elix, ассистент PhoneXR." to arrayOf("I'm Elix, the PhoneXR assistant.", "Sou a Elix, a assistente do PhoneXR.", "Sou a Elix, a assistente do PhoneXR."),
        "Сейчас" to arrayOf("It's", "Agora são", "São"),
        "Заряд" to arrayOf("Battery", "Bateria", "Bateria"),
        "Снимаю!" to arrayOf("Taking a photo!", "Tirando foto!", "A tirar fotografia!"),
        "Готово, выровняла вид." to arrayOf("Done, view recentered.", "Pronto, visão recentralizada.", "Feito, vista recentrada."),
        "Обойдите край свободного места." to arrayOf("Walk around the edge of the free space.", "Caminhe pela borda do espaço livre.", "Percorra o limite do espaço livre."),
        "Выхожу из VR." to arrayOf("Leaving VR.", "Saindo do VR.", "A sair de VR."),
        "Открываю." to arrayOf("Opening.", "Abrindo.", "A abrir."),
    )
}

/** The chosen language's text for a Russian UI string. */
fun tr(ru: String) = L10n.tr(ru)
