# Forum sign-in

This maintained Plan fork can sign players in through the Nameless PlanAuth module. A player confirms
their active forum account and its verified Minecraft identity on the forum, then returns to the
appropriate Plan page. Forum passwords and MFA codes stay on the forum. Discord authentication is not involved.

An operator can explicitly map an immutable forum subject to an existing Plan web group in the
protected `forum-auth.yml`. That path uses the group's current exact permissions without creating a
local Plan password account. Unmapped identities still inherit an exactly-one existing Plan account
linked to the verified Minecraft UUID when present. Minecraft or forum names, forum groups/staff roles
and in-game operator/wildcard status are not used to grant Plan access. Existing password accounts and
passwords remain unchanged and available through **Use a Plan account**.

Without an explicit subject mapping or linked Plan account, forum sign-in grants only
`access.player.self` and the player overview, sessions, versus, servers, statistics and plugins tabs.
Mapped groups/accounts with no permissions retain no permissions. Duplicate UUID associations, a
missing configured group and invalid or unavailable permission storage deny access. Current permissions
are read on every authenticated request, so a Plan group change, unlink or account deletion removes
inherited rights on the next request. No permission snapshot is stored in a forum session. The normal
Plan landing page sends authorized administrators to the server/network view and users with self access
to their own UUID page.

## Install and configure

1. Use the canonical tested Plan build and the reviewed website PlanAuth module together. Keep Plan
   web authentication enabled and serve both applications over HTTPS. This feature does not make an
   unauthenticated Plan deployment safe.
2. Install/provision PlanAuth using the website repository's `scripts/provision-plan-auth.php` and
   its operator instructions. Register one client, `plan`, with issuer `https://forums.patriam.cc` and
   exact callback `https://plan.patriam.cc/auth/forum/callback`. Generate a dedicated random secret
   of at least 32 random bytes, base64url encoded (43–128 characters); use the protected credential
   channel documented by the website tooling.
3. Plan creates `forum-auth.yml` in its data directory with disabled defaults. Set `client-secret`
   to the same dedicated secret and `enabled: true`. Optional `subject-web-groups` entries map an exact
   immutable numeric subject from this configured forum to an existing Plan web group, for example
   `'1': 'admin'`; keep the default mapping empty unless that authority is explicitly approved. Keep
   file access restricted to the service owner. Do not reuse a Minecraft integration, Store,
   moderation, Discord or staff rank-worker credential.
4. Restart/reload Plan through the normal controlled release procedure. Check its forum configuration
   diagnostic, `/auth/forum/status`, and the forum sign-in button on `/login`. A blocked file leaves a
   previous valid generation active only while Plan authentication remains enabled; a cold failed
   load provides no forum sign-in. Configuration is not watched or reloaded on each request.
5. Complete acceptance with controlled forum/Minecraft test identities before announcing availability.

The independent `forum-auth.yml` schema is `config-version: 3`. A physically unversioned file
is schema 0 and follows explicit `0 -> 1 -> 2 -> 3` migrations with source validation and atomic
replacement, preserving explicit values, credentials and unknown keys without creating a backup.
Schema 1's implicit 900-second
duration is preserved too; schema 2 adopts an empty subject map without granting anyone access. Set
`session-seconds: 1209600` explicitly to extend an existing installation. Fresh files default to 14
days. Subject keys are canonical positive decimal forum IDs and values are bounded Plan group names;
the map has at most 256 entries. Malformed, duplicate, null and future versions are rejected without
rewriting the file. It does not alter the upstream `config.yml` schema. Endpoint URLs derive from the
configured forum HTTPS origin, not browser input. The exact callback path is fixed; changing its
hostname requires matching registration on both sides.

## Session and recovery behavior

- A login transaction lasts at most five minutes and is bound to a host-only, secure browser cookie.
  The forum's single-use authorization code lasts 60 seconds and requires both the recorded state
  and PKCE S256 verifier plus the server-held client secret for redemption.
- Sessions last at most 14 days (`session-seconds`, fresh default and maximum 1209600) from authentication.
  The browser receives a persistent Secure, HttpOnly, SameSite=Lax cookie with that remaining lifetime. Eligibility
  is checked at most 60 seconds apart (`recheck-seconds`, default 60), with bounded five-second
  network exchanges (`timeout-seconds`, configurable up to 10). Checks never extend session expiry.
- Session records persist in `plan_forum_sessions`, separately from local password accounts. Only
  hashes of browser cookies are stored. After restart, a persisted session requires a fresh forum
  check before authorizing anything. Client/issuer/callback/secret or access-timing changes invalidate
  sessions from the previous configuration. Subject-map changes are also part of this generation and
  require a new forum sign-in.
