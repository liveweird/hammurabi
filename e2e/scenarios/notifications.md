# Notifications (the bell, the digest and the preferences)

- **Spec**: [tests/notifications.spec.ts](../tests/notifications.spec.ts)
- **Actors**: the seed administrator, only through the API to create and delete the cast; two throwaway users — a Driver (raises the ADR
  and moves it through the API) and a recipient (named its Approver) who reads the bell, the preferences page and Mailpit
- **Owns** (exclusive server-side state): its two throwaway users (unique `E2E Notifier-*` / `E2E Recipient-*` names) and the one ADR
  (unique `e2e-adr-*` name) the Driver raises — deleted through the API after each test, which removes their notices with them. Notices are
  per recipient, so no other spec sees them. The digest test needs a stack whose digest is fast (`e2e/README.md` "Run").

## Scenario: the bell shows a role another user gave you, and its notice opens the ADR and is marked seen

1. The Driver raises an ADR naming the recipient its Approver (through the API); the recipient signs in.
   - *Expected*: the header bell is named "Notifications (1 unseen)".
2. They open the bell.
   - *Expected*: a drawer lists "<Driver> gave you a role on ADR-nnnn: Approver" with the ADR's name beneath.
3. They click the notice.
   - *Expected*: the ADR page opens (`/adrs/<id>`) and the bell reads "Notifications (0 unseen)".

## Scenario: a transition of your ADR shows in the bell, and Mark all as seen clears the count

1. The Driver sends the ADR to review (through the API); the recipient signs in.
   - *Expected*: the bell reads "Notifications (2 unseen)".
2. They open the bell.
   - *Expected*: both "<Driver> changed the status of ADR-nnnn to In review" and the role notice are listed.
3. They click **Mark all as seen** and close the drawer.
   - *Expected*: the button is gone and the bell reads "Notifications (0 unseen)".

## Scenario: a notice that stays unseen is mailed to the recipient in one digest

1. The recipient never opens the app.
2. Mailpit is polled for a message to the recipient whose subject starts "Hammurabi: ".
   - *Expected*: it arrives within a minute (on the fast-digest stack).
   - *Expected*: the body says "<Driver> made you Approver of ADR-nnnn" and carries the ADR's `/adrs/<id>` link, and contains neither the ADR's
     name nor any of its text.

## Scenario: switching a kind off on the preferences page stops its notices for the bell

1. The recipient opens **Notification preferences** and switches "Bell: An ADR changes status" off, then **Save**.
   - *Expected*: "Notification preferences saved"; after a reload the switch is still off.
2. The Driver sends the ADR to review (through the API); the recipient opens the home page and the bell.
   - *Expected*: only the role notice is unseen ("Notifications (1 unseen)") and no "changed the status" notice is listed.

## Not covered here (and why)

- **Who is told what, never the actor, the language of the mail, the expiry reminder and the retention** — the server's `NotificationsTest`,
  `NotificationDigestTest`, `ExpiryRemindersTest` and `NotificationRoutesTest`; this spec asserts what a person sees and receives.
- **The bell and preferences page under axe** — `accessibility.spec.ts`.
