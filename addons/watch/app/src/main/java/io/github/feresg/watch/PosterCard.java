package io.github.feresg.watch;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** One title in a row: its poster, a bar for how far it was watched, its name and a line under it. */
final class PosterCard extends LinearLayout {
    static final int WIDTH_DP = 128;
    static final int HEIGHT_DP = 192;

    PosterCard(Context context, Title t, Runnable onClick) {
        super(context);
        float dp = context.getResources().getDisplayMetrics().density;
        int w = Math.round(WIDTH_DP * dp);
        setOrientation(VERTICAL);
        int pad = Math.round(5 * dp);
        setPadding(pad, pad, pad, pad);
        setBackgroundResource(R.drawable.card);
        setFocusable(true);
        setClickable(true);
        setOnClickListener(v -> onClick.run());
        setOnFocusChangeListener((v, focused) -> v.animate().scaleX(focused ? 1.06f : 1f).scaleY(focused ? 1.06f : 1f)
                .setDuration(120).start());

        FrameLayout frame = new FrameLayout(context);
        frame.setBackgroundColor(0xFF22252D);
        ImageView poster = new ImageView(context);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(poster, new FrameLayout.LayoutParams(w, Math.round(HEIGHT_DP * dp)));
        TextView fallback = new TextView(context);
        fallback.setText(t.name);
        fallback.setTextColor(0xFF8B8F99);
        fallback.setGravity(Gravity.CENTER);
        fallback.setPadding(pad, pad, pad, pad);
        frame.addView(fallback, 0, new FrameLayout.LayoutParams(w, Math.round(HEIGHT_DP * dp)));
        if (t.progress >= 0) {
            View track = new View(context);
            track.setBackgroundColor(0x99000000);
            FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(w, Math.round(5 * dp), Gravity.BOTTOM);
            frame.addView(track, tp);
            View fill = new View(context);
            fill.setBackgroundColor(0xFFED1C24);
            FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(Math.round(w * (float) (t.progress / 100)),
                    Math.round(5 * dp), Gravity.BOTTOM);
            frame.addView(fill, fp);
        }
        addView(frame);
        Posters.load(poster, Tmdb.image(t.poster, "w185"), w);

        TextView name = line(context, t.name, 14, 0xFFFFFFFF, w);
        name.setPadding(0, Math.round(6 * dp), 0, 0);
        addView(name);
        String caption = t.caption();
        if (!caption.isEmpty()) addView(line(context, caption, 12, 0xFF9DA1AB, w));
    }

    private static TextView line(Context c, String text, float size, int color, int width) {
        TextView v = new TextView(c);
        v.setText(text);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.END);
        v.setLayoutParams(new LayoutParams(width, LayoutParams.WRAP_CONTENT));
        return v;
    }
}