- Disabling forum sign-in or Plan web authentication clears saved forum sessions. If deletion fails,
  forum sign-in remains paused and retries the deletion before it can be enabled again. Normal
  enabled restarts preserve unexpired sessions and require the fresh eligibility check above.
- Explicit revocation or an identity revision mismatch invalidates the session. Temporary verification
  failure denies new requests and allows a retry after five seconds; it does not treat unknown identity
  as verified. Already rendered browser content cannot be withdrawn, but new protected responses stop.
- Forum unlink, verification replacement, account disabling/banning, password/MFA changes and the
  broker's durable identity-revision events are checked against the same immutable forum user and UUID.
  Minecraft names and public-profile visibility are never ownership evidence. A game-only ban has no
  separate analytics entitlement mapping in this release.
- Sign-out deletes the server session. A storage failure reports that sign-out could not complete;
  retry instead of assuming the session has been revoked. Full Plan data reset clears forum sessions.
  Saves and deletions require confirmed transaction commit, not merely a completed database future.
  Revoked hashes remain blocked in memory for the maximum session lifetime across configuration and
  eligibility-cache clears. They are never evicted to admit another marker; reaching 10,000 pauses
  forum sign-in until a reload successfully invalidates all saved sessions. Final authorization also
  rereads the same saved session under the revocation lock, fencing an in-flight deletion.
  A failed, unacknowledged deletion is not promised durable across a process restart; a surviving
  saved session must pass fresh forum eligibility verification before it can be used again.
- Login errors direct the player to check account activation and Minecraft verification and retry.
  Missing or pending links show an explanation and an Account Connections link on the forum. This
  requires a verified Minecraft account association, not a roleplay character record.
  Formal reports/appeals and gameplay property are not modified by this integration.

Pending transactions and cached checks are bounded in memory. Expired durable sessions are removed on
new session creation. Use the website's bounded purge command for expired broker codes/attempt records.
The forum consent page requires confirmation and rechecks configured MFA, including for remembered
forum sessions. When retained, local Plan owner access remains a recovery path if the forum is unavailable.

## Patriam presentation

The login page and default dashboard use the current Minecraft page/forum identity: navy and gold,
parchment surfaces, the Patriam crest and landscape, and locally bundled Jost/Instrument Sans fonts.
Forum sign-in is the primary button when enabled; the existing Plan form remains under **Use a Plan
account**, and is shown directly when forum sign-in is disabled. Light/dark dashboard modes, custom
themes and data-series/status colours retain their controls. Bundled asset provenance and font
licences are in `Plan/react/dashboard/src/assets/patriam/`.

## Release evidence and rollback

Run Plan API/common tests (including the database aggregate), frontend build, website PHP/schema,
protocol/MFA tests and an actual cross-application acceptance pass. The common access-control suite
covers player HTML, JSON, sessions and datapoints; raw and staff access require the mapped Plan group's
or linked Plan account's corresponding grants. Tests include replay, wrong browser/state/PKCE,
cross-account access, name changes, subject/group mapping, permission inheritance/downgrade, ambiguous
UUID links, outages, expiry, revocation, restart, storage failures and configuration migration/races. Passing local tests
does not establish deployed acceptance.

Record source commits and staged hashes using the coordination workspace's `tools/build-all.sh Plan`.
It also tests/builds the declared API consumers; do not replace it with a packaging-only build. Do
not create release or database backups unless the operator explicitly requests them.

To pause new forum access, disable the dedicated client in PlanAuth or disable `forum-auth.yml` and
reload/restart Plan. Explicit broker refusal removes existing sessions on their next bounded check.
To roll back software, redeploy the prior recorded source revision and retain the additive tables
until an owner-reviewed cleanup. Never roll back unrelated Handbook work or resume
the currently paused website publishers as part of this feature.

## Quality basis

The acceptance criteria use [ISO/IEC 25010:2023](https://www.iso.org/standard/78176.html) as a product
quality model, not a certification claim. Relevant evidence includes correctness of identity and
access, bounded memory/network work, compatibility with existing accounts and databases, usable
recovery, revocation/restart behavior, secret handling, small independently tested components, safe
configuration changes and preservation of unrelated data. Protocol protections follow the applicable
authorization-code guidance in [RFC 9700](https://www.rfc-editor.org/rfc/rfc9700.html) and
[PKCE, RFC 7636](https://www.rfc-editor.org/rfc/rfc7636.html). This private broker is not a general
OAuth or OpenID Connect identity provider.
