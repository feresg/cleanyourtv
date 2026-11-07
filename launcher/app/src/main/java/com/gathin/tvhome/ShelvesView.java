package com.gathin.tvhome;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * home_shelves on screen: under the apps, a heading and a sideways row of landscape cards for each row a module
 * offers (HomeShelves). Built in code, and only while the switch is on. Keys inside the rows are handled here:
 * a row's two ends keep focus, and Up and Down go to the next row at the card last focused there; Up from the
 * first row and Back go back to the apps (Host). The activity moves Home up to show the focused row.
 */
@SuppressLint("ViewConstructor")
final class ShelvesView extends LinearLayout {

    interface Host {
        /** Up from the first row, or the rows went away under the focus: focus goes back to the apps. */
        void onShelvesExit();

        /** A card in this row took focus: Home moves so the row shows. */
        void onShelfRowFocused(int row);

        /** OK on a card. */
        void onShelfItemChosen(HomeShelves.Row row, HomeShelves.Item item);
    }

    /** A card's size (16:9) and the room between two cards; the focus zoom fits in the room. */
    static final float CARD_W_DP = 184f, CARD_H_DP = 104f, GAP_DP = 16f;
    static final float FOCUS_SCALE = 1.08f;
    /** Room above and below the cards for the zoom and the focus ring. */
    static final float ZOOM_ROOM_DP = 10f;
    /** The heading above each row and the gap under the row. */
    static final float HEADING_GAP_DP = 4f, ROW_GAP_DP = 14f;

    private final Host host;
    private final float density;
    private final boolean animate;
    private final int cardW, cardH, gap, radius;
    private final Typeface medium, regular;
    /** Left and right edge of the rows: the first card lines up with the first tile (setSide). */
    private int side;
    private List<HomeShelves.Row> rows = new ArrayList<>();
    /** The card views of each row, and the pictures in them, in the rows' order. */
    private final List<List<View>> cards = new ArrayList<>();
    private final List<ImageView> pictures = new ArrayList<>();
    /** Per row (Row.key): the card last focused in it, so coming back to the row lands there again. */
    private final Map<String, Integer> remembered = new HashMap<>();
    /** The row that last had focus (Row.key), for the way back from a title opened there. */
    private String lastRow;
    /** While the rows are rebuilt the activity is not told about the focus moving to the new cards. */
    private boolean rebuilding;

    ShelvesView(Context ctx, Host host, float density, boolean animate) {
        super(ctx);
        this.host = host;
        this.density = density;
        this.animate = animate;
        cardW = Math.round(CARD_W_DP * density);
        cardH = Math.round(CARD_H_DP * density);
        gap = Math.round(GAP_DP * density);
        radius = Math.round(8 * density);
        side = Math.round(66 * density);
        medium = UiFont.get(ctx, R.string.font_medium);
        regular = UiFont.get(ctx, R.string.font_regular);
        setOrientation(VERTICAL);
        setClipChildren(false);
        setClipToPadding(false);
        setFocusable(false);
        setVisibility(GONE);
    }

    /**
     * Always as tall as all its rows: the root would cap it at the screen's height, cutting the last rows off,
     * and Home moves it up into view anyway (MainActivity.pageTo).
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
    }

    int pictureWidth() {
        return cardW;
    }

    int pictureHeight() {
        return cardH;
    }

    boolean hasRows() {
        return !rows.isEmpty();
    }

    /** The row's top, from the rows' own top (its heading included). */
    int rowTop(int row) {
        return row >= 0 && row < getChildCount() ? getChildAt(row).getTop() : 0;
    }

    /** The row holding focus, or -1. */
    int focusedRow() {
        View focused = findFocus();
        if (focused == null) return -1;
        for (int r = 0; r < cards.size(); r++) if (cards.get(r).contains(focused)) return r;
        return -1;
    }

    /** Lines the first card up with the apps' first column: x is the tiles' left edge on screen. */
    void setSide(int x) {
        if (x <= 0 || x == side) return;
        side = x;
        for (int i = 0; i < getChildCount(); i++) applySide((ViewGroup) getChildAt(i));
    }

