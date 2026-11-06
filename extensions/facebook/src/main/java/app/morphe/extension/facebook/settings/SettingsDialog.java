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
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
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
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import app.morphe.extension.facebook.media.PictureInPicture;
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
    /** The argument naming the page the screen opens on: a route of {@link SettingsNavigation}. */
    static final String START_PAGE = "hushfacebook_start_page";
    /**
     * Saved when the screen goes away while the search box is being typed in. Android 17 no longer
     * brings the keyboard back after a configuration change the app doesn't handle, and the box has
     * no id for the window to put its focus back on, so the screen asks for both itself.
     */
    static final String TYPING = "hushfacebook_search_typing";
    static final String BUILD_DETAILS = "hushfacebook_build_details";
    static final String VIEW_TOGGLE = "hushfacebook_view_toggle";
    /** Whether the keyboard is up over a view. Replaced in tests, where no keyboard draws. */
    static java.util.function.Predicate<View> keyboardUp = SettingsDialog::keyboardShowing;

    private TextView pageTitle;
    /** What the bar calls the page once the header's name has scrolled away. */
    private CharSequence pageName = "Hushfacebook";
    private HeaderColumn column;
    /** The header over the search: Facebook's icon, the name and the version line. */
    private View headerIcon;
    private TextView headerName;
    private View headerVersion;
    Button pauseAction;
    private LinearLayout searchBox;
    /** Under the search: shows the settings as one list or a button per section. */
    private LinearLayout viewToggle;
    private ImageView viewToggleIcon;
    private TextView viewToggleLabel;
    private EditText search;
    private TextView results;
    private int found;
    private final Runnable showFound = () -> {
        String count = L10n.quantity(results.getContext(), found, "%1$d setting found", "%1$d settings found", found);
        results.setVisibility(View.VISIBLE);
        if (!count.contentEquals(results.getText())) results.setText(count);
    };
    private boolean settingSearch;
    /** The screen was recreated while its search box was being typed in. */
    private boolean typingAgain;
    /** The search box had the focus and the keyboard when the screen last paused. */
    private boolean typingAtPause;

    /** The page's colours, or null for the black page. Read when the dialog is created. */
    @Nullable
    private ScreenColors colors;

    /**
     * The screen, opening on [page] when nothing saved says otherwise: the page a restart from the
     * restart row left. Null or empty opens the first page.
     */
    static SettingsDialog onPage(@Nullable String page) {
        SettingsDialog dialog = new SettingsDialog();
        if (page != null && !page.isEmpty()) {
            Bundle arguments = new Bundle();
            arguments.putString(START_PAGE, page);
            dialog.setArguments(arguments);
        }
        return dialog;
    }

    /** The page this screen was asked to open on, or "" for the first page. */
    String startPage() {
        Bundle arguments = getArguments();
        return arguments == null ? "" : arguments.getString(START_PAGE, "");
    }

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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.setNavigationBarContrastEnforced(false);
            window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            // Below Android 15 this window draws its own bars in the framework theme's colours,
            // grey and black, which a light page's dark icons can't be read on. The page's colour
            // goes behind them instead. Android 15 and newer draw the page there already.
            // A black page over another Background colour does the same.
            ScreenColors page = colors == null ? ScreenColors.black() : colors;
            if (page != ScreenColors.DEFAULT) {
                window.setStatusBarColor(page.background);
                window.setNavigationBarColor(page.background);
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
        typingAgain = savedInstanceState != null && savedInstanceState.getBoolean(TYPING);
        ScreenColors palette = colors == null ? ScreenColors.black() : colors;
        HeaderColumn root = new HeaderColumn(getContext());
        column = root;
        root.setBackgroundColor(background());

        LinearLayout bar = new LinearLayout(getContext());
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        // Start and end, not left and right: in a right-to-left language the bar is mirrored.
        bar.setPaddingRelative(dp(4), dp(4), dp(12), dp(4));

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
        boolean largeText = getResources().getConfiguration().fontScale >= 1.5f;
        LinearLayout labels = bar;
        if (largeText) {
            labels = new LinearLayout(getContext());
            labels.setOrientation(LinearLayout.VERTICAL);
            bar.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }

        TextView title = new TextView(getContext());
        pageTitle = title;
        title.setText("Hushfacebook");
        title.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        // The bar, every page opened from it and search all show their name here, in Facebook's blues.
        ShimmerTitle.wear(title, background());
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setIncludeFontPadding(false);
        title.setPaddingRelative(dp(8), dp(8), dp(8), dp(8));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setAutoSizeTextTypeUniformWithConfiguration(12, 22, 1, TypedValue.COMPLEX_UNIT_SP);
        title.setMaxLines(1);
        title.setAccessibilityHeading(true);
        labels.addView(title, new LinearLayout.LayoutParams(largeText ? ViewGroup.LayoutParams.MATCH_PARENT : 0,
                ViewGroup.LayoutParams.WRAP_CONTENT, largeText ? 0 : 1));
        pauseAction = new Button(getContext());
        pauseAction.setTag("hushfacebook_toolbar_pause");
        LinearLayout.LayoutParams actionPlace = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionPlace.gravity = Gravity.END;
        labels.addView(pauseAction, actionPlace);
        root.addView(bar);
        View divider = new View(getContext());
        divider.setBackgroundColor(palette.outline);
        root.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        // The bar and its line stay put while the header, the search and the list scroll under them.
        root.pin(2);

        headerName = SettingsHeader.addTo(root, palette);
        headerIcon = root.findViewWithTag(SettingsHeader.ICON);
        headerVersion = root.findViewWithTag(SettingsHeader.VERSION);

        buildSearch(root);

        FrameLayout container = new FrameLayout(getContext());
        container.setId(CONTAINER_ID);
        // The column gives it the height below the bar.
        root.addView(container, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0));

        root.setOnScrollChangeListener((view, x, y, oldX, oldY) -> showBarTitle());
        root.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom)
                -> showBarTitle());
        // Facebook targets a recent API, so its windows are edge to edge: keep the bars and the
        // keyboard off the content.
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            padForBars(view, insets);
            return insets;
        });
        return root;
    }

    /** Pads [view] off the bars and the keyboard. Before Android 11 the system window insets hold both. */
    @SuppressWarnings("deprecation")
    private static void padForBars(View view, WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
        } else {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
        }
    }

    /** Lets the header follow [list]: it slides up under the bar as the rows scroll. */
    void followList(ListView list) {
        if (column != null) column.follow(list);
    }

    /**
     * The bar leaves the name off while the header under it shows it, and names the page again
     * once the header has scrolled away. While a search is typed it says so. Invisible rather than
     * gone, so the bar keeps its height and its buttons stay where they were.
     */
    private void showBarTitle() {
        if (pageTitle == null || column == null || headerName == null) return;
        boolean searching = searchBox.getVisibility() == View.VISIBLE && search.length() > 0;
        CharSequence text = searching ? L10n.t("Search settings") : pageName;
        if (!TextUtils.equals(text, pageTitle.getText())) pageTitle.setText(text);
        // "Search settings" names what the screen is doing, not the page; the header's name, which
        // stays during a search, is the page's heading then.
        if (pageTitle.isAccessibilityHeading() == searching) pageTitle.setAccessibilityHeading(!searching);
        boolean nameShows = !searching && headerName.getVisibility() == View.VISIBLE
                && (!column.isLaidOut() || headerName.getBottom() > column.getScrollY() + column.pinnedBottom());
        int shown = nameShows ? View.INVISIBLE : View.VISIBLE;
        if (pageTitle.getVisibility() != shown) pageTitle.setVisibility(shown);
    }

    private void buildSearch(LinearLayout root) {
        ScreenColors palette = colors == null ? ScreenColors.black() : colors;
        searchBox = new LinearLayout(getContext());
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        android.graphics.drawable.GradientDrawable surface = new android.graphics.drawable.GradientDrawable();
        surface.setColor(palette.card);
        surface.setCornerRadius(dp(10));
        surface.setStroke(dp(1), ScreenColors.focusStroke(palette.heading, palette.outline));
        searchBox.setBackground(surface);
        searchBox.setAddStatesFromChildren(true);
        searchBox.setPaddingRelative(dp(14), 0, dp(4), 0);
        ImageView icon = new ImageView(getContext());
        icon.setImageDrawable(SettingsIcons.icon(getContext(), SettingsIcons.SEARCH, palette.summary));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        searchBox.addView(icon, new LinearLayout.LayoutParams(dp(20), dp(20)));
        search = new EditText(getContext());
        search.setHint(L10n.t("Search settings"));
        search.setContentDescription(L10n.t("Search settings"));
        search.setSingleLine(true);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        search.setBackground(null);
        search.setTextColor(palette.title);
        search.setHintTextColor(palette.hint);
        search.setPaddingRelative(dp(12), dp(10), 0, dp(10));
        search.setMinimumHeight(dp(48));
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
        clear.setOnClickListener(ignored -> {
            search.setText("");
            search.requestFocus();
        });
        searchBox.addView(clear, new LinearLayout.LayoutParams(dp(48), dp(48)));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                clear.setVisibility(text.length() == 0 ? emptyClearVisibility : View.VISIBLE);
                showBarTitle();
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
        // On the 18 dp line the section cards and the restart bar stand on.
        layout.setMargins(dp(18), dp(4), dp(18), dp(12));
        root.addView(searchBox, layout);
        buildViewToggle(root, palette);
        // Typed letters alone tell a screen reader nothing about the list below, so the count is
        // spoken from a polite live region, once the typing has settled.
        results = new TextView(getContext());
        results.setTextColor(palette.summary);
        results.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        results.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        results.setVisibility(View.GONE);
        LinearLayout.LayoutParams place = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        place.setMarginStart(dp(20));
        place.setMarginEnd(dp(20));
        place.topMargin = dp(8);
        place.bottomMargin = dp(8);
        root.addView(results, place);
        // A newly opened screen must not steal focus and raise the keyboard.
        root.setFocusableInTouchMode(true);
        root.requestFocus();
        searchBox.setVisibility(View.GONE);
    }

    /**
     * Under the search, a row of the page like a section's button: it shows every section's rows
     * on one list, or a button per section, and offers whichever the list isn't showing. It stays
     * while a search is typed.
     */
    private void buildViewToggle(LinearLayout root, ScreenColors palette) {
        viewToggle = new LinearLayout(getContext());
        viewToggle.setTag(VIEW_TOGGLE);
        viewToggle.setOrientation(LinearLayout.HORIZONTAL);
        viewToggle.setGravity(Gravity.CENTER_VERTICAL);
        viewToggle.setMinimumHeight(dp(48));
        viewToggle.setPaddingRelative(dp(20), dp(4), dp(20), dp(4));
        android.graphics.drawable.GradientDrawable surface = new android.graphics.drawable.GradientDrawable();
        surface.setColor(palette.card);
        surface.setStroke(dp(1), palette.interactionOutline(palette.outline));
        // Edge to edge like the rows under it: only the line along its bottom shows.
        android.graphics.drawable.Drawable inset = new android.graphics.drawable.InsetDrawable(surface, -dp(1), -dp(1), -dp(1), 0);
        viewToggle.setBackground(new android.graphics.drawable.RippleDrawable(
                ScreenColors.interactionRipple(palette.heading), inset, null));
        viewToggle.setFocusable(true);
        viewToggle.setClickable(true);
        viewToggleIcon = new ImageView(getContext());
        viewToggleIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        viewToggleIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        viewToggleIcon.setPaddingRelative(0, 0, dp(14), 0);
        viewToggle.addView(viewToggleIcon, new LinearLayout.LayoutParams(dp(38), dp(24)));
        viewToggleLabel = new TextView(getContext());
        viewToggleLabel.setTextColor(palette.title);
        viewToggleLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        viewToggleLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        viewToggleLabel.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        viewToggle.addView(viewToggleLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        viewToggle.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
            }
        });
        viewToggle.setOnClickListener(ignored -> {
            HushfacebookPreferenceFragment page = page();
            if (page != null && page.navigation != null) page.navigation.showAsList(!page.navigation.listMode());
        });
        showViewToggle(false);
        viewToggle.setVisibility(View.GONE);
        root.addView(viewToggle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** The toggle offers the view the list isn't showing: [list] is the one it shows now. */
    void showViewToggle(boolean list) {
        ScreenColors palette = colors == null ? ScreenColors.black() : colors;
        CharSequence label = list ? L10n.t("Show settings by category") : L10n.t("Show all settings as a list");
        if (!TextUtils.equals(label, viewToggleLabel.getText())) viewToggleLabel.setText(label);
        viewToggleIcon.setImageDrawable(SettingsIcons.icon(getContext(), list ? SettingsIcons.FEED : SettingsIcons.MENU,
                palette.heading));
    }

    void showPage(CharSequence title, boolean home, String query) {
        pageName = title;
        // The header belongs to the main page, and stays there while a search is typed.
        int header = home ? View.VISIBLE : View.GONE;
        headerIcon.setVisibility(header);
        headerName.setVisibility(header);
        headerVersion.setVisibility(header);
        int headingSize = 22;
        if (home) {
            pageTitle.setAutoSizeTextTypeUniformWithConfiguration(12, headingSize, 1, TypedValue.COMPLEX_UNIT_SP);
        } else {
            pageTitle.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE);
            pageTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, headingSize);
        }
        pageTitle.setMaxLines(home ? 1 : Integer.MAX_VALUE);
        searchBox.setVisibility(home ? View.VISIBLE : View.GONE);
        viewToggle.setVisibility(home ? View.VISIBLE : View.GONE);
        settingSearch = true;
        if (!search.getText().toString().equals(query)) search.setText(query);
        settingSearch = false;
        showBarTitle();
        boolean typing = typingAgain && home;
        typingAgain = false;
        if (typing) {
            // Posted, since a restored page can get here before the dialog's window holds the box.
            search.post(() -> {
                // A requested setting may have moved the page on since, and the box with it.
                if (searchBox.getVisibility() != View.VISIBLE) return;
                search.requestFocus();
                search.setSelection(search.length());
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    WindowInsetsController keyboard = search.getWindowInsetsController();
                    if (keyboard != null) keyboard.show(WindowInsets.Type.ime());
                } else {
                    android.view.inputmethod.InputMethodManager keyboard =
                            getContext().getSystemService(android.view.inputmethod.InputMethodManager.class);
                    if (keyboard != null) keyboard.showSoftInput(search, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                }
            });
        } else if (!home || query.isEmpty()) {
            search.clearFocus();
            hideKeyboard();
        }
    }

    /**
     * Read at pause, while the dialog still shows: its onStop hides the dialog, which takes the
     * focus off the box before the state is saved on Android 9 and newer.
     */
    @Override
    public void onPause() {
        typingAtPause = search != null && search.hasFocus() && keyboardUp.test(search);
        super.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();
        typingAtPause = false;
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (typingAtPause) outState.putBoolean(TYPING, true);
    }

    @SuppressWarnings("deprecation")
    private static boolean keyboardShowing(View view) {
        WindowInsets insets = view.getRootWindowInsets();
        if (insets == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return insets.isVisible(WindowInsets.Type.ime());
        // Before Android 11 the keyboard shows only as a bottom inset taller than the bars'.
        return insets.getSystemWindowInsetBottom() > insets.getStableInsetBottom();
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
     * has its decor. And a reel playing under the page no longer floats it in the
     * picture-in-picture window when the person leaves Facebook, until the page closes.
     */
    @Override
    public void onStart() {
        super.onStart();
        PictureInPicture.settingsShown(getActivity());
        Dialog dialog = getDialog();
        Window window = dialog == null ? null : dialog.getWindow();
        if (colors == null || !colors.light || window == null) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            lightBarsBefore11(window.getDecorView());
            return;
        }
        WindowInsetsController bars = window.getInsetsController();
        if (bars == null) return;
        int dark = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        bars.setSystemBarsAppearance(dark, dark);
    }

    @SuppressWarnings("deprecation")
    private static void lightBarsBefore11(View decor) {
        decor.setSystemUiVisibility(decor.getSystemUiVisibility()
                | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }

    private int background() {
        return (colors == null ? ScreenColors.black() : colors).background;
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
    public void onDismiss(android.content.DialogInterface dialog) {
        PictureInPicture.settingsClosed();
        super.onDismiss(dialog);
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
