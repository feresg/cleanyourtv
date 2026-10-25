package io.github.feresg.watch;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * One title over its backdrop: name, year, rating and overview, with buttons that hand it to Umbrella in Kodi.
 * A film or an episode plays (Umbrella scrapes and you pick a source); a series opens its seasons.
 */
public class DetailActivity extends Activity {
    static final String TITLE = "title";

    /** Kodi keeps starting after this screen is gone, so the request outlives it. */
    private static final ExecutorService KODI = Executors.newSingleThreadExecutor();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private Title title;
    private Config config;
    private TextView meta;
    private TextView overview;
    private ImageView backdrop;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            title = Title.fromJson(getIntent().getStringExtra(TITLE));
        } catch (JSONException | NullPointerException e) {
            finish();
            return;
        }
        config = Config.load(this);

        FrameLayout page = new FrameLayout(this);
        page.setBackgroundColor(0xFF0E0F13);
        backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        page.addView(backdrop, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // Dark from the left, so the text reads on any picture.
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xF20E0F13, 0xCC0E0F13, 0x330E0F13}));
        page.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setGravity(Gravity.CENTER_VERTICAL);
        text.setPadding(px(64), px(48), px(64), px(48));
        TextView name = new TextView(this);
        name.setText(title.name);
        name.setTextSize(40);
        name.setTextColor(0xFFFFFFFF);
        name.setMaxLines(2);
        meta = new TextView(this);
        meta.setTextSize(17);
        meta.setTextColor(0xFFC9CCD3);
        meta.setPadding(0, px(8), 0, px(14));
        overview = new TextView(this);
        overview.setTextSize(16);
        overview.setTextColor(0xFFC9CCD3);
        overview.setMaxLines(6);
        overview.setLineSpacing(0, 1.15f);
        text.addView(name);
        text.addView(meta);
        text.addView(overview);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setPadding(0, px(28), 0, 0);
        Button first;
        if (title.kind == Title.Kind.SHOW) {
            first = button(buttons, "Seasons in Umbrella", () -> kodi(() -> Kodi.openBody(Umbrella.seasons(title))));
        } else if (title.kind == Title.Kind.EPISODE) {
            first = button(buttons, "Play " + title.episodeCode(), () -> kodi(() -> Kodi.playBody(Umbrella.play(title))));
            button(buttons, "Season " + title.season, () -> kodi(() -> Kodi.openBody(Umbrella.episodes(title))));
            button(buttons, "All seasons", () -> kodi(() -> Kodi.openBody(Umbrella.seasons(title))));
        } else {
            first = button(buttons, "Play in Umbrella", () -> kodi(() -> Kodi.playBody(Umbrella.play(title))));
        }
        text.addView(buttons);
        page.addView(text, new FrameLayout.LayoutParams(px(760), ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(page);
        first.requestFocus();
        fill();

        // The IMDb and TVDB ids Umbrella plays by, and anything else TMDB knows that the list didn't carry.
        if (config.hasTmdb()) {
            Title t = title;
            net.execute(() -> {
                new Tmdb(config).complete(t, true);
                main.post(() -> {
                    if (!isFinishing()) fill();
                });
            });
        }
    }

    @Override
    protected void onDestroy() {
        net.shutdownNow();
        super.onDestroy();
    }

    private void fill() {
        StringBuilder m = new StringBuilder();
        if (title.kind == Title.Kind.EPISODE) {
            m.append(title.episodeCode());
            if (!title.episodeTitle.isEmpty()) m.append(" · ").append(title.episodeTitle);
        } else {
            m.append(title.kind == Title.Kind.MOVIE ? "Film" : "Series");
        }
        if (title.year > 0) m.append("  ·  ").append(title.year);
        if (title.rating > 0) m.append("  ·  ★ ").append(String.format(Locale.ROOT, "%.1f", title.rating));
        if (title.progress >= 0) m.append("  ·  ").append(Math.round(title.progress)).append(" % watched");
        meta.setText(m);
        overview.setText(title.overview);
        if (backdrop.getTag() == null && !title.backdrop.isEmpty()) {
            Posters.load(backdrop, Tmdb.image(title.backdrop, "w1280"), getResources().getDisplayMetrics().widthPixels);
        }
    }

    /** Brings Kodi to the front and sends the request once its web server answers. */
    private void kodi(Supplier<String> body) {
        Intent launch = Kodi.launchIntent(this);
        if (launch == null) {
            Toast.makeText(this, "Kodi (" + Kodi.PACKAGE + ") isn't installed or is disabled.", Toast.LENGTH_LONG).show();
            return;
        }
        Context app = getApplicationContext();
        Config c = config;
        // Queued behind the id lookup above, so the address carries the IMDb id when TMDB has one.
        net.execute(() -> {
            String request = body.get();
            main.post(() -> {
                startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                KODI.execute(() -> {
                    String why = Kodi.send(c, request);
                    if (why != null) main.post(() -> Toast.makeText(app, why, Toast.LENGTH_LONG).show());
                });
            });
        });
    }

    private Button button(LinearLayout parent, String label, Runnable click) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(17);
        b.setTextColor(getColorStateList(R.color.button_text));
        b.setBackgroundResource(R.drawable.button);
        b.setPadding(px(26), 0, px(26), 0);
        b.setOnClickListener(v -> click.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(54));
        lp.rightMargin = px(14);
        parent.addView(b, lp);
        return b;
    }

    private int px(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