    private void applySide(ViewGroup row) {
        View heading = row.getChildAt(0);
        LayoutParams lp = (LayoutParams) heading.getLayoutParams();
        lp.leftMargin = side;
        lp.rightMargin = side;
        heading.setLayoutParams(lp);
        View scroller = row.getChildAt(1);
        int room = Math.round(ZOOM_ROOM_DP * density);
        scroller.setPadding(side, room, side, room);
    }

    /** Focuses a row at the card last focused in it (the first card the first time). False when there is none. */
    boolean focusRow(int row) {
        if (row < 0 || row >= rows.size()) return false;
        Integer last = remembered.get(rows.get(row).key());
        int index = HomeShelves.clampIndex(last == null ? 0 : last, cards.get(row).size());
        return index >= 0 && cards.get(row).get(index).requestFocus();
    }

    /**
     * Focus left the rows for the apps: every row goes back to its first card, so the heading that shows under the
     * apps starts at the beginning again, and Down lands on the first card.
     */
    void toStart() {
        remembered.clear();
        for (int i = 0; i < getChildCount(); i++) {
            HorizontalScrollView scroller = (HorizontalScrollView) ((ViewGroup) getChildAt(i)).getChildAt(1);
            if (animate) scroller.smoothScrollTo(0, 0);
            else scroller.scrollTo(0, 0);
        }
    }

    /** Focuses the card last focused in the rows, when its row is still there. */
    boolean focusLast() {
        for (int r = 0; r < rows.size(); r++) {
            if (rows.get(r).key().equals(lastRow)) return focusRow(r);
        }
        return false;
    }

