// The changelog is a build-time artifact: entries are authored here (newest first) and bundled
// into the SPA, so it can only change with a deploy — never at runtime. The newest entry's
// version must equal APP_VERSION in ./version.ts (the shell's eager import — this file stays
// out of the main bundle): a release adds the entry AND bumps that literal; entries.test.ts
// pins the pair. Bodies are markdown, one per language (content, not chrome — hence not in
// locales/); keep the phrases tests assert on in plain text runs, and follow the Polish style
// conventions (inclusive slash forms, active voice).
interface ChangelogEntry {
  version: string;
  /** Release date, YYYY-MM-DD. Keep the array strictly descending by date. */
  date: string;
  /** Markdown body, English. */
  en: string;
  /** Markdown body, Polish. */
  pl: string;
}

export const CHANGELOG: readonly ChangelogEntry[] = [
  {
    version: "1.2.3",
    date: "2026-11-21",
    en: `**Hammurabi 1.2.3 — a clearer Decision log.**

- Compare ADR counts by status in a single grid. Select a count to open the filtered ADR list.
- Read pending reviews, upcoming expirations, and recent decisions in one panel. Reviews that need your decision and overdue ADRs are easy to spot.
- Browse decisions in force by category and tag. The page adapts to narrower screens and supports both themes.`,
    pl: `**Hammurabi 1.2.3 — czytelniejszy rejestr decyzji.**

- Porównaj liczbę ADR-ów według statusu w jednej siatce. Wybierz liczbę, aby otworzyć przefiltrowaną listę ADR-ów.
- Sprawdź oczekujące przeglądy, nadchodzące terminy wygaśnięcia i ostatnie decyzje w jednym panelu. Łatwo zauważysz przeglądy, które wymagają Twojej decyzji, i ADR-y po terminie.
- Przeglądaj obowiązujące decyzje według kategorii i tagów. Widok dopasowuje się do węższych ekranów i działa w obu motywach.`,
  },
  {
    version: "1.2.2",
    date: "2026-11-21",
    en: `**Hammurabi 1.2.2 — safer ADR edits.**

- Withdrawal and successor confirmations act on the ADR revision or link you saw. When someone changes an expiry date or discussion entry while you are editing it, the app asks you to reload before saving.
- The app asks before you leave an unsaved ADR or discussion draft. If editing closes while you are writing, you can still copy your text.
- ADR list totals and ZIP export limits stay consistent when other people make changes. Tag filters validate names before counting distinct tags, and the email digest honors the configured limit.
- For administrators, demo seeding checks that the browser endpoint and both containers belong to the same Compose stack. The backup and restore drill now tests upgrading existing discussion entries to their own revisions.`,
    pl: `**Hammurabi 1.2.2 — bezpieczniejsza edycja ADR-ów.**

- Potwierdzenie wycofania lub odłączenia następcy dotyczy wersji ADR-u albo powiązania widocznego w chwili otwarcia okna. Jeśli ktoś zmieni datę wygaśnięcia lub wpis w dyskusji w trakcie Twojej edycji, aplikacja poprosi o ponowne wczytanie przed zapisem.
- Aplikacja pyta o potwierdzenie przed opuszczeniem niezapisanego szkicu ADR-u lub wpisu w dyskusji. Jeśli możliwość edycji skończy się w trakcie pisania, nadal możesz skopiować swój tekst.
- Liczba wyników na liście ADR-ów i limit eksportu ZIP pozostają spójne, gdy inne osoby wprowadzają zmiany. Filtry sprawdzają nazwy tagów przed zliczeniem unikatowych wartości, a zbiorcze e-maile uwzględniają ustawiony limit.
- Dla administratorów/ek: skrypt danych przykładowych sprawdza, czy adres aplikacji i oba kontenery należą do tego samego projektu Compose. Próba kopii zapasowej i odtwarzania sprawdza teraz, czy po aktualizacji istniejące wpisy w dyskusji otrzymują własne wersje.`,
  },
  {
    version: "1.2.1",
    date: "2026-11-21",
    en: `**Hammurabi 1.2.1: tidy-up.**

- The history, the discussion and the notification list share one compact page switcher, and empty lists look the same everywhere.
- New notifications and what's new are marked in the app's own colour instead of red, which stays reserved for things that block you.
- If the app cannot load your account details, the account menu now says so instead of staying silent.
- Removing or demoting the last administrator is explained more reliably.
- Behind the scenes: tidier code and stricter automated checks, so mistakes are caught before a release.
- The memory labels of the telemetry panel are now translated into Polish.`,
    pl: `**Hammurabi 1.2.1: porządki.**

- Historia, dyskusja i lista powiadomień mają jeden wspólny, zwarty przełącznik stron, a puste listy wyglądają wszędzie tak samo.
- Nowe powiadomienia i nowości są oznaczone kolorem aplikacji zamiast czerwieni, która pozostaje zarezerwowana dla tego, co Cię blokuje.
- Jeśli aplikacja nie może wczytać danych Twojego konta, menu konta mówi o tym, zamiast milczeć.
- Usunięcie albo odebranie uprawnień ostatniemu administratorowi/ostatniej administratorce jest wyjaśniane bardziej niezawodnie.
- Za kulisami: uporządkowany kod i surowsze automatyczne kontrole, które wyłapują błędy przed wydaniem.
- Etykiety pamięci w panelu telemetrii są przetłumaczone.`,
  },
  {
    version: "1.2.0",
    date: "2026-11-21",
    en: `**Hammurabi 1.2.0: safety net.**

- One person's changes can no longer flood everyone's inbox. Each account may cause 200 notifications a day; past that, the people concerned still see every notification in the bell, but no email is sent for it. Notifications the app makes on its own (an ADR expiring, an expiry reminder) count towards the ADR's Approver, and an expiry reminder comes at most once a day.
- Changes to ADRs and their discussion are limited to 120 a minute per person. Past that, the app asks you to wait a moment instead of failing.
- When an ADR is cancelled, withdrawn or expires while another ADR is waiting to replace it, that successor's Driver and Approver are now told, and its history says so. The ended ADR no longer lists it as its successor; the successor keeps the link until its Driver removes it.
- For administrators: scripts to back up the database and restore it, with a step-by-step guide and a rehearsal that checks the whole round trip.`,
    pl: `**Hammurabi 1.2.0: siatka bezpieczeństwa.**

- Zmiany jednej osoby nie zaleją już niczyjej skrzynki. Każde konto może wywołać 200 powiadomień dziennie; ponad ten limit osoby, których to dotyczy, nadal widzą każde powiadomienie w dzwonku, ale nie dostają już o nim e-maila. Powiadomienia, które aplikacja tworzy sama (wygaśnięcie ADR-u, przypomnienie o wygaśnięciu), liczą się do limitu Zatwierdzającego/ej tego ADR-u, a przypomnienie o wygaśnięciu przychodzi najwyżej raz dziennie.
- Zmiany w ADR-ach i w ich dyskusji są ograniczone do 120 na minutę na osobę. Powyżej tego limitu aplikacja prosi o chwilę cierpliwości zamiast zgłaszać błąd.
- Gdy ADR zostaje anulowany, wycofany albo wygasa, a inny ADR czeka, żeby go zastąpić, Prowadzący/a i Zatwierdzający/a tego następcy dostają teraz powiadomienie, a jego historia to odnotowuje. Zakończony ADR nie wskazuje go już jako następcy; następca zachowuje powiązanie, dopóki jego Prowadzący/a go nie usunie.
- Dla administratorów/ek: skrypty do kopii zapasowej bazy danych i jej odtwarzania, z instrukcją krok po kroku i próbą, która sprawdza całą drogę w obie strony.`,
  },
  {
    version: "1.1.0",
    date: "2026-11-21",
    en: `**Hammurabi 1.1.0: write and see.**

- The long text fields (the problem, context, decision, alternatives and consequences, and the discussion) are now visual editors: bold, lists, headings, links, tables and code blocks look like they will on the page while you type. The Markdown view is one click away, and the app remembers which one you prefer.
- Text the visual editor cannot show (for example raw HTML or images) opens in the Markdown view, so nothing is lost. Opening an ADR and saving it without typing changes nothing in its history.
- The new Lifecycle tab on every ADR draws the whole workflow: every status, every move between them and who may make it. It marks where the ADR is now and highlights the moves you can make yourself; the same information is listed under the picture.`,
    pl: `**Hammurabi 1.1.0: pisz i patrz.**

- Długie pola tekstowe (problem, kontekst, decyzja, alternatywy i konsekwencje, a także dyskusja) są teraz edytorami wizualnymi: pogrubienia, listy, nagłówki, odnośniki, tabele i bloki kodu wyglądają podczas pisania tak jak na stronie. Widok Markdown jest o jedno kliknięcie, a aplikacja zapamiętuje, który wolisz.
- Tekst, którego edytor wizualny nie potrafi pokazać (na przykład surowy HTML albo obrazy), otwiera się w widoku Markdown, więc nic nie ginie. Otwarcie ADR-u i zapisanie go bez pisania nie zmienia niczego w jego historii.
- Nowa karta Cykl życia w każdym ADR-ze rysuje cały przebieg: każdy status, każde przejście między nimi i to, kto może je wykonać. Zaznacza, gdzie ADR jest teraz, i wyróżnia przejścia, które możesz wykonać sam/a; te same informacje są wypisane pod rysunkiem.`,
  },
  {
    version: "1.0.0",
    date: "2026-11-21",
    en: `**Hammurabi 1.0.0: the checkup.**

- A decision in force can no longer be ended behind its Approver's back. Only the Approver or an administrator changes the expiry date of an active ADR, and everyone on it is told when it changes.
- A successor now replaces an active ADR only after that ADR's Approver (or an administrator) accepts it. Until then the link is a proposal. Links that were waiting when you upgrade need that acceptance too.
- Sign-in is harder to attack: repeated wrong codes lock an account for longer, password changes are rate-limited, and unknown addresses are no longer written to the audit log.
- Every page now has its own browser tab title, and times are shown in your own time zone.
- The ADR list loads lighter, long histories read faster, and unused code is gone.
- For API clients (the API is now frozen): people are named \`…Id\`/\`…Name\`/\`…Deleted\` everywhere. The list filters are now \`participantId\`, \`participantRole\`, \`hasPredecessor\` and \`hasSuccessor\`. A discussion entry has a \`state\`, and notification preferences use the same shape for reading and saving. Saved lenses were converted, and old links still work.`,
    pl: `**Hammurabi 1.0.0: przegląd.**

- Obowiązującej decyzji nie da się już zakończyć za plecami osoby zatwierdzającej. Datę wygaśnięcia aktywnego ADR-u zmienia tylko Zatwierdzający/a albo administrator/ka, a wszyscy jego uczestnicy dostają o tym powiadomienie.
- Następca zastępuje aktywny ADR dopiero wtedy, gdy Zatwierdzający/a tego ADR-u (albo administrator/ka) go zaakceptuje. Do tego czasu powiązanie jest tylko propozycją. Powiązania oczekujące w chwili aktualizacji też wymagają takiej akceptacji.
- Logowanie jest lepiej chronione: powtarzane błędne kody blokują konto na dłużej, zmiany hasła mają limit, a nieznane adresy nie trafiają już do dziennika audytu.
- Każda strona ma własny tytuł karty w przeglądarce, a godziny są pokazywane w Twojej strefie czasowej.
- Lista ADR-ów ładuje się lżej, długie historie czytają się szybciej, a nieużywany kod zniknął.
- Dla klientów API (API jest teraz zamrożone): osoby są nazywane wszędzie \`…Id\`/\`…Name\`/\`…Deleted\`. Filtry listy to teraz \`participantId\`, \`participantRole\`, \`hasPredecessor\` i \`hasSuccessor\`. Wpis dyskusji ma pole \`state\`, a preferencje powiadomień mają ten sam kształt przy odczycie i zapisie. Zapisane widoki zostały przekonwertowane, a stare linki nadal działają.`,
  },
  {
    version: "0.9.0",
    date: "2026-11-21",
    en: `**Hammurabi 0.9.0: the decision log.**

- The new Decision log page, between Home and the ADRs, shows the whole log at a glance: how many ADRs are in each status, what is waiting for a decision, what expires soon, what was decided recently and what is in force.
- ADRs in review are listed with the longest wait first, and the ones waiting for you as the Approver are highlighted, with a link to your own review queue.
- The decisions in force are grouped by tag category and tag (an ADR appears under each of its tags, and ADRs without a tag have their own group). Each group shows its newest ADRs and a "+N more" link to the ADR list filtered to the rest.
- Expiring soon looks 30 days ahead by default and puts any date that has already passed first, marked Overdue.`,
    pl: `**Hammurabi 0.9.0: rejestr decyzji.**

- Nowa strona Rejestr decyzji, między stroną główną a listą ADR-ów, pokazuje cały rejestr na jednym ekranie: ile ADR-ów jest w każdym statusie, co czeka na decyzję, co wkrótce wygasa, co niedawno rozstrzygnięto i co obowiązuje.
- ADR-y w przeglądzie są wymienione od najdłużej czekających, a te, które czekają na Ciebie jako Zatwierdzającego/ą, są wyróżnione i prowadzą do Twojej kolejki przeglądów.
- Obowiązujące decyzje są pogrupowane według kategorii tagów i tagów (ADR pojawia się pod każdym ze swoich tagów, a ADR-y bez tagu mają własną grupę). Każda grupa pokazuje najnowsze ADR-y i odnośnik „+N więcej” do listy ADR-ów przefiltrowanej do pozostałych.
- Wkrótce wygasające domyślnie sięgają 30 dni w przód, a daty już minione są na początku i oznaczone jako po terminie.`,
  },
  {
    version: "0.8.0",
    date: "2026-11-21",
    en: `**Hammurabi 0.8.0: Markdown export.**

- The Export Markdown button on an ADR downloads it as one Markdown file in the adr-tools layout (status, context, decision, consequences), with the alternatives, the people by role and the tags added, and links to the ADR it replaces or is replaced by. Anyone who can read an ADR can export it.
- The Export (ZIP) button on the ADR list downloads every ADR the current search and filters select (up to 500) as one file each, plus an index.md listing them in number order. Narrow the filters if you hit the limit.
- File names are plain ASCII (ADR-0042-use-postgresql.md), so they travel safely between systems.`,
    pl: `**Hammurabi 0.8.0: eksport do Markdown.**

- Przycisk Eksportuj Markdown przy ADR-ze pobiera go jako jeden plik Markdown w układzie adr-tools (status, kontekst, decyzja, konsekwencje), z dodanymi rozważanymi alternatywami, osobami według ról i tagami oraz z odnośnikami do ADR-a, który zastępuje lub przez który został zastąpiony. Każdy, kto może przeczytać ADR, może go wyeksportować.
- Przycisk Eksportuj (ZIP) na liście ADR-ów pobiera wszystkie ADR-y wybrane przez bieżące wyszukiwanie i filtry (do 500), każdy jako osobny plik, wraz z plikiem index.md z listą w kolejności numerów. Jeśli trafisz na limit, zawęź filtry.
- Nazwy plików składają się wyłącznie ze znaków ASCII (ADR-0042-use-postgresql.md), więc bezpiecznie wędrują między systemami.`,
  },
  {
    version: "0.7.0",
    date: "2026-11-21",
    en: `**Hammurabi 0.7.0: send back and withdraw.**

- The Approver or the Driver can now send an ADR in review back to draft. Nothing is cleared (the content, the people and the tags stay), the Approver has to review it again, and sending it to review again re-checks the required fields.
- The Approver can withdraw a decision that is already in force. The ADR is closed for good, its open issues can still be resolved, and an ADR that was set to replace it cannot be activated until that link is removed.
- Sending back, withdrawing and cancelling ask for an optional reason, which the History shows under the move. Notifications and emails only say that the ADR was sent back or withdrawn, never why.
- Nobody can make themselves the Approver; once an ADR has been in review, only its Approver or an administrator can hand the role on.`,
    pl: `**Hammurabi 0.7.0: odsyłanie i wycofywanie.**

- Zatwierdzający/a lub Prowadzący/a może teraz odesłać ADR z przeglądu z powrotem do szkicu. Nic nie jest czyszczone (treść, osoby i tagi zostają), Zatwierdzający/a musi przejrzeć go ponownie, a ponowne wysłanie do przeglądu ponownie sprawdza wymagane pola.
- Zatwierdzający/a może wycofać decyzję, która już obowiązuje. ADR zostaje zamknięty na stałe, jego otwarte zgłoszenia nadal można rozwiązać, a ADR, który miał go zastąpić, nie może zostać aktywowany, dopóki to powiązanie nie zostanie usunięte.
- Odesłanie, wycofanie i anulowanie pytają o opcjonalny powód, który Historia pokazuje pod zmianą. Powiadomienia i e-maile mówią tylko, że ADR odesłano lub wycofano, nigdy dlaczego.
- Nikt nie może sam siebie uczynić Zatwierdzającym/ą; gdy ADR był już w przeglądzie, tylko jego Zatwierdzający/a lub administrator może przekazać tę rolę dalej.`,
  },
  {
    version: "0.6.0",
    date: "2026-11-21",
    en: `**Hammurabi 0.6.0: notify.**

- The bell in the header shows how many notifications you have not seen. Open it to read them, click one to go to its ADR (it is marked as seen), or mark them all as seen.
- You are told when you are given a role on an ADR or lose it, when an ADR you take part in changes status, expires or is superseded, and when a successor is linked to or detached from your ADR.
- Drivers and Approvers are told about new comments, questions, issues and escalations on their ADRs (Contributors too for comments and questions); the author hears when a question is answered or an issue resolved. You never get a notification for your own actions.
- Drivers and Approvers get a reminder two weeks before an active ADR expires, once for each expiry date.
- Notifications you have not seen after a few minutes are mailed to you in one digest, in your language. The new Notification preferences page, in the account menu, switches each kind off for the bell and for email separately.`,
    pl: `**Hammurabi 0.6.0: powiadomienia.**

- Dzwonek w nagłówku pokazuje, ile powiadomień jeszcze nie widziałeś/aś. Otwórz go, aby je przeczytać, kliknij powiadomienie, aby przejść do jego ADR-a (zostanie oznaczone jako przeczytane), albo oznacz wszystkie jako przeczytane.
- Dostajesz powiadomienie, gdy przypisano Ci rolę w ADR-ze lub ją utraciłeś/aś, gdy ADR, w którym uczestniczysz, zmienia status, wygasa lub zostaje zastąpiony, oraz gdy do Twojego ADR-a podłączono lub od niego odłączono następcę.
- Prowadzący/a i Zatwierdzający/a dostają powiadomienia o nowych komentarzach, pytaniach, zgłoszeniach i eskalacjach w swoich ADR-ach (Współtwórcy/czynie także o komentarzach i pytaniach); autor/ka dowiaduje się, gdy odpowiedziano na pytanie lub rozwiązano zgłoszenie. Nigdy nie dostajesz powiadomienia o własnych działaniach.
- Prowadzący/a i Zatwierdzający/a dostają przypomnienie na dwa tygodnie przed wygaśnięciem obowiązującego ADR-a, raz dla każdej daty wygaśnięcia.
- Powiadomienia, których nie przeczytasz w ciągu kilku minut, przychodzą e-mailem w jednym podsumowaniu, w Twoim języku. Nowa strona Preferencje powiadomień, w menu konta, wyłącza każdy rodzaj osobno dla dzwonka i dla e-maila.`,
  },
  {
    version: "0.5.0",
    date: "2026-11-21",
    en: `**Hammurabi 0.5.0: find.**

- The ADR list has a Search box: it looks through the name and the whole text of every ADR, ignores case and accents, ranks the best matches first and highlights what matched.
- The Filters panel adds created and updated date ranges, an expiry bound, and yes/no filters for open issues, "replaces another ADR" and "has a successor". Every filter combines with the others.
- A filtered or searched list is a link: copy the address to share the same view.
- Save a combination of filters as a lens, private or shared with everyone, and apply it in one click. Only its creator changes a lens; an administrator can remove a shared one.`,
    pl: `**Hammurabi 0.5.0: wyszukiwanie.**

- Lista ADR-ów ma pole Szukaj: przeszukuje nazwę i całą treść każdego ADR-a, ignoruje wielkość liter i polskie znaki, układa najlepsze trafienia na początku i podświetla to, co pasuje.
- Panel Filtry dodaje zakresy dat utworzenia i zmiany, granicę wygaśnięcia oraz filtry tak/nie: otwarte zgłoszenia, „zastępuje inny ADR” i „ma następcę”. Każdy filtr łączy się z pozostałymi.
- Przefiltrowana lub przeszukana lista to link: skopiuj adres, aby udostępnić ten sam widok.
- Zapisz zestaw filtrów jako soczewkę, prywatną albo udostępnioną wszystkim, i zastosuj ją jednym kliknięciem. Soczewkę zmienia tylko jej autor/ka; administrator/ka może usunąć udostępnioną.`,
  },
  {
    version: "0.4.0",
    date: "2026-11-21",
    en: `**Hammurabi 0.4.0: tags and successors.**

- Administrators curate tag categories on the new Tags page (seeded with Domain and Scope); each tag belongs to exactly one category.
- The Driver, the Approver or an administrator tags an ADR, also while it is active; the ADR list shows the tags and filters by them.
- A removed tag stays visible, struck through, on the ADRs that carry it until they are retagged.
- A draft can name the ADR it replaces. When it becomes active, the old ADR becomes Superseded, and both pages link to each other.
- A link that would close a loop is refused. The Driver, the Approver or an administrator of the old ADR can detach a successor that is still pending.
- The history records tag changes, successor links and the replacement.`,
    pl: `**Hammurabi 0.4.0: tagi i następcy.**

- Administratorzy/rki porządkują kategorie tagów na nowej stronie Tagi (na start: Domain i Scope); każdy tag należy do dokładnie jednej kategorii.
- Prowadzący/a, Zatwierdzający/a albo administrator/ka taguje ADR, także obowiązujący; lista ADR-ów pokazuje tagi i filtruje po nich.
- Usunięty tag pozostaje widoczny, przekreślony, na ADR-ach, które go mają, dopóki nie zostaną przetagowane.
- Szkic może wskazać ADR, który zastępuje. Gdy stanie się obowiązujący, stary ADR otrzymuje status Zastąpiony, a obie strony wskazują na siebie nawzajem.
- Powiązanie, które zamknęłoby pętlę, jest odrzucane. Prowadzący/a, Zatwierdzający/a albo administrator/ka starego ADR-u może odłączyć oczekującego następcę.
- Historia zapisuje zmiany tagów, powiązania z następcami i zastąpienie.`,
  },
  {
    version: "0.3.0",
    date: "2026-11-20",
    en: `**Hammurabi 0.3.0: discussion.**

- Every ADR page now has a Discussion tab: while an ADR is being drafted and reviewed, anyone can post a comment or ask a question, in Markdown with a preview.
- A question can be marked answered by its author, the Driver, the Approver or an administrator, and reopened.
- Once an ADR is active, the Issues tab takes issues and escalations. The Driver, the Approver or an administrator resolves one, optionally with a note, and can reopen it.
- Authors edit their own entries; an author or an administrator can delete one, which leaves a "deleted" placeholder. Everything stays readable in every status.
- The tabs show the unanswered questions and open issues, the ADR list has an Open issues column, and the history records every comment, question, issue and resolution.`,
    pl: `**Hammurabi 0.3.0: dyskusja.**

- Każdy ADR ma teraz kartę Dyskusja: gdy ADR jest szkicowany i przeglądany, każdy/a może dodać komentarz albo zadać pytanie, w Markdownie z podglądem.
- Pytanie może oznaczyć jako z odpowiedzią jego autor/ka, Prowadzący/a, Zatwierdzający/a albo administrator/ka, a także otworzyć je ponownie.
- Gdy ADR jest obowiązujący, karta Zgłoszenia przyjmuje zgłoszenia i eskalacje. Prowadzący/a, Zatwierdzający/a albo administrator/ka rozwiązuje zgłoszenie, opcjonalnie z uwagą, i może je otworzyć ponownie.
- Autorzy/ki edytują własne wpisy; autor/ka albo administrator/ka może wpis usunąć, a wtedy zostaje informacja „usunięto”. Wszystko pozostaje do przeczytania w każdym statusie.
- Karty pokazują pytania bez odpowiedzi i otwarte zgłoszenia, lista ADR-ów ma kolumnę Otwarte zgłoszenia, a historia zapisuje każdy komentarz, pytanie, zgłoszenie i rozwiązanie.`,
  },
  {
    version: "0.2.0",
    date: "2026-11-20",
    en: `**Hammurabi 0.2.0: Architecture Decision Records.**

- Raise an ADR with just its problem; it gets a number such as ADR-0001, and you become its Driver.
- Each ADR moves from problem raised through solution draft and review to active, or ends cancelled, withdrawn, expired or superseded. The buttons show only the moves your role allows.
- Responsibility follows DACI: one Driver, one Approver, and any number of Contributors and Informed people. The Driver and Contributors write the content, the Approver decides.
- Write the problem, context, decision, alternatives and consequences in Markdown, with a preview. Once an ADR is active its content is frozen; only the expiry date and the participants can still change.
- An active ADR expires on its own the day after its expiry date.
- Every change lands in the ADR's history, with who did it and when, in your language. If someone else changed the ADR first, you are offered a reload instead of overwriting their work.
- Browse the ADRs by name or key, status, or the ones you take part in; the filters live in the address, so a link reproduces them.`,
    pl: `**Hammurabi 0.2.0: rekordy decyzji architektonicznych.**

- Zgłoś ADR, podając tylko problem; dostaje numer, na przykład ADR-0001, a Ty zostajesz jego Prowadzącym/ą.
- Każdy ADR przechodzi od zgłoszonego problemu przez szkic rozwiązania i przegląd do statusu obowiązującego albo kończy jako anulowany, wycofany, wygasły lub zastąpiony. Przyciski pokazują tylko ruchy, które dopuszcza Twoja rola.
- Odpowiedzialność wynika z DACI: jeden/a Prowadzący/a, jeden/a Zatwierdzający/a oraz dowolnie wiele osób Współtworzących i Informowanych. Prowadzący/a i Współtwórcy/czynie piszą treść, Zatwierdzający/a decyduje.
- Pisz problem, kontekst, decyzję, alternatywy i konsekwencje w Markdownie, z podglądem. Gdy ADR jest obowiązujący, jego treść jest zamrożona; zmienić można jeszcze tylko datę wygaśnięcia i uczestników/czki.
- Obowiązujący ADR wygasa sam w dniu po dacie wygaśnięcia.
- Każda zmiana trafia do historii ADR-a, z informacją, kto jej dokonał i kiedy, w Twoim języku. Jeśli ktoś zmienił ADR przed Tobą, dostajesz propozycję ponownego wczytania zamiast nadpisania cudzej pracy.
- Przeglądaj ADR-y według nazwy lub klucza, statusu albo tych, w których bierzesz udział; filtry są zapisane w adresie, więc link je odtwarza.`,
  },
  {
    version: "0.1.0",
    date: "2026-11-17",
    en: `**Hammurabi 0.1.0: the foundation.**

- Sign in with your email and password, with an emailed one-time code as the second step (email MFA), and reset a forgotten password by email.
- Administrators manage users, with their feature flags and language; every user can change their own password.
- Feature flags, and teams, each with an optional manager.
- Light and dark themes, in English and Polish, and this changelog.
- An administrator's telemetry panel with the memory, storage, request and JVM figures of the server.
- The lapis lazuli Hammurabi brand, named for the glazed bricks of the Ishtar Gate in Babylon, and the Stele logo, after the basalt stele of the Code of Hammurabi.
- Architecture Decision Records (ADRs) arrive in the next version.`,
    pl: `**Hammurabi 0.1.0: fundament.**

- Zaloguj się adresem e-mail i hasłem, z jednorazowym kodem wysłanym e-mailem jako drugim krokiem (MFA e-mailem), a zapomniane hasło zresetuj e-mailem.
- Administratorzy/rki zarządzają użytkownikami/czkami, ich flagami funkcji i językiem; każda osoba może zmienić własne hasło.
- Flagi funkcji oraz zespoły, każdy z opcjonalnym kierownikiem/czką.
- Jasny i ciemny motyw, po angielsku i po polsku, oraz ten dziennik zmian.
- Panel telemetrii dla administratorów/rek z danymi serwera o pamięci, przestrzeni dyskowej, żądaniach i JVM.
- Marka Hammurabi w kolorze lazurytu (lapis lazuli), od szkliwionych cegieł Bramy Isztar w Babilonie, oraz logo Stela, od bazaltowej steli Kodeksu Hammurabiego.
- Rekordy decyzji architektonicznych (ADR) pojawią się w kolejnej wersji.`,
  },
];
