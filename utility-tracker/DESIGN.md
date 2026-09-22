# Utility Tracker Design Context

## Product and audience

Utility Tracker is a private, local-first household meter log. Its primary job is to make a reading, sync state, and backend choice understandable at a glance on a phone; it is not a generic analytics dashboard.

## Visual direction

- Use Material 3 dynamic color when available, with calm system light/dark fallbacks.
- Prefer dense, quiet information cards over permanent action rows. A single selected radio control is the visual owner of an active endpoint.
- Use one strong action per workflow: pull to sync, a compact add icon in detail pages, and overflow menus for edit/delete.
- Date, time, money, and meter data are functional content; labels should be plain and localized, never decorative.

## Layout tokens and behavior

- Portrait: bottom navigation for the four primary destinations; secondary workflows are full screen with a back app bar.
- Landscape: navigation rail and multi-column meter cards. `values-land/dimens.xml` owns spacing and column count.
- Card padding is 16dp, ordinary gaps are 8–12dp, and destructive actions only appear in an overflow menu followed by confirmation.
- All strings originate in `values/` and `values-zh/`; timestamps display in the device zone but persist as UTC RFC 3339.
