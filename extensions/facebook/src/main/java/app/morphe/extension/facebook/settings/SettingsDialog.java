/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Built on SysAdminDoc/hushfeed (GPL-3.0).
 */
package app.morphe.extension.facebook.settings;

import android.app.Dialog;
import android.app.DialogFragment;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;

/**
 * The Hushfacebook screen: a full screen, black dialog with a title bar and the preference list.
 * It is a framework dialog fragment, so it needs no activity of its own and Back closes it. With the
 * Material You theme in the build, the page follows the phone's dark or light setting in its
 * wallpaper colours instead ({@link ScreenColors}).
 */
@SuppressWarnings("deprecation") // Framework fragments are what the shared preference code builds on.
public final class SettingsDialog extends DialogFragment {
    /**
     * The preference list's container. Fixed, because the child manager saves the page with this
     * id and puts it back into a view with the same id after rotation or process recreation. A
     * fresh View.generateViewId() on each onCreateView left the restored page no container.
     * Outside both the generated-id range and aapt's 0x7f ids, and nothing else in this dialog
     * carries an id.
     */
    static final int CONTAINER_ID = 0x48464301;

    private TextView pageTitle;
    private LinearLayout searchBox;
    private EditText search;
    private TextView results;
    private int found;
    private final Runnable showFound = () -> {
        String count = L10n.quantity(results.getContext(), found, "%1$d setting found", "%1$d settings found", found);
        results.setVisibility(View.VISIBLE);
        if (!count.contentEquals(results.getText())) results.setText(count);
    };
    private boolean settingSearch;

