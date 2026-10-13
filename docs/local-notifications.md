# Local notification viewer

v71 adds the `read_notifications` action and an authenticated on-demand client for Nocturne's notification bridge. Nocturne controls access with an off-by-default switch and an initially empty selected-app list. Ghost also requires an interactive, unlocked phone. No outgoing notification reply action is added.

The client sends protocol version 1 and a fresh UUID to `com.coxy.nocturne.ui.ghost.NOTIFICATIONS_QUERY`, explicitly targeting Nocturne and sharing Android sender identity. Its temporary receiver accepts `NOTIFICATIONS_RESULT` only from the expected package and Android-provided UID, with the matching version and request ID. Unsolicited, late, malformed or oversized data is ignored. The receiver is removed on completion, cancellation or the ten-second timeout.

`result_json` contains fixed status and at most twenty rows (`package`, `title`, `text`, `postTime`). The client independently enforces 32,000 UTF-8 bytes, 200 title characters, 500 text characters, valid package names and nonnegative integer timestamps. Failure statuses cannot contain rows. Nocturne excludes secret, ongoing, group-summary and other-user notifications and rechecks its access/lock policy before responding.

## Local-only data flow

This is a terminal display action, not a LookupTurn. The snapshot is never supplied to an LLM, ActionResult, cloud prompt, chat history or semantic memory. The executor returns only fixed status text. A private, nonexported activity consumes a one-use three-second in-memory ticket; the Intent contains only that random ticket. Notification content is not saved to disk or instance state. The screen clears and closes when backgrounded or the screen turns off. It is excluded from recents and uses FLAG_SECURE.

The initial version displays notification text locally. It does not generate a cloud summary or speak notification bodies through a potentially network-backed TTS engine. Those are separate capabilities requiring explicit data-sharing choices. Permission revocation after a snapshot has already been displayed does not retroactively erase what the user has seen; new requests always recheck consent in Nocturne.

## Device verification

Nocturne owns deployment. Verify disabled access, empty selection, selected-app listing, wrong sender/replayed request rejection, locked/screen-off denial, closing/rotation behavior, and successful display without any raw notification text in Ghost model logs or memory. Test `read_notifications` through an explicit user request. The tool must report failure if Android blocks the viewer instead of claiming it was shown. Opening acknowledgment comes from the viewer itself, with a three-second deadline.

Client regression tests cover sender identity, malformed/oversized snapshots, forbidden rows on failure, byte limits, and single-use/expired/replaced handoffs. Physical verification remains pending.

Build verification: 353 JVM tests pass; optimized release/lint/signature/16K alignment/JNI retention pass; all 20 native libraries match v70 and same-key update preflight passes. APK `app/build/outputs/verified-release/ghost-0.33.1-71.apk`, SHA-256 `b313841b5cede2ccc470668dc9d44870b672f444a57d8e84b7f0113cee49ae38`. Handed to Nocturne for device validation.
