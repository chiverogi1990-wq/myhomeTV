package ru.home.tvlauncher;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Оболочка в стиле «витрина + ряды плиток»:
 *  - верхняя панель: приветствие, кнопки «Сеть» и «Настройки», часы;
 *  - большая витрина: фон и заголовок меняются под выбранную плитку;
 *  - ряды: «Избранное», «Все приложения», «Система».
 * Удержание ОК (или кнопка «Меню») на плитке — избранное, порядок, сведения, удаление.
 */
public class MainActivity extends Activity {

    /** Плитка: приложение или системный пункт. */
    static final class Tile {
        final String key;        // имя пакета или "sys:..."
        final String label;
        final Drawable icon;
        final Drawable banner;   // широкий ТВ-баннер, если есть
        final int color;         // основной цвет плитки
        final Intent intent;     // для системных пунктов
        final boolean isApp;

        Tile(String key, String label, Drawable icon, Drawable banner,
             int color, Intent intent, boolean isApp) {
            this.key = key;
            this.label = label;
            this.icon = icon;
            this.banner = banner;
            this.color = color;
            this.intent = intent;
            this.isApp = isApp;
        }
    }

    private static final String PREFS = "launcher";
    private static final String KEY_FAVS = "favorites";
    private static final String KEY_INIT = "favorites_initialized";
    private static final int DEFAULT_FAVS = 6;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final Map<String, Tile> apps = new LinkedHashMap<>(); // по алфавиту
    private final List<String> favorites = new ArrayList<>();

    private LinearLayout rows;
    private View heroBg;
    private TextView heroTitle, heroHint, clock, greeting;
    private SharedPreferences prefs;

