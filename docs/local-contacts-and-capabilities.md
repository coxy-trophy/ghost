# Local contacts and capability onboarding

v72 adds **Local capabilities** in Ghost's System screen. Contact lookup is off by default and requires both the local switch and Android READ_CONTACTS permission. Entering the capability screen does not read contacts. Notification access remains independently controlled by Nocturne's master switch and selected-app list.

`lookup_contact(query)` opens a private local search screen. Empty, control-containing, and over-100-character queries are rejected; the phone provider's filter URI is used rather than shell commands or SQL interpolation. Search inspects at most 100 cursor rows and displays up to twenty distinct contact/number matches. Multiple matches remain a user choice; selecting a row displays that contact locally without calling, messaging or copying a number.

The tool's return to ActionRunner is fixed status text only. Names/numbers never enter its result, model prompts, chat history or semantic memory. The viewer requires an unlocked interactive device, clears on pause/screen-off, excludes recents/screenshots and does not save its contents to instance state. Access is rechecked after the provider query. Contact consent preferences are excluded from cloud and device-transfer backup so a restored app does not silently enable this capability.

Personal-tool status bookkeeping is converted into plain text only in MessageAdapter's display/copy path. The underlying ActionRunner result remains available for existing memory stripping; no raw notification/contact content is introduced into that path.

The initial capability screen links local contact search, the private notification viewer, Nocturne's app-selection settings and Android assistant settings. Calling, sending messages, notification replies and remote commands are separate capabilities. Local data access does not enable them.

Verification targets: denied/default-off access; granted local search; multiple contacts with similar names; no empty-query bulk lookup; lock/background clearing; permission revocation; no names/numbers in model logs/history; fixed tool outcomes. Source tests cover query bounds and personal-result rendering; phone verification is coordinated by Nocturne.

Final v72 build verification: 367 JVM tests and nine Python checks pass. Optimized release/lint/signature/16K alignment/JNI checks pass; DEX contains none of the compile-only platform definitions. All 20 native libraries match v71, and same-key update preflight passes. APK `app/build/outputs/verified-release/ghost-0.34.0-72.apk`, SHA-256 `e63c97e2a12dffbda642499b1d861bf487b78db9b99ad87a16084762ff512e0b`. Handed to Nocturne for coordinated installation/permission staging and physical validation.
