/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import android.content.Context;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.PopupMenu;

import com.facebook.graphservice.tree.TreeJNI;
import com.facebook.graphql.model.GraphQLMedia;
import com.facebook.graphql.model.GraphQLStory;
import com.facebook.graphql.model.GraphQLStoryAttachment;

import org.robolectric.RuntimeEnvironment;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * The video menu hook, for a test outside this package: one post menu Facebook has filled, handed
 * to the hook the way the patch hands it over.
 */
public final class VideoMenuItemForTests {
    private VideoMenuItemForTests() {
    }

    /** The getter names the fakes carry, standing in for the ones the patch reads from a build. */
    static final String ATTACHMENTS = "A0n";
    static final String MEDIA = "A01";
    static final String ATTACHED_STORY = "A04";

    /** The icon the patch borrows from Facebook's own Download video row. */
    static final int ICON = 0x7f1a02de;

    /** A menu as Facebook fills it for a video post: its Save row and its own Download row. */
    static Menu facebooksMenu(Context context) {
        Menu menu = new PopupMenu(context, new View(context)).getMenu();
        menu.add(Menu.NONE, 1, Menu.NONE, "Save video");
        menu.add(Menu.NONE, 2, Menu.NONE, "Download video");
        return menu;
    }

    /**
     * The same rows in a menu that sorts the way Facebook's post menu does (583, X.5WE's insert):
     * a new row goes in front of the first row whose order number is larger, else at the end, so
     * the numbers sort plainly and Android's categories mean nothing.
     */
    static Menu facebooksSortedMenu() {
        List<MenuItem> rows = new ArrayList<>();
        Menu menu = (Menu) Proxy.newProxyInstance(Menu.class.getClassLoader(), new Class<?>[] {Menu.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "add":
                            MenuItem row = args.length == 1 ? row(proxy, 0, 0, (CharSequence) args[0])
                                    : row(proxy, (int) args[1], (int) args[2], (CharSequence) args[3]);
                            int at = 0;
                            while (at < rows.size() && rows.get(at).getOrder() <= row.getOrder()) at++;
                            rows.add(at, row);
                            return row;
                        case "size":
                            return rows.size();
                        case "getItem":
                            return rows.get((int) args[0]);
                        default:
                            return method.getReturnType() == boolean.class ? false : null;
                    }
                });
        menu.add(Menu.NONE, 1, Menu.NONE, "Save video");
        menu.add(Menu.NONE, 2, Menu.NONE, "Download video");
        return menu;
    }

    /** A row of [facebooksSortedMenu]: its id, order and title. Setters hand the row back. */
    private static MenuItem row(Object menu, int id, int order, CharSequence title) {
        return (MenuItem) Proxy.newProxyInstance(MenuItem.class.getClassLoader(), new Class<?>[] {MenuItem.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getItemId": return id;
                        case "getOrder": return order;
                        case "getTitle": return title;
                        default:
                            if (method.getReturnType() == MenuItem.class) return proxy;
                            return method.getReturnType() == boolean.class ? false : null;
                    }
                });
    }

    /** A post holding one video whose post names its single file. */
    static GraphQLStory videoPost() {
        GraphQLMedia video = new GraphQLMedia("Video");
        video.with("id", "1234567890123456")
                .with("playable_url", "https://video.xx.fbcdn.net/v/t42.1790-2/sd.mp4?_nc_cat=1");
        return new GraphQLStory(null, new GraphQLStoryAttachment(video));
    }

    /** Fills a post menu for a video post and says whether the hook added its item. */
    public static boolean addsAnItem() {
        Context context = RuntimeEnvironment.getApplication();
        Menu menu = facebooksMenu(context);
        int before = menu.size();
        VideoMenuItem.add(menu, new View(context), videoPost(), ICON, ATTACHMENTS, MEDIA, ATTACHED_STORY, PhotoMenuItemForTests.SUBATTACHMENTS);
        return menu.size() > before;
    }

    /** Hands the viewer's hook a video the way the patch does and says whether it added its row. */
    public static boolean addsTheViewerRow() {
        Menu menu = facebooksMenu(RuntimeEnvironment.getApplication());
        int before = menu.size();
        ViewerVideoItem.add(menu, null, null, new TreeJNI("Video").with("id", "1234567890123456"), ICON);
        return menu.size() > before;
    }

    /** Fills a post menu for a video post and says whether the hook added Open in another app. */
    public static boolean addsTheOpenItem() {
        Context context = RuntimeEnvironment.getApplication();
        Menu menu = facebooksMenu(context);
        VideoMenuItem.add(menu, new View(context), videoPost(), ICON, ATTACHMENTS, MEDIA, ATTACHED_STORY, PhotoMenuItemForTests.SUBATTACHMENTS);
        for (int i = 0; i < menu.size(); i++) {
            if ("Open in another app".equals(String.valueOf(menu.getItem(i).getTitle()))) return true;
        }
        return false;
    }
}
