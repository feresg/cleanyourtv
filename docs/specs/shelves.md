# Home rows from a module (`home_shelves`)

With `home_shelves` on, Home shows rows of titles under the app rows, read from a module whose
[card](card.md) the owner chose to show. The launcher has no network access, so the module does the
fetching and the launcher only reads what it hands over. This is a feature of this fork: upstream keeps
content rows out of the launcher. The reference module is [Watch](../../addons/watch/README.md#rows-on-home).

## Declaration

On the card's activity (the one with the `DOCK_CARD` filter), name a content provider of the same app:

```xml
<meta-data android:name="com.gathin.tvhome.shelves" android:value="com.example.module.shelves" />
```

The provider must be in the card's own package (the launcher checks the authority resolves to it), be
exported, and answer the two paths below. If what it shows is private, check the caller on every call, as
Watch does: `getCallingPackage()` is `com.gathin.tvhome` and `PackageManager.checkSignatures` matches.

## Provider

| URI | Columns |
|---|---|
| `content://<authority>/shelves` | `id`, `title`: one row per row on Home, in order |
| `content://<authority>/shelves/<id>` | `id`, `title`, `subtitle`, `progress`, `image`: one row per title, in order |

- `id`: text, 1 to 128 characters, no control characters. A title's `id` is handed back when it is opened.
- `title` (a row's heading, or a title's name), `subtitle` (optional, the line under the name): text, cut to
  48 characters; control and formatting characters become spaces.
- `progress` (optional): an integer from 0 to 100 draws a bar under the title; -1, empty or absent draws none.
- `image` (optional): `content://<authority>/<path>` of the same provider, the path made of letters, digits,
  `.`, `_`, `-` and `/`, served by `openFile` in mode `r`. A JPEG or PNG, at most 1 MB and 4096 × 4096 pixels;
  a 16:9 picture about 300 pixels wide fills a card. Anything else is shown without a picture.
- When the rows change, `notifyChange(content://<authority>/shelves)`. Home reads them again a moment later
  while it is in front (at most once every 10 seconds), or on its next return if it is not.

## Launcher behaviour

- Off by default: `home_shelves` in `config.xml` or a theme's `config.json`. Needs `module_cards` (on by
  default). Nothing is read and nothing is added to the screen while it is off.
- Rows come only from cards in the Shown state, in the cards' order: at most 4 rows in all, 20 titles in a
  row. A card shown, hidden, installed or removed brings its rows or takes them away.
- Reads run on one background thread: after Home's first frame, on a return to Home when the last read is
  more than 2 minutes old or the module reported a change, and when the cards change. No timer and no
  service: a change reported while Home is away is only noted.
- On screen: the first row's heading shows under the apps. Down from the last app row enters the first row
  and Home moves up with it, Up and Down go from row to row (to the card last focused there), Left and Right
  stop at a row's ends, and Up from the first row or Back goes back to the apps. Leaving the rows puts each
  one back at its first card. Back from a title opened in the rows returns to that card.
- OK starts the card's activity by explicit component, as a tile does, with one extra:
  `com.gathin.tvhome.extra.SHELF_ITEM` set to the title's `id`. Nothing else is handed over.
- Pictures are decoded at the card's size in RGB_565 and let go when Android asks for memory while Home is
  away; the next read decodes them again. A read that brings nothing new changes nothing on screen.

## Security

- The launcher reads no other app's provider: the authority must belong to the card's own package, and a
  picture must come from that same provider. It opens no network connection.
- Every value is bounded and cleaned before it reaches the screen. A module that fails (throws, refuses,
  answers with the wrong columns) is left out with one log line, `logcat -s TvHomeShelves`; if the launcher's
  own code fails, the rows switch off until Home restarts and the apps work as without them.
- A module that hangs while answering delays only its own rows: the reads never run on Home's main thread.
- The extra is the only input the module gets from Home. Treat it as untrusted: look the id up in your own
  data, as Watch does, and ignore one you don't know.