    /**
     * Shows a new read of the rows; nothing happens when it shows the same as the last one. Focus inside the rows
     * stays on the same title, or the same place, in the new rows: the new cards take it before the old ones go,
     * so it never falls to some other view. When no row is left the apps get it back.
     */
    void setRows(List<HomeShelves.Row> fresh) {
        if (HomeShelves.same(rows, fresh)) return;
        View focused = findFocus();
        String focusKey = null, focusId = null;
        int focusRow = -1, focusIndex = -1;
        if (focused != null) {
            for (int r = 0; r < cards.size() && focusRow < 0; r++) {
                int i = cards.get(r).indexOf(focused);
                if (i < 0) continue;
                focusRow = r;
                focusIndex = i;
                focusKey = rows.get(r).key();
                focusId = rows.get(r).items.get(i).id;
            }
        }
        List<View> old = new ArrayList<>();
        for (int i = 0; i < getChildCount(); i++) old.add(getChildAt(i));
        rows = new ArrayList<>(fresh);
        cards.clear();
        pictures.clear();
        Map<String, Integer> keep = new HashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            HomeShelves.Row row = rows.get(i);
            Integer last = remembered.get(row.key());
            if (last != null) keep.put(row.key(), last);
            addView(buildRow(i, row));
        }
        remembered.clear();
        remembered.putAll(keep);
        if (focusRow >= 0) {
            rebuilding = true;
            boolean placed = false;
            try {
                int r = -1;
                for (int i = 0; i < rows.size(); i++) if (rows.get(i).key().equals(focusKey)) r = i;
                if (r < 0 && !rows.isEmpty()) r = Math.min(focusRow, rows.size() - 1);
                if (r >= 0) {
                    List<HomeShelves.Item> items = rows.get(r).items;
                    int i = -1;
                    for (int j = 0; j < items.size(); j++) if (items.get(j).id.equals(focusId)) i = j;
                    if (i < 0) i = HomeShelves.clampIndex(focusIndex, items.size());
                    remembered.put(rows.get(r).key(), i);
                    placed = cards.get(r).get(i).requestFocus();
                }
            } finally {
                rebuilding = false;
            }
            if (!placed) host.onShelvesExit();
        }
        for (View v : old) removeView(v);
        setVisibility(rows.isEmpty() ? GONE : VISIBLE);
    }

    /** Lets go of every picture (memory pressure while Home is away); the next read brings them back. */
    void clearPictures() {
        for (ImageView p : pictures) p.setImageDrawable(null);
        List<HomeShelves.Row> bare = new ArrayList<>();
        for (HomeShelves.Row row : rows) {
            List<HomeShelves.Item> items = new ArrayList<>();
            for (HomeShelves.Item it : row.items) {
                items.add(new HomeShelves.Item(it.id, it.title, it.subtitle, it.progress, it.image, null));
            }
            bare.add(new HomeShelves.Row(row.pkg, row.target, row.authority, row.id, row.title, items));
        }
        rows = bare;
    }

    // ---- building --------------------------------------------------------------------------------

    private View buildRow(int index, HomeShelves.Row row) {
        Context ctx = getContext();
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);

        TextView heading = new TextView(ctx);
        heading.setText(row.title);
        heading.setSingleLine(true);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        heading.setTextColor(0xB3FFFFFF);
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        heading.setTypeface(medium);
        heading.setLetterSpacing(0.18f);
        heading.setAllCaps(true);
        heading.setIncludeFontPadding(false);
        heading.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        LayoutParams hlp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        hlp.bottomMargin = Math.round(HEADING_GAP_DP * density);
        box.addView(heading, hlp);

        Scroller scroller = new Scroller(ctx);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setOverScrollMode(OVER_SCROLL_NEVER);
        scroller.setClipChildren(false);
        scroller.setClipToPadding(false);
        LinearLayout strip = new LinearLayout(ctx);
        strip.setOrientation(HORIZONTAL);
        strip.setClipChildren(false);
        strip.setClipToPadding(false);
        List<View> rowCards = new ArrayList<>();
        for (int i = 0; i < row.items.size(); i++) {
            View card = buildCard(index, i, row, row.items.get(i));
            LayoutParams clp = new LayoutParams(cardW, cardH);
            if (i < row.items.size() - 1) clp.rightMargin = gap;
            strip.addView(card, clp);
            rowCards.add(card);
        }
        cards.add(rowCards);
        scroller.addView(strip, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        LayoutParams slp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        slp.bottomMargin = Math.round(ROW_GAP_DP * density);
        box.addView(scroller, slp);
        applySide(box);
        return box;
    }

    private View buildCard(int rowIndex, int index, HomeShelves.Row row, HomeShelves.Item item) {
        Context ctx = getContext();
        FrameLayout card = new FrameLayout(ctx);
        card.setFocusable(true);
        card.setClickable(true);
        GradientDrawable face = new GradientDrawable();
        face.setColor(0x26FFFFFF);
        face.setCornerRadius(radius);
        card.setBackground(face);
        card.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        card.setClipToOutline(true);
        card.setForeground(focusRing());

        ImageView picture = new ImageView(ctx);
        picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
        picture.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (item.picture != null) picture.setImageBitmap(item.picture);
        card.addView(picture, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        pictures.add(picture);

        // Darkens the lower part so the white lines read over any picture.
        View scrim = new View(ctx);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0x99000000, 0xE6000000}));
        card.addView(scrim, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                Math.round(cardH * 0.64f), Gravity.BOTTOM));

        LinearLayout lines = new LinearLayout(ctx);
        lines.setOrientation(VERTICAL);
        int pad = Math.round(10 * density);
        lines.setPadding(pad, 0, pad, Math.round((item.progress >= 0 ? 18 : 9) * density));
        TextView title = new TextView(ctx);
        title.setText(item.title);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        title.setTypeface(medium);
        title.setIncludeFontPadding(false);
        lines.addView(title);
        if (!item.subtitle.isEmpty()) {
            TextView subtitle = new TextView(ctx);
            subtitle.setText(item.subtitle);
            subtitle.setSingleLine(true);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            subtitle.setTextColor(0xB3FFFFFF);
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            subtitle.setTypeface(regular);
            subtitle.setIncludeFontPadding(false);
            LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            lp.topMargin = Math.round(3 * density);
            lines.addView(subtitle, lp);
        }
        card.addView(lines, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        if (item.progress >= 0) {
            // How much was watched: a thin rounded bar inside the card's bottom edge, over a dim track.
            card.addView(progressBar(0x4DFFFFFF), barParams());
            View bar = progressBar(0xFFFFFFFF);
            bar.setPivotX(0f);
            bar.setScaleX(item.progress / 100f);
            card.addView(bar, barParams());
        }

        card.setContentDescription(item.subtitle.isEmpty() ? item.title : item.title + ", " + item.subtitle);
        card.setOnClickListener(v -> host.onShelfItemChosen(row, item));
        card.setOnFocusChangeListener((v, focused) -> {
            float s = focused ? FOCUS_SCALE : 1f;
            if (animate) {
                v.animate().scaleX(s).scaleY(s).setDuration(150).start();
            } else {
                v.animate().cancel();
                v.setScaleX(s);
                v.setScaleY(s);
            }
            if (!focused) return;
            remembered.put(row.key(), index);
            lastRow = row.key();
            if (!rebuilding) host.onShelfRowFocused(rowIndex);
        });
        card.setOnKeyListener((v, keyCode, event) -> onCardKey(v, rowIndex, index, row.items.size(), keyCode, event));
        return card;
    }

    private View progressBar(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(1.5f * density);
        View bar = new View(getContext());
        bar.setBackground(shape);
        return bar;
    }

    private FrameLayout.LayoutParams barParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                Math.round(3 * density), Gravity.BOTTOM);
        int inset = Math.round(10 * density);
        lp.leftMargin = inset;
        lp.rightMargin = inset;
        lp.bottomMargin = Math.round(8 * density);
        return lp;
    }

    /** A white ring around the focused card, inside its corners. */
    private StateListDrawable focusRing() {
        GradientDrawable ring = new GradientDrawable();
        ring.setColor(0x00000000);
        ring.setCornerRadius(radius);
        ring.setStroke(Math.round(2 * density), 0xFFFFFFFF);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, ring);
        return states;
    }

    private boolean onCardKey(View card, int row, int index, int size, int keyCode, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return index == 0;              // the row's start: stay, never wrap or leave the rows
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return index == size - 1;       // and its end
            case KeyEvent.KEYCODE_DPAD_UP:
                if (row == 0) {
                    card.playSoundEffect(SoundEffectConstants.NAVIGATION_UP);
                    host.onShelvesExit();
                } else if (focusRow(row - 1)) {
                    card.playSoundEffect(SoundEffectConstants.NAVIGATION_UP);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (focusRow(row + 1)) card.playSoundEffect(SoundEffectConstants.NAVIGATION_DOWN);
                return true;                    // the last row: stay
            default:
                return false;
        }
    }

    /**
     * A row that scrolls only as far as it takes to keep the focused card inside its padding, so the card never
     * meets the screen's edge; the stock row scrolls it flush against the edge, where its zoom is cut off.
     * Left and Right move with the stock smooth scroll and click like the grid does.
     */
    @SuppressLint("ViewConstructor")
    private static final class Scroller extends HorizontalScrollView {
        Scroller(Context ctx) {
            super(ctx);
            setFocusable(false);
            setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
        }

        @Override
        protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
            if (getChildCount() == 0) return 0;
            int left = getScrollX() + getPaddingLeft();
            int right = getScrollX() + getWidth() - getPaddingRight();
            if (rect.right > right) return rect.right - right;
            if (rect.left < left) return rect.left - left;
            return 0;
        }

        @Override
        public boolean arrowScroll(int direction) {
            View before = findFocus();
            boolean handled = super.arrowScroll(direction);
            if (handled && findFocus() != before) playSoundEffect(SoundEffectConstants.getContantForFocusDirection(direction));
            return handled;
        }
    }
}