    private int heroColor = 0xFF1C2A4A;
    private ValueAnimator heroAnim;
    private String focusedKey;     // чтобы вернуть фокус после перестройки рядов
    private String focusedRow;

    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault());

    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            updateClock();
            long now = System.currentTimeMillis();
            handler.postDelayed(this, 60_000 - (now % 60_000) + 50);
        }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            loadApps();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        rows = findViewById(R.id.rows);
        heroBg = findViewById(R.id.hero_bg);
        heroTitle = findViewById(R.id.hero_title);
        heroHint = findViewById(R.id.hero_hint);
        clock = findViewById(R.id.clock);
        greeting = findViewById(R.id.greeting);

        applyHeroColor(heroColor);
        setupTopButton(findViewById(R.id.btn_settings), new Intent(Settings.ACTION_SETTINGS),
                getString(R.string.menu_settings));
        setupTopButton(findViewById(R.id.btn_network), new Intent(Settings.ACTION_WIFI_SETTINGS),
                getString(R.string.menu_network));

        String saved = prefs.getString(KEY_FAVS, "");
        if (!saved.isEmpty()) {
            Collections.addAll(favorites, saved.split(","));
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(packageReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(packageReceiver, filter);
        }

        loadApps();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(clockTick);
        clockTick.run();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(clockTick);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(packageReceiver);
        loader.shutdownNow();
    }

    /** «Назад» на главном экране возвращает к первой плитке. */
    @Override
    public void onBackPressed() {
        focusFirstTile();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            View f = getCurrentFocus();
            if (f != null && f.getTag() instanceof Tile && ((Tile) f.getTag()).isApp) {
                showAppMenu((Tile) f.getTag());
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---------- Верхняя панель ----------

    private void setupTopButton(ImageButton b, Intent intent, String title) {
        b.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        b.setOnFocusChangeListener((v, has) -> {
            b.setImageTintList(ColorStateList.valueOf(has ? 0xFF10141C : Color.WHITE));
            if (has) {
                heroTitle.setText(title);
                heroHint.setText(R.string.hint_system);
            }
        });
        b.setOnClickListener(v -> safeStart(intent));
    }

    private void updateClock() {
        Date now = new Date();
        clock.setText(timeFormat.format(now));
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int greet = h < 5 ? R.string.greet_night
                : h < 12 ? R.string.greet_morning
                : h < 18 ? R.string.greet_day
                : R.string.greet_evening;
        String d = dateFormat.format(now);
        if (!d.isEmpty()) d = Character.toUpperCase(d.charAt(0)) + d.substring(1);
        greeting.setText(getString(greet) + "  ·  " + d);
    }

    // ---------- Загрузка приложений ----------

    private void loadApps() {
        loader.execute(() -> {
            PackageManager pm = getPackageManager();
            Map<String, Tile> found = new HashMap<>();
            collect(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, found, true);
            collect(pm, Intent.CATEGORY_LAUNCHER, found, false);
            found.remove(getPackageName());

            List<Tile> sorted = new ArrayList<>(found.values());
            Collator collator = Collator.getInstance(Locale.getDefault());
            Collections.sort(sorted, (a, b) -> collator.compare(a.label, b.label));

            handler.post(() -> {
                apps.clear();
                for (Tile t : sorted) apps.put(t.key, t);
                initDefaultFavorites();
                buildRows();
            });
        });
    }

    private void collect(PackageManager pm, String category, Map<String, Tile> out, boolean tv) {
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(intent, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null || out.containsKey(ai.packageName)) continue;

            Drawable banner = null;
            if (tv) {
                banner = ai.loadBanner(pm);
                if (banner == null) banner = ai.applicationInfo.loadBanner(pm);
            }
            Drawable icon = ri.loadIcon(pm);
            int color = dominantColor(banner != null ? banner : icon);
            String label = String.valueOf(ri.loadLabel(pm));
            Intent launch = new Intent(Intent.ACTION_MAIN)
                    .setComponent(new ComponentName(ai.packageName, ai.name));
            out.put(ai.packageName,
                    new Tile(ai.packageName, label, icon, banner, color, launch, true));
        }
    }

    /** При первом запуске кладём в избранное первые ТВ-приложения. */
    private void initDefaultFavorites() {
        if (prefs.getBoolean(KEY_INIT, false)) return;
        for (Tile t : apps.values()) {
            if (favorites.size() >= DEFAULT_FAVS) break;
            if (t.banner != null) favorites.add(t.key);
        }
        for (Tile t : apps.values()) {
            if (favorites.size() >= DEFAULT_FAVS) break;
            if (!favorites.contains(t.key)) favorites.add(t.key);
        }
        saveFavorites();
        prefs.edit().putBoolean(KEY_INIT, true).apply();
    }

    private void saveFavorites() {
        prefs.edit().putString(KEY_FAVS, TextUtils.join(",", favorites)).apply();
    }

    // ---------- Ряды плиток ----------

    private void buildRows() {
        rows.removeAllViews();

        List<Tile> favTiles = new ArrayList<>();
        for (String k : favorites) {
            Tile t = apps.get(k);
            if (t != null) favTiles.add(t);
        }
        if (!favTiles.isEmpty()) addRow("fav", getString(R.string.row_favorites), favTiles);
        addRow("apps", getString(R.string.row_apps), new ArrayList<>(apps.values()));
        addRow("sys", getString(R.string.row_system), systemTiles());

        // Возвращаем фокус туда, где он был
        View target = null;
        if (focusedKey != null) target = findTile(focusedRow + "|" + focusedKey);
        if (target != null) target.requestFocus();
        else focusFirstTile();
    }

    private void addRow(String rowId, String title, List<Tile> tiles) {
        View row = LayoutInflater.from(this).inflate(R.layout.row, rows, false);
        ((TextView) row.findViewById(R.id.row_title)).setText(title);
        LinearLayout items = row.findViewById(R.id.row_items);
        for (Tile t : tiles) items.addView(makeTileView(rowId, t, items));
        rows.addView(row);
    }

    private View makeTileView(String rowId, Tile t, ViewGroup parent) {
        View v = LayoutInflater.from(this).inflate(R.layout.item_app, parent, false);
        ImageView banner = v.findViewById(R.id.banner);
        View iconBox = v.findViewById(R.id.icon_box);
        ImageView icon = v.findViewById(R.id.icon);
        TextView label = v.findViewById(R.id.label);

        float r = 10 * getResources().getDisplayMetrics().density;
        ViewOutlineProvider rounded = new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        };

        if (t.banner != null) {
            banner.setVisibility(View.VISIBLE);
            banner.setImageDrawable(t.banner);
            banner.setOutlineProvider(rounded);
            banner.setClipToOutline(true);
            iconBox.setVisibility(View.GONE);
        } else {
            GradientDrawable bg = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    new int[]{t.color, darken(t.color, 0.55f)});
            bg.setCornerRadius(r);
            iconBox.setBackground(bg);
            icon.setImageDrawable(t.icon);
            if (!t.isApp) icon.setImageTintList(ColorStateList.valueOf(Color.WHITE));
            label.setText(t.label);
        }

        v.setTag(t);
        // Отдельная метка для поиска плитки после перестройки
        v.setContentDescription(t.label);
        v.setTag(R.id.row_items, rowId + "|" + t.key);

        v.setOnFocusChangeListener((view, has) -> {
            view.animate()
                    .scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f)
                    .translationZ(has ? 16f : 0f)
                    .setDuration(140).start();
            if (has) {
                focusedKey = t.key;
                focusedRow = rowId;
                onTileFocused(t);
            }
        });
        v.setOnClickListener(view -> open(t));
        if (t.isApp) {
            v.setOnLongClickListener(view -> {
                showAppMenu(t);
                return true;
            });
        }
        return v;
    }

    private List<Tile> systemTiles() {
        List<Tile> list = new ArrayList<>();
        list.add(sysTile("settings", R.string.tile_settings,
                android.R.drawable.ic_menu_preferences, 0xFF3D4A66, Settings.ACTION_SETTINGS));
        list.add(sysTile("wifi", R.string.tile_network,
                android.R.drawable.ic_menu_share, 0xFF1F6FB2, Settings.ACTION_WIFI_SETTINGS));
        list.add(sysTile("display", R.string.tile_display,
                android.R.drawable.ic_menu_view, 0xFF7B3FA6, Settings.ACTION_DISPLAY_SETTINGS));
        list.add(sysTile("storage", R.string.tile_storage,
                android.R.drawable.ic_menu_save, 0xFF2E8B57, Settings.ACTION_INTERNAL_STORAGE_SETTINGS));
        return list;
    }

    private Tile sysTile(String id, int label, int icon, int color, String action) {
        return new Tile("sys:" + id, getString(label), getDrawable(icon), null,
                color, new Intent(action), false);
    }

    private View findTile(String id) {
        for (int i = 0; i < rows.getChildCount(); i++) {
            LinearLayout items = rows.getChildAt(i).findViewById(R.id.row_items);
            if (items == null) continue;
            for (int j = 0; j < items.getChildCount(); j++) {
                View v = items.getChildAt(j);
                if (id.equals(v.getTag(R.id.row_items))) return v;
            }
        }
        return null;
    }

    private void focusFirstTile() {
        if (rows.getChildCount() == 0) return;
        LinearLayout items = rows.getChildAt(0).findViewById(R.id.row_items);
        if (items != null && items.getChildCount() > 0) {
            items.getChildAt(0).requestFocus();
            View scroll = rows.getChildAt(0).findViewById(R.id.row_scroll);
            if (scroll != null) scroll.scrollTo(0, 0);
        }
    }

    // ---------- Витрина ----------

    private void onTileFocused(Tile t) {
        heroTitle.setText(t.label);
        heroHint.setText(t.isApp ? R.string.hint_app : R.string.hint_system);
        animateHero(t.color);
    }

    private void animateHero(int target) {
        if (heroAnim != null) heroAnim.cancel();
        heroAnim = ValueAnimator.ofObject(new ArgbEvaluator(), heroColor, target);
        heroAnim.setDuration(350);
        heroAnim.addUpdateListener(a -> applyHeroColor((int) a.getAnimatedValue()));
        heroAnim.start();
    }

    private void applyHeroColor(int c) {
        heroColor = c;
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{darken(c, 0.75f), darken(c, 0.35f), 0xFF05070A});
        heroBg.setBackground(g);
    }

    // ---------- Меню приложения ----------

    private void showAppMenu(Tile t) {
        boolean fav = favorites.contains(t.key);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        if (fav) {
            int idx = favorites.indexOf(t.key);
            labels.add(getString(R.string.menu_remove_fav));
            actions.add(() -> { favorites.remove(t.key); saveFavorites(); buildRows(); });
            if (idx > 0) {
                labels.add(getString(R.string.menu_move_left));
                actions.add(() -> moveFavorite(idx, idx - 1));
            }
            if (idx < favorites.size() - 1) {
                labels.add(getString(R.string.menu_move_right));
                actions.add(() -> moveFavorite(idx, idx + 1));
            }
        } else {
            labels.add(getString(R.string.menu_add_fav));
            actions.add(() -> { favorites.add(t.key); saveFavorites(); buildRows(); });
        }

        Uri uri = Uri.parse("package:" + t.key);
        labels.add(getString(R.string.menu_info));
        actions.add(() -> safeStart(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri)));
        labels.add(getString(R.string.menu_uninstall));
        actions.add(() -> safeStart(new Intent(Intent.ACTION_DELETE, uri)));

        new AlertDialog.Builder(this)
                .setTitle(t.label)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    private void moveFavorite(int from, int to) {
        Collections.swap(favorites, from, to);
        saveFavorites();
        focusedRow = "fav";
        buildRows();
    }

    // ---------- Запуск ----------

    private void open(Tile t) {
        if (!t.isApp) {
            safeStart(t.intent);
            return;
        }
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(t.key);
        if (i == null) i = pm.getLaunchIntentForPackage(t.key);
        if (i == null) i = new Intent(t.intent);
        i.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        safeStart(i);
    }

    private void safeStart(Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (Exception e) {
            // Некоторые приставки не знают отдельных разделов настроек — открываем общие
            if (!Settings.ACTION_SETTINGS.equals(intent.getAction()) && intent.getAction() != null
                    && intent.getAction().startsWith("android.settings.")) {
                try {
                    startActivity(new Intent(Settings.ACTION_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    return;
                } catch (Exception ignored) { }
            }
            Toast.makeText(this, R.string.open_failed, Toast.LENGTH_SHORT).show();
        }
    }

    // ---------- Цвета ----------

    /** Средний насыщенный цвет картинки — для фона плитки и витрины. */
    private static int dominantColor(Drawable d) {
        if (d == null) return 0xFF2B3550;
        try {
            Bitmap bmp = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            d.setBounds(0, 0, 24, 24);
            d.draw(c);
            long r = 0, g = 0, b = 0, n = 0;
            for (int y = 0; y < 24; y++) {
                for (int x = 0; x < 24; x++) {
                    int p = bmp.getPixel(x, y);
                    if (Color.alpha(p) < 128) continue;
                    float[] hsv = new float[3];
                    Color.colorToHSV(p, hsv);
                    // Белое, серое и чёрное почти не учитываем
                    int w = (hsv[1] > 0.25f && hsv[2] > 0.2f) ? 4 : 1;
                    r += (long) Color.red(p) * w;
                    g += (long) Color.green(p) * w;
                    b += (long) Color.blue(p) * w;
                    n += w;
                }
            }
            bmp.recycle();
            if (n == 0) return 0xFF2B3550;
            int col = Color.rgb((int) (r / n), (int) (g / n), (int) (b / n));
            float[] hsv = new float[3];
            Color.colorToHSV(col, hsv);
            hsv[1] = Math.min(1f, hsv[1] * 1.2f);
            hsv[2] = Math.max(0.35f, Math.min(0.8f, hsv[2]));
            return Color.HSVToColor(hsv);
        } catch (Exception e) {
            return 0xFF2B3550;
        }
    }

    private static int darken(int c, float f) {
        return Color.rgb((int) (Color.red(c) * f), (int) (Color.green(c) * f),
                (int) (Color.blue(c) * f));
    }
}