    /** The page's colours, or null for the black page. Read when the dialog is created. */
    @Nullable
    private ScreenColors colors;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        colors = ScreenColors.forScreen(getContext());
        setStyle(STYLE_NO_TITLE, colors == null ? android.R.style.Theme_Material_NoActionBar : colors.theme());
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        // What the framework's own onCreateDialog builds, with Back first taking the list back to
        // where it was before a section jump. Dialog's back callback on Android 13 and newer ends
        // in onBackPressed as well, so this covers the gesture and the key alike.
        Dialog dialog = new Dialog(getActivity(), getTheme()) {
            @Override
            public void onBackPressed() {
                HushfacebookPreferenceFragment page = page();
                if (page != null && page.backFromJump()) return;
                super.onBackPressed();
            }
        };
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(background()));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            // With three-button navigation Android lays a grey scrim under the buttons; the
            // screen is black edge to edge, so the scrim only shows as a grey band.
            window.setNavigationBarContrastEnforced(false);
            window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            // Below Android 15 this window draws its own bars in the framework theme's colours,
            // grey and black, which a light page's dark icons can't be read on. The page's colour
            // goes behind them instead. Android 15 and newer draw the page there already.
            if (colors != null) {
                window.setStatusBarColor(colors.background);
                window.setNavigationBarColor(colors.background);
            }
        }
        return dialog;
    }

    /** Takes the page, already showing, to the row a notification's button asked for. */
    void showRequestedSetting() {
        HushfacebookPreferenceFragment page = page();
        if (page != null) page.showRequestedSetting();
    }

    /** The settings page this dialog holds, or null before it's attached. */
    @Nullable
    private HushfacebookPreferenceFragment page() {
        Object page = getChildFragmentManager().findFragmentById(CONTAINER_ID);
        return page instanceof HushfacebookPreferenceFragment ? (HushfacebookPreferenceFragment) page : null;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup parent, Bundle savedInstanceState) {
        LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(background());

        LinearLayout bar = new LinearLayout(getContext());
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(16);
        // Start and end, not left and right: in a right-to-left language the bar is mirrored.
        bar.setPaddingRelative(dp(4), dp(12), pad, dp(12));

        android.widget.ImageButton back = new android.widget.ImageButton(getContext());
        android.graphics.drawable.Drawable arrow = SettingsIcons.icon(getContext(), SettingsIcons.BACK, foreground());
        arrow.setBounds(0, 0, dp(24), dp(24));
        arrow.setLayoutDirection(rightToLeft() ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        back.setImageDrawable(arrow);
        back.setPadding(dp(12), dp(12), dp(12), dp(12));
        ScreenColors.pressFeedback(back, foreground(), true);
        back.setMinimumWidth(dp(48));
        back.setMinimumHeight(dp(48));
        back.setContentDescription(L10n.t(getContext(), "Back"));
        // A screen reader calls it a button, as it would the back arrow of any other screen.
        back.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
            }
        });
        back.setOnClickListener(v -> {
            HushfacebookPreferenceFragment page = page();
            if (page != null && page.backFromJump()) return;
            SettingsEntry.onClosedByUser();
            dismissAllowingStateLoss();
        });
        bar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView title = new TextView(getContext());
        pageTitle = title;
        title.setText("Hushfacebook");
        title.setTextColor(foreground());
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setAutoSizeTextTypeUniformWithConfiguration(16, 24, 1, TypedValue.COMPLEX_UNIT_SP);
        title.setMaxLines(1);
        title.setPaddingRelative(dp(8), 0, 0, 0);
        title.setAccessibilityHeading(true);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(bar);

        buildSearch(root);

        FrameLayout container = new FrameLayout(getContext());
        container.setId(CONTAINER_ID);
        root.addView(container, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Facebook targets a recent API, so its windows are edge to edge: keep the bars off the
        // content.
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        return root;
    }

    private void buildSearch(LinearLayout root) {
        ScreenColors palette = colors == null ? ScreenColors.DEFAULT : colors;
        searchBox = new LinearLayout(getContext());
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        android.graphics.drawable.GradientDrawable surface = new android.graphics.drawable.GradientDrawable();
        surface.setColor(palette.card);
        surface.setCornerRadius(dp(10));
        surface.setStroke(dp(1), palette.outline);
        searchBox.setBackground(surface);
        searchBox.setPaddingRelative(dp(16), 0, 0, 0);
        ImageView icon = new ImageView(getContext());
        icon.setImageDrawable(SettingsIcons.icon(getContext(), SettingsIcons.SEARCH, palette.summary));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        searchBox.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));
        search = new EditText(getContext());
        search.setHint(L10n.t("Search settings"));
        search.setContentDescription(L10n.t("Search settings"));
        search.setSingleLine(true);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        search.setBackground(null);
        search.setTextColor(palette.title);
        search.setHintTextColor(palette.summary);
        search.setPaddingRelative(dp(12), dp(12), 0, dp(12));
        search.setMinimumHeight(dp(52));
        searchBox.addView(search, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        android.widget.ImageButton clear = new android.widget.ImageButton(getContext());
        clear.setImageDrawable(SettingsIcons.icon(getContext(), SettingsIcons.CLOSE, palette.summary));
        ScreenColors.pressFeedback(clear, palette.summary, true);
        clear.setPadding(dp(12), dp(12), dp(12), dp(12));
        clear.setContentDescription(L10n.t("Clear search"));
        // At large text sizes an empty button's reserved width cuts off the search hint.
        int emptyClearVisibility = getResources().getConfiguration().fontScale >= 1.5f
                ? View.GONE : View.INVISIBLE;
        clear.setVisibility(emptyClearVisibility);
        clear.setOnClickListener(ignored -> search.setText(""));
        searchBox.addView(clear, new LinearLayout.LayoutParams(dp(48), dp(48)));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                clear.setVisibility(text.length() == 0 ? emptyClearVisibility : View.VISIBLE);
                HushfacebookPreferenceFragment page = page();
                if (!settingSearch && page != null && page.navigation != null) page.navigation.search(text.toString());
            }
            @Override public void afterTextChanged(Editable text) { }
        });
        search.setOnEditorActionListener((view, action, event) -> {
            if (action != android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) return false;
            hideKeyboard();
            return true;
        });
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        layout.setMargins(dp(16), 0, dp(16), dp(12));
        root.addView(searchBox, layout);
        // Typed letters alone tell a screen reader nothing about the list below, so the count is
        // spoken from a polite live region, once the typing has settled.
        results = new TextView(getContext());
        results.setTextColor(palette.summary);
        results.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        results.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        results.setVisibility(View.GONE);
        LinearLayout.LayoutParams place = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        place.setMarginStart(dp(20));
        place.setMarginEnd(dp(20));
        place.bottomMargin = dp(8);
        root.addView(results, place);
        // A newly opened screen must not steal focus and raise the keyboard.
        root.setFocusableInTouchMode(true);
        root.requestFocus();
        searchBox.setVisibility(View.GONE);
    }

    void showPage(CharSequence title, boolean home, String query) {
        pageTitle.setText(title);
        pageTitle.setMaxLines(home ? 1 : Integer.MAX_VALUE);
        searchBox.setVisibility(home ? View.VISIBLE : View.GONE);
        settingSearch = true;
        if (!search.getText().toString().equals(query)) search.setText(query);
        settingSearch = false;
        if (!home || query.isEmpty()) {
            search.clearFocus();
            hideKeyboard();
        }
    }

    /** How many settings the search shows, or -1 when there is no search. */
    void showResults(int count) {
        results.removeCallbacks(showFound);
        if (count < 0) {
            results.setVisibility(View.GONE);
            results.setText("");
            return;
        }
        found = count;
        results.postDelayed(showFound, 600);
    }

    private void hideKeyboard() {
        android.view.inputmethod.InputMethodManager keyboard = getContext().getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(search.getWindowToken(), 0);
    }

    /**
     * On a light page, dark status and navigation bar icons, which the window only takes once it
     * has its decor.
     */
    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        Window window = dialog == null ? null : dialog.getWindow();
        if (colors == null || !colors.light || window == null) return;
        WindowInsetsController bars = window.getInsetsController();
        if (bars == null) return;
        int dark = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        bars.setSystemBarsAppearance(dark, dark);
    }

    private int background() {
        return colors == null ? Color.BLACK : colors.background;
    }

    private int foreground() {
        return colors == null ? Color.WHITE : colors.title;
    }

    @Override
    public void onCancel(android.content.DialogInterface dialog) {
        // Back key or a tap outside: the person closed it.
        SettingsEntry.onClosedByUser();
        super.onCancel(dialog);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        try {
            if (getChildFragmentManager().findFragmentById(CONTAINER_ID) == null) {
                getChildFragmentManager().beginTransaction()
                        .replace(CONTAINER_ID, new HushfacebookPreferenceFragment())
                        .commitNow();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Could not show the preference list", ex);
        }
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }

    /** Whether the phone's language reads right to left, so the way back points right. */
    private boolean rightToLeft() {
        return getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
    }
}
