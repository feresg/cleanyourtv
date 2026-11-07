# A line under the clock from a module (`clock_line`)

With `clock_line` on, Home shows one line under its clock, such as the weather, read from a module whose
[card](card.md) the owner chose to show. The launcher has no network access, so the module does any fetching.
This is a feature of this fork, like [`home_shelves`](shelves.md), and works the same way. The reference module is
[Weather](../../addons/weather/README.md#the-line-under-homes-clock).

## Declaration

On the card's activity, name a content provider of the same app:

```xml
<meta-data android:name="com.gathin.tvhome.clock_line" android:value="com.example.module.clockline" />
```

The provider must be in the card's own package (the launcher checks the authority resolves to it) and exported.

## Provider

`content://<authority>/line` answers with a column `text`. The first row's text is the line, cut to 48
characters with control and formatting characters made spaces; no row, or an empty text, shows no line. When
the line changes, `notifyChange(content://<authority>/line)`: Home reads it again while it is in front (at most
once every 10 seconds), or on its next return.

## Launcher behaviour

- Off by default: `clock_line` in `config.xml` or a theme's `config.json`. Needs `module_cards`. Nothing is read
  and nothing is added to the screen while it is off.
- The first card in the Shown state that offers a line gives it. The line sits under the clock's row (and under
  the date when `date_below_clock` put it there).
- Read on one background thread: after Home's first frame, on a return to Home when the last read is more than
  2 minutes old or the module reported a change, and when the cards change. No timer and no service.
- The same checks as `home_shelves`: no other app's provider, everything bounded, a module that fails is left
  out with one log line (`logcat -s TvHomeShelves`), and a failure of the launcher's own code turns the line
  (and the rows) off until Home restarts.
