package ch.nokillswit.notifications

// The digest's statements (`NotificationDigest.kt`): constant text, every parameter cast; `persistence.md` "Notices".

/**
 * The notices a digest may mail, one fragment for the claim (alias `c`, three parameters: the delay cutoff, the day floor, the people
 * inside the interval): unseen, unclaimed, older than the delay, younger than a day (nothing older is ever mailed), of an ACTIVE
 * account, of a kind the person has NOT switched off for email, and not of a person already mailed within the interval (their notices
 * stay unclaimed and ride one later email).
 */
private const val DUE = """c.seen_at IS NULL AND c.emailed_at IS NULL
      AND c.created_at <= CAST(? AS int8) AND c.created_at > CAST(? AS int8)
      AND c.recipient_id <> ALL(CAST(? AS int8[]))
      AND EXISTS (SELECT 1 FROM users u WHERE u.id = c.recipient_id AND u.marked_as_deleted = false)
      AND NOT EXISTS (SELECT 1 FROM user_notification_preferences p
                      WHERE p.user_id = c.recipient_id AND p.channel = 'EMAIL' AND p.notification_type = c.notification_type)"""

/**
 * The claim (`persistence.md` "Notices"): ONE statement, constant text. The [DUE] notices, the oldest `?` of them ([DIGEST_SCAN_CAP]),
 * are grouped by person and the people taken in the order of their OLDEST waiting notice (at most `?` people: a flooded backlog is one
 * person's slot, never everybody's); ALL of a taken person's notices in the scan are locked `FOR UPDATE SKIP LOCKED` (a row another
 * process has claimed, or is claiming, is skipped, never waited for) and stamped `emailed_at` in the same statement, so a flooded
 * person's backlog is one digest and not a mail per tick: a notice is claimed, hence mailed, at most once. Each row comes back with its
 * rank among the person's notices, oldest first (the digest lists the first [DIGEST_MAX_NOTICES_PER_RECIPIENT] and counts the rest).
 * `MATERIALIZED` keeps each step to one evaluation. The scan is bounded by the cap, not by the people limit. Every parameter is cast.
 */
internal const val CLAIM = """
WITH eligible AS MATERIALIZED (
    SELECT c.id, c.recipient_id, c.created_at FROM notifications c WHERE $DUE
    ORDER BY c.created_at, c.id LIMIT CAST(? AS int4)),
people AS MATERIALIZED (
    SELECT recipient_id, MIN(created_at) AS oldest FROM eligible
    GROUP BY recipient_id ORDER BY oldest, recipient_id LIMIT CAST(? AS int4)),
ranked AS MATERIALIZED (
    SELECT e.id, row_number() OVER (PARTITION BY e.recipient_id ORDER BY e.created_at, e.id) AS rn
    FROM eligible e JOIN people ON people.recipient_id = e.recipient_id),
picked AS MATERIALIZED (
    SELECT n.id, r.rn FROM notifications n JOIN ranked r ON r.id = n.id
    WHERE n.seen_at IS NULL AND n.emailed_at IS NULL
    ORDER BY n.id FOR UPDATE OF n SKIP LOCKED)
UPDATE notifications n SET emailed_at = CAST(? AS int8)
FROM picked WHERE n.id = picked.id
RETURNING n.id, n.recipient_id, n.adr_id, n.other_adr_id, n.notification_type, n.actor_id, n.detail, picked.rn"""

/**
 * The settle, in the claim's transaction, before the claim: of the oldest `?` due notices ([DIGEST_SCAN_CAP], read once, so the
 * statement never walks more), the ones a digest can never mail (the kind switched off for email, or an account that is gone) are
 * stamped `emailed_at`, so they leave `idx_notifications_email_due` for good and never fill a scan again. A person who switches the
 * kind back on gets the notices made from then on. A never-mailable notice deeper than the cap waits until the head of the window has
 * drained.
 */
internal const val SETTLE = """
WITH scanned AS MATERIALIZED (
    SELECT x.id, x.recipient_id, x.notification_type FROM notifications x
    WHERE x.seen_at IS NULL AND x.emailed_at IS NULL AND x.created_at <= CAST(? AS int8) AND x.created_at > CAST(? AS int8)
    ORDER BY x.created_at, x.id LIMIT CAST(? AS int4)),
doomed AS MATERIALIZED (
    SELECT n.id FROM notifications n
    WHERE n.id IN (
        SELECT c.id FROM scanned c
        WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.id = c.recipient_id AND u.marked_as_deleted = false)
           OR EXISTS (SELECT 1 FROM user_notification_preferences p
                      WHERE p.user_id = c.recipient_id AND p.channel = 'EMAIL' AND p.notification_type = c.notification_type))
      AND n.seen_at IS NULL AND n.emailed_at IS NULL
    ORDER BY n.id FOR UPDATE SKIP LOCKED)
UPDATE notifications n SET emailed_at = CAST(? AS int8)
FROM doomed WHERE n.id = doomed.id
RETURNING n.id"""

internal data class ClaimedNotice(
    val id: UInt,
    val recipientId: UInt,
    val adrId: UInt,
    val otherAdrId: UInt?,
    val type: NotificationType,
    /** Null = the system. */
    val actorId: UInt?,
    val detail: String?,
    /** 1-based rank among the person's notices of this claim, oldest first. */
    val rank: Long,
)
