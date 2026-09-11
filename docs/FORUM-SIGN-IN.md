# Forum sign-in

This maintained Plan fork can sign players in through the Nameless PlanAuth module. A player confirms
their active forum account and its verified Minecraft identity on the forum, then returns to the
appropriate Plan page. Forum passwords and MFA codes stay on the forum. Discord authentication is not involved.

If exactly one existing Plan account is explicitly linked to the verified Minecraft UUID, forum sign-in
uses that account's current permission group and exact permissions. Minecraft or forum names, forum
staff roles and in-game operator/wildcard status are not used to grant Plan access. Existing password
accounts and passwords remain unchanged and available through **Use a Plan account**.

Without a linked Plan account, forum sign-in grants only `access.player.self` and the player overview,
sessions, versus, servers, statistics and plugins tabs. Mapped accounts with no permissions retain no
permissions. Duplicate UUID associations, missing groups and permission-storage failures deny access.
Current permissions are read on every authenticated request, so a group change, unlink or account
deletion removes inherited rights on the next request. No permission snapshot is stored in a forum
session. The normal Plan landing page sends authorized administrators to the server/network view and
users with self access to their own UUID page.

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
   to the same dedicated secret and `enabled: true`. Keep file access restricted to the service owner.
   Do not reuse a Minecraft integration, Store, moderation, Discord or staff rank-worker credential.
4. Restart/reload Plan through the normal controlled release procedure. Check its forum configuration
   diagnostic, `/auth/forum/status`, and the forum sign-in button on `/login`. A blocked file leaves a
   previous valid generation active only while Plan authentication remains enabled; a cold failed
   load provides no forum sign-in. Configuration is not watched or reloaded on each request.
5. Complete acceptance with controlled forum/Minecraft test identities before announcing availability.

The independent `forum-auth.yml` schema starts at `config-version: 1`. A physically unversioned file
is schema 0 and migrates with an exact private backup and atomic replacement, preserving explicit
values and unknown keys. Malformed, duplicate, null and future versions are rejected without rewriting
the file. It does not alter the upstream `config.yml` schema. Endpoint URLs derive from the configured
forum HTTPS origin, not browser input. The exact callback path is fixed; changing its hostname requires
matching registration on both sides.

## Session and recovery behavior

- A login transaction lasts at most five minutes and is bound to a host-only, secure browser cookie.
  The forum's single-use authorization code lasts 60 seconds and requires both the recorded state
  and PKCE S256 verifier plus the server-held client secret for redemption.
- Sessions last at most 15 minutes (`session-seconds`, default 900) from authentication. Eligibility
  is checked at most 60 seconds apart (`recheck-seconds`, default 60), with bounded five-second
  network exchanges (`timeout-seconds`, configurable up to 10). Checks never extend session expiry.
- Session records persist in `plan_forum_sessions`, separately from local password accounts. Only
  hashes of browser cookies are stored. After restart, a persisted session requires a fresh forum
  check before authorizing anything. Client/issuer/callback/secret or access-timing changes invalidate
  sessions from the previous configuration.
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
- Login errors direct the player to check account activation and Minecraft verification and retry.
  Missing or pending links show an explanation and an Account Connections link on the forum. This
  requires a verified Minecraft account association, not a roleplay character record.
  Formal reports/appeals and gameplay property are not modified by this integration.

Pending transactions and cached checks are bounded in memory. Expired durable sessions are removed on
new session creation. Use the website's bounded purge command for expired broker codes/attempt records.
The forum consent page requires confirmation and rechecks configured MFA, including for remembered
forum sessions. Normal local Plan owner access remains the recovery path if the forum is unavailable.

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
covers player HTML, JSON, sessions and datapoints; raw and staff access require the linked Plan account's
corresponding grants. Tests include replay, wrong browser/state/PKCE, cross-account access, name changes,
permission inheritance/downgrade, ambiguous UUID links, outages, expiry, revocation, restart, storage
failures and configuration migration/races. Passing local tests
does not establish deployed acceptance.

Record source commits and staged hashes using the coordination workspace's `tools/build-all.sh Plan`.
It also tests/builds the declared API consumers; do not replace it with a packaging-only build. Capture
the installed Plan jar, operator configuration and database backup before a controlled live release.

To pause new forum access, disable the dedicated client in PlanAuth or disable `forum-auth.yml` and
reload/restart Plan. Explicit broker refusal removes existing sessions on their next bounded check.
To roll back software, restore the prior jar/module from the recorded release backup and retain the
additive tables until an owner-reviewed cleanup. Never roll back unrelated Handbook work or resume
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
