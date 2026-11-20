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
