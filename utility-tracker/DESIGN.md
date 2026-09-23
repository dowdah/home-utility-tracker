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

## Remaining-reading semantics

- All three meter types display remaining balances; new reading forms default to electricity.
- Unknown consumption or cost uses an em dash with a localized reason. A known subtotal is labeled incomplete; a tariff-change estimate is labeled estimated.
- An increasing balance can mean a top-up or input error. It must not become a negative consumption or an apparently complete zero bill.
- Statistics scroll naturally in portrait and landscape; tariff recovery uses the existing tariff-history route.

## Recharge presentation

The approved expansion keeps the existing Material 3 theme, typography and spacing. `ui/theme/Theme.kt` remains the runtime color/type owner; `values-land/dimens.xml` owns orientation layout. Recharges reuse the existing meter selector, date/time picker, confirmation and overflow controls. Do not add a second form/navigation system.

Consumption and recharge spending are separate labeled values. Month charts describe reading intervals, year charts describe month buckets, and every chart has matching text values. Unknown values are gaps rather than zero bars; partial totals retain their warning. All new content has English and Chinese resources.

Behavior drift addressed: save failures now preserve the form; the dashboard reads persisted background-sync state instead of only transient messages. No palette or theme change is intended.
