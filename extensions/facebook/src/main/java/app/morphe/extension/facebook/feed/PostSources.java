/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import androidx.annotation.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.IDN;
import java.net.URLDecoder;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Hide posts from people, Pages and sites: the person's list, and who wrote a feed post and where
 * its links go, read the way Facebook's own code reads them.
 *
 * <p>Each line of the list is one rule: a profile or Page id (only digits), a site (a domain such
 * as example.com, which takes its subdomains too), or a name, matched whole against the name of a
 * person or Page that wrote the post, capital letters aside. A link to Facebook itself is the id it
 * carries (profile.php?id=, /people/name/id, or an id as the whole path), and one without is left
 * out: a post carries its authors' ids and names, never their usernames, and Facebook's own
 * domains as a site would take nearly every post. A bare line with a dot and no slash, such as
 * Booking.com or Mr.Beast, is a site and a name at once. A post goes when one of its authors or
 * one of its links matches, and so does a share of such a post: the post a share wraps is read too.
 *
 * <p>The authors are the story's {@code actors}, a list GraphQLStory reads with a Redex-renamed
 * accessor and a renamed model class, so the patch fills in {@link #actors} for it. The links are
 * the {@code url} of each of the story's {@code attachments}, through {@link #attachments}, and a
 * link Facebook routes through its own redirect (l.facebook.com/l.php?u=) counts as the site it
 * leads to. Each id, name and url is read with the kept {@code getCachedString(int)}, which checks
 * the native tree is still there, so a released model reads as nothing rather than crashing.
 *
 * <p>The same authors tell Hide posts from Pages whether a Page wrote a post, by the type name each
 * one answers, or by what a Page that Facebook types as a person reads in its person fields
 * ({@link #authorType}).
 *
 * <p>Nothing here keeps, logs or reports a name, an id or a link. The report counts rules by number.
 */
public final class PostSources {
    /** The most rules the list holds, and the longest one. Longer lines and extra ones are left out. */
    public static final int MAX_RULES = 200;
    public static final int MAX_LENGTH = 80;

    /**
     * The longest link to Facebook kept. Its id sits among tracking parameters that make it longer
     * than a line otherwise holds, and the rule it makes is only the id.
     */
    static final int MAX_FACEBOOK_LINK_LENGTH = 512;
    /**
     * The room the stored list takes in a settings file, as {@link PostWords#encodedBytes} counts
     * it, so it fits beside the word lists whatever it's written in. Rules past it are left out like
     * rules past {@link #MAX_RULES}.
     */
    public static final int MAX_LIST_BYTES = 16 * 1024;

    static final String ACTORS_FIELD = "actors";
    static final String ATTACHMENTS_FIELD = "attachments";
    static final int ID_KEY = "id".hashCode();
    static final int NAME_KEY = "name".hashCode();
    static final int URL_KEY = "url".hashCode();

    /** The GraphQL type a Page answers {@code getTypeName()} with, and the kind Hide posts from Pages hides. */
    static final String PAGE = "Page";
    /** The kind of a story with an empty author list. */
    static final String NO_AUTHORS = "no authors";
    /** The kind of an author whose type can't be read, which counts as a person. */
    static final String UNKNOWN_AUTHOR = "unknown author";

    static final StoryFlag.Accessor ACTORS = PostSources::actors;
    static final StoryFlag.Accessor ATTACHMENTS = PostSources::attachments;

    private static final Pattern DIGITS = Pattern.compile("[0-9]{3,20}");
    private static final Pattern HOST = Pattern.compile("[a-z0-9-]+(\\.[a-z0-9-]+)+");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern FACEBOOK_ID = Pattern.compile("[?&]id=([0-9]{3,20})(?=[&#]|$)");

    /** Facebook's own domains. A rule never takes them as a site. */
    private static final String[] FACEBOOK_SITES = {"facebook.com", "fb.com", "fb.me", "fb.watch", "fbcdn.net"};

    /** What one line of the list is. */
    enum Kind { ID, SITE, NAME }

    /**
     * One rule: what it is, the value it matches, and its line number for the report. A site typed
     * bare, with no scheme or path, also matches an author named the same, in [name].
     */
    static final class Rule {
        final Kind kind;
        final String value;
        final int number;
        @Nullable final String name;

        Rule(Kind kind, String value, int number) {
            this(kind, value, number, null);
        }

        Rule(Kind kind, String value, int number, @Nullable String name) {
            this.kind = kind;
            this.value = value;
            this.number = number;
            this.name = name;
        }
    }

    /** The last stored list read and its rules, so the feed reads a list once, not once per post. */
    private static final class Parsed {
        final String stored;
        final List<Rule> rules;

        Parsed(String stored, List<Rule> rules) {
            this.stored = stored;
            this.rules = rules;
        }
    }

    private static volatile Parsed parsed;

    /** What reading one feed unit found. Only {@link #READ} carries authors and links. */
    enum Outcome {
        READ("sources read"),
        NO_UNIT("no feed unit"),
        NOT_A_STORY("not a story"),
        NO_READER("reader missing"),
        NO_ACCESSOR("accessor not patched"),
        READ_FAILED("read failed");

        /** What the report counts this under: a shape, never content. */
        final String reason;

        Outcome(String reason) {
            this.reason = reason;
        }
    }

    /** The ids, names and link hosts of a post and the post it shares. */
    static final class Found {
        final Outcome outcome;
        final Set<String> ids = new LinkedHashSet<>();
        final Set<String> names = new LinkedHashSet<>();
        final Set<String> hosts = new LinkedHashSet<>();

        Found(Outcome outcome) {
            this.outcome = outcome;
        }
    }

    private PostSources() {
    }

    /**
     * Injection point, filled in by the patch: the story's {@code actors}, the people and Pages that
     * wrote it, as a list of tree models, or null. The patch replaces this body with a call to
     * GraphQLStory's accessor, whose name changes every build. Only a GraphQLStory may be passed.
     */
    public static Object actors(Object story) {
        return StoryFlag.NOT_PATCHED;
    }

    /**
     * Injection point, filled in by the patch: the story's {@code attachments} as a list of
     * GraphQLStoryAttachment, or null. Only a GraphQLStory may be passed.
     */
    public static Object attachments(Object story) {
        return StoryFlag.NOT_PATCHED;
    }

    // The list.

    /** {@link #rules} of [stored], read again only when the list has changed since the last post. */
    static List<Rule> cachedRules(@Nullable String stored) {
        String key = stored == null ? "" : stored;
        Parsed last = parsed;
        if (last != null && last.stored.equals(key)) return last.rules;
        List<Rule> rules = Collections.unmodifiableList(rules(key));
        parsed = new Parsed(key, rules);
        return rules;
    }

    /** The rules in a stored list, in order. Blank, too long and repeated lines are left out. */
    static List<Rule> rules(@Nullable String stored) {
        if (stored == null || stored.isEmpty()) return Collections.emptyList();
        List<Rule> rules = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String line : stored.split("\n", -1)) {
            if (rules.size() == MAX_RULES) break;
            Rule rule = rule(line, rules.size() + 1);
            if (rule == null || !seen.add(key(rule))) continue;
            rules.add(rule);
        }
        return rules;
    }

    /**
     * What tells two rules apart: a site typed bare also names an author, so it isn't the same rule
     * as a link to that site. Two bare spellings of one site are, and the first is kept.
     */
    private static String key(Rule rule) {
        return rule.kind + ":" + rule.value + (rule.name == null ? "" : "|named");
    }

    /**
     * What one typed line is, or null when it's blank, too long, or a link to Facebook with no id
     * in it. A link to Facebook may run to {@link #MAX_FACEBOOK_LINK_LENGTH}.
     */
    @Nullable
    static Rule rule(String line, int number) {
        String trimmed = normalize(line);
        if (trimmed.isEmpty() || trimmed.length() > MAX_FACEBOOK_LINK_LENGTH) return null;
        if (DIGITS.matcher(trimmed).matches()) return new Rule(Kind.ID, trimmed, number);
        String site = site(trimmed);
        if (trimmed.length() > MAX_LENGTH && (site == null || !isFacebook(site))) return null;
        if (site != null && isFacebook(site)) {
            String id = facebookId(trimmed);
            return id == null ? null : new Rule(Kind.ID, id, number);
        }
        if (site != null) {
            boolean bare = trimmed.indexOf('/') < 0;
            return new Rule(Kind.SITE, site, number, bare ? trimmed.toLowerCase(Locale.ROOT) : null);
        }
        return new Rule(Kind.NAME, trimmed.toLowerCase(Locale.ROOT), number);
    }

    /** Whether [site] is one of Facebook's own domains or under one. */
    static boolean isFacebook(String site) {
        for (String own : FACEBOOK_SITES) {
            if (site.equals(own) || site.endsWith("." + own)) return true;
        }
        return false;
    }

    /**
     * The profile or Page id a link to Facebook carries, or null: profile.php?id=, the last part of
     * /people/name/id, or an id as the whole path.
     */
    @Nullable
    static String facebookId(String typed) {
        String value = typed.toLowerCase(Locale.ROOT);
        int scheme = value.indexOf("://");
        if (scheme >= 0) value = value.substring(scheme + 3);
        int slash = value.indexOf('/');
        if (slash < 0) return null;
        String rest = value.substring(slash);
        int query = rest.indexOf('?');
        String path = query < 0 ? rest : rest.substring(0, query);
        int fragment = path.indexOf('#');
        if (fragment >= 0) path = path.substring(0, fragment);
        if (query >= 0 && path.equals("/profile.php")) {
            Matcher id = FACEBOOK_ID.matcher(rest.substring(query));
            if (id.find()) return id.group(1);
        }
        List<String> parts = new ArrayList<>();
        for (String part : path.split("/")) {
            if (!part.isEmpty()) parts.add(part);
        }
        if (parts.size() == 1 && DIGITS.matcher(parts.get(0)).matches()) return parts.get(0);
        if (parts.size() == 3 && parts.get(0).equals("people") && DIGITS.matcher(parts.get(2)).matches()) {
            return parts.get(2);
        }
        return null;
    }

    /**
     * The list as it's kept: one rule per line, trimmed, in the order typed, with what can't be a
     * rule and what repeats left out. A name keeps its capitals, so the list reads as typed.
     */
    public static String clean(@Nullable String typed) {
        if (typed == null || typed.isEmpty()) return "";
        StringBuilder kept = new StringBuilder();
        Set<String> seen = new LinkedHashSet<>();
        int count = 0;
        int bytes = 0;
        for (String line : typed.split("\n", -1)) {
            if (count == MAX_RULES) break;
            Rule rule = rule(line, count + 1);
            if (rule == null || seen.contains(key(rule))) continue;
            String text = normalize(line);
            // A line break between rules takes two bytes in the file, as \n.
            int size = PostWords.encodedBytes(text) + (count > 0 ? 2 : 0);
            if (bytes + size > MAX_LIST_BYTES) break;
            seen.add(key(rule));
            if (count > 0) kept.append('\n');
            kept.append(text);
            bytes += size;
            count++;
        }
        return kept.toString();
    }

    /** Whether [value] is a list as {@link #clean} keeps it, so a settings file can't hold a looser one. */
    public static boolean isClean(@Nullable String value) {
        return value != null && value.equals(clean(value));
    }

    /** How many non-blank lines of [typed] {@link #clean} leaves out. */
    public static int leftOut(@Nullable String typed) {
        if (typed == null || typed.isEmpty()) return 0;
        int lines = 0;
        for (String line : typed.split("\n", -1)) {
            if (!normalize(line).isEmpty()) lines++;
        }
        String clean = clean(typed);
        return lines - (clean.isEmpty() ? 0 : clean.split("\n", -1).length);
    }

    /** How many rules a stored list holds. */
    public static int count(@Nullable String stored) {
        return rules(stored).size();
    }

    /** A line trimmed, in one Unicode form, with its runs of spaces made one. */
    private static String normalize(String line) {
        String composed = Normalizer.normalize(line, Normalizer.Form.NFC);
        return SPACES.matcher(composed).replaceAll(" ").trim();
    }

    /**
     * The site a typed line names, or null when it isn't one: a domain with a dot and no spaces,
     * with any scheme, path and leading www. dropped. "https://www.Example.com/news" is example.com.
     * A domain in another script is kept in its ASCII form, the way {@link #host} reads a link's.
     */
    @Nullable
    static String site(String typed) {
        if (typed.indexOf(' ') >= 0 || typed.indexOf('.') < 0) return null;
        String value = typed.toLowerCase(Locale.ROOT);
        int scheme = value.indexOf("://");
        if (scheme >= 0) value = value.substring(scheme + 3);
        int end = value.length();
        for (char stop : new char[] {'/', '?', '#', ':'}) {
            int at = value.indexOf(stop);
            if (at >= 0 && at < end) end = at;
        }
        value = value.substring(0, end);
        if (value.startsWith("www.")) value = value.substring(4);
        while (value.endsWith(".")) value = value.substring(0, value.length() - 1);
        value = ascii(value);
        return value != null && HOST.matcher(value).matches() ? value : null;
    }

    /**
     * [host] in the ASCII form a domain in another script takes (bücher.de is xn--bcher-kva.de),
     * lowercase, or null when it can't be one. An ASCII host comes back as it was.
     */
    @Nullable
    static String ascii(String host) {
        try {
            return IDN.toASCII(host, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException notADomain) {
            return null;
        }
    }

    /**
     * The site a link goes to, lowercase, in ASCII and without www., or null when it has no host. A
     * link through Facebook's redirect counts as the one it carries in its u parameter, and an app
     * link (fbrpc://facebook/nativethirdparty, which a post's link to YouTube or Instagram carries on
     * 586) as the one in its target_url. A backslash ends the host as a slash does, the way a browser
     * opens the link.
     */
    @Nullable
    static String host(@Nullable String url) {
        if (url == null) return null;
        String value = url.trim();
        int scheme = value.indexOf("://");
        if (scheme <= 0) return null;
        String rest = value.substring(scheme + 3).replace('\\', '/');
        int end = rest.length();
        for (char stop : new char[] {'/', '?', '#'}) {
            int at = rest.indexOf(stop);
            if (at >= 0 && at < end) end = at;
        }
        String authority = rest.substring(0, end);
        int user = authority.lastIndexOf('@');
        if (user >= 0) authority = authority.substring(user + 1);
        int port = authority.indexOf(':');
        if (port >= 0) authority = authority.substring(0, port);
        String host = authority.toLowerCase(Locale.ROOT);
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (host.isEmpty() || host.indexOf(' ') >= 0) return null;
        String inAscii = ascii(host);
        if (inAscii != null) host = inAscii;
        String target = null;
        if (host.equals("l.facebook.com") || host.equals("lm.facebook.com")) {
            target = carried(rest.substring(end), "/l.php", "u");
        } else if (host.equals("facebook") && value.substring(0, scheme).equalsIgnoreCase("fbrpc")) {
            target = carried(rest.substring(end), "/nativethirdparty", "target_url");
        }
        String inner = host(target);
        if (inner != null) return inner;
        return host.startsWith("www.") ? host.substring(4) : host;
    }

    /** The decoded [name] parameter of [path] when the part before its query is [expected], or null. */
    @Nullable
    private static String carried(String path, String expected, String name) {
        int query = path.indexOf('?');
        if (query < 0 || !path.substring(0, query).equals(expected)) return null;
        int fragment = path.indexOf('#', query);
        return queryParameter(path.substring(query + 1, fragment < 0 ? path.length() : fragment), name);
    }

    /** The decoded value of [name] in [query], or null when it's missing or can't be decoded. */
    @Nullable
    private static String queryParameter(String query, String name) {
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals < 0 || !pair.substring(0, equals).equals(name)) continue;
            try {
                return URLDecoder.decode(pair.substring(equals + 1), "UTF-8");
            } catch (IllegalArgumentException | java.io.UnsupportedEncodingException malformed) {
                return null;
            }
        }
        return null;
    }

    /**
     * The number of the first rule [found] matches, or 0: an id the same as an author's, a site the
     * same as a link's host or a domain it ends in, or a name the same as an author's, capitals and
     * extra spaces aside. A site typed bare matches an author of that name too.
     */
    static int match(List<Rule> rules, Found found) {
        for (Rule rule : rules) {
            switch (rule.kind) {
                case ID:
                    if (found.ids.contains(rule.value)) return rule.number;
                    break;
                case SITE:
                    for (String host : found.hosts) {
                        if (host.equals(rule.value) || host.endsWith("." + rule.value)) return rule.number;
                    }
                    if (rule.name != null && found.names.contains(rule.name)) return rule.number;
                    break;
                case NAME:
                    if (found.names.contains(rule.value)) return rule.number;
                    break;
            }
        }
        return 0;
    }

    // The post.

    /**
     * The authors and links of a feed unit and of the post it shares. Never throws. A unit that
     * isn't a story, or a story whose own authors and links can't be read, answers why with nothing
     * found, and the rule keeps it. When only the shared post can't be read, what the unit's own
     * authors and links hold still counts.
     */
    static Found read(@Nullable Object feedUnit, StoryFlag.Accessor actors, StoryFlag.Accessor attachments,
            StoryFlag.Accessor attached) {
        if (feedUnit == null) return new Found(Outcome.NO_UNIT);
        PostText.Members members = PostText.members();
        PostText.report();
        if (members.story == null || members.treeModel == null || members.cachedString == null) {
            return new Found(Outcome.NO_READER);
        }
        if (!members.story.isInstance(feedUnit)) return new Found(Outcome.NOT_A_STORY);

        Found found = new Found(Outcome.READ);
        Outcome own = readStory(feedUnit, actors, attachments, members, found);
        if (own != Outcome.READ) return new Found(own);

        Object wrapped;
        try {
            wrapped = attached.model(feedUnit);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.POST_WORDS, "attached story accessor", failure);
            return found;
        }
        if (wrapped != null && wrapped != StoryFlag.NOT_PATCHED && members.story.isInstance(wrapped)) {
            // A failure here is in the hook status already; the share's own sources still count.
            Found shared = new Found(Outcome.READ);
            if (readStory(wrapped, actors, attachments, members, shared) == Outcome.READ) {
                found.ids.addAll(shared.ids);
                found.names.addAll(shared.names);
                found.hosts.addAll(shared.hosts);
            }
        }
        return found;
    }

    /**
     * What kind of author wrote a feed unit, for Hide posts from Pages: {@link #PAGE} when every one
     * of the story's own authors is a Page by {@link #authorType}, otherwise the first other author's
     * type ({@link #UNKNOWN_AUTHOR} when it can't be read), {@link #NO_AUTHORS}, or why nothing was
     * read. The post a share wraps isn't read. Never throws.
     */
    static String authorKind(@Nullable Object feedUnit, StoryFlag.Accessor actors) {
        if (feedUnit == null) return Outcome.NO_UNIT.reason;
        PostText.Members members = PostText.members();
        if (members.story == null || members.treeModel == null) return Outcome.NO_READER.reason;
        if (!members.story.isInstance(feedUnit)) return Outcome.NOT_A_STORY.reason;
        Object authors;
        try {
            authors = actors.model(feedUnit);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.POST_WORDS, "author accessor", failure);
            return Outcome.READ_FAILED.reason;
        }
        if (authors == StoryFlag.NOT_PATCHED) {
            HookStatus.missingMember(FamilyNames.POST_WORDS, "method", StoryFlag.STORY_CLASS,
                    "the " + ACTORS_FIELD + " accessor");
            return Outcome.NO_ACCESSOR.reason;
        }
        boolean any = false;
        for (Object author : items(authors)) {
            if (!members.treeModel.isInstance(author)) continue;
            any = true;
            String type = authorType(author);
            if (!PAGE.equals(type)) return type == null || type.isEmpty() ? UNKNOWN_AUTHOR : type;
        }
        return any ? PAGE : NO_AUTHORS;
    }

    /** A person's GraphQL type, as an author's {@code getTypeName()} answers it. */
    static final String PERSON = "User";
    /** The person field that says whether you're friends, or could ask to be. */
    static final String FRIENDSHIP_FIELD = "friendship_status";
    static final int FRIENDSHIP_KEY = FRIENDSHIP_FIELD.hashCode();
    /** What {@link #friendKind} starts the kind of a post that isn't a friend's with. */
    static final String NOT_A_FRIEND = "not a friend: ";
    /** What {@link #friendKind} starts the kind of a friend's post with. */
    static final String FRIEND = "friend: ";
    /** A person whose friendship status reads nothing, so they count as a friend. */
    static final String NO_FRIENDSHIP = "no friendship status";
    /** The statuses of a person you aren't friends with: you could send a request, or one is on its way. */
    private static final String[] NOT_FRIENDS = {"CAN_REQUEST", "OUTGOING_REQUEST", "INCOMING_REQUEST"};

    /** The person fields that, read with the friendship status, tell a Page Facebook types as a person. */
    static final String GENDER_FIELD = "gender";
    static final int GENDER_KEY = GENDER_FIELD.hashCode();
    static final String FOLLOW_FIELD = "subscribe_status";
    static final int FOLLOW_KEY = FOLLOW_FIELD.hashCode();
    /** The friendship status of someone you can't ask: a Page, your own posts, or a person who takes no requests. */
    static final String CANNOT_REQUEST = "CANNOT_REQUEST";
    /** The genders a Page answers, and the follow statuses of an author you can follow. */
    private static final String[] PAGE_GENDERS = {"NEUTER", "UNKNOWN"};
    private static final String[] FOLLOWABLE = {"CAN_SUBSCRIBE", "IS_SUBSCRIBED"};

    /**
     * An author's GraphQL type, as {@code getTypeName()} answers it, but {@link #PAGE} for a Page
     * that Facebook types as a person. On 586 every author of a Home story answers {@link #PERSON},
     * the Pages behind sponsored posts included (#262), and the model holds no page field. A Page
     * reads three person fields its own way: you can't ask it to be a friend ({@code CANNOT_REQUEST}),
     * its gender is {@code NEUTER} or {@code UNKNOWN}, and you can follow it. A person shows a gender
     * of their own, or can be asked, or is a friend. Someone with neutral pronouns who takes no
     * friend requests and can be followed is the one person this takes for a Page. Null when the
     * type can't be read.
     */
    @Nullable
    static String authorType(Object author) {
        String type = FeedFilter.typeName(author);
        if (!PERSON.equals(type) || !oneOf(field(author, GENDER_KEY), PAGE_GENDERS)) return type;
        if (!CANNOT_REQUEST.equals(field(author, FRIENDSHIP_KEY))) return type;
        return oneOf(field(author, FOLLOW_KEY), FOLLOWABLE) ? PAGE : type;
    }

    /**
     * What Only friends' posts reads of a feed unit (#406): {@link #FRIEND} and the status of the
     * first of its own authors who is a friend; when none is, {@link #NOT_A_FRIEND} and why, the
     * first author's type when it isn't a person (a Page's, say, {@link #authorType} finding the ones
     * typed as people) or the status of a person you aren't friends with. A person whose status reads
     * nothing counts as a friend, and so does {@code CANNOT_REQUEST}, which is what your own posts
     * answer. An author whose type can't be read is skipped ({@link #UNKNOWN_AUTHOR} when that's all
     * of them). The post a share wraps isn't read, so a friend's share stays. Never throws.
     */
    static String friendKind(@Nullable Object feedUnit, StoryFlag.Accessor actors) {
        if (feedUnit == null) return Outcome.NO_UNIT.reason;
        PostText.Members members = PostText.members();
        if (members.story == null || members.treeModel == null) return Outcome.NO_READER.reason;
        if (!members.story.isInstance(feedUnit)) return Outcome.NOT_A_STORY.reason;
        Object authors;
        try {
            authors = actors.model(feedUnit);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.POST_WORDS, "author accessor", failure);
            return Outcome.READ_FAILED.reason;
        }
        if (authors == StoryFlag.NOT_PATCHED) {
            HookStatus.missingMember(FamilyNames.POST_WORDS, "method", StoryFlag.STORY_CLASS,
                    "the " + ACTORS_FIELD + " accessor");
            return Outcome.NO_ACCESSOR.reason;
        }
        boolean any = false;
        String friend = null;
        String notAFriend = null;
        for (Object author : items(authors)) {
            if (!members.treeModel.isInstance(author)) continue;
            any = true;
            String type = authorType(author);
            if (type == null || type.isEmpty()) continue;
            if (!PERSON.equals(type)) {
                if (notAFriend == null) notAFriend = NOT_A_FRIEND + type;
                continue;
            }
            String status = field(author, FRIENDSHIP_KEY);
            if (oneOf(status, NOT_FRIENDS)) {
                if (notAFriend == null) notAFriend = NOT_A_FRIEND + status;
            } else if (friend == null) {
                friend = FRIEND + (status == null || status.isEmpty() ? NO_FRIENDSHIP : status);
            }
        }
        if (friend != null) return friend;
        if (notAFriend != null) return notAFriend;
        return any ? UNKNOWN_AUTHOR : NO_AUTHORS;
    }

    /** Whether what {@link #friendKind} found takes the post out under Only friends' posts. */
    static boolean notAFriend(@Nullable String kind) {
        return kind != null && kind.startsWith(NOT_A_FRIEND);
    }

    private static boolean oneOf(@Nullable String value, String[] values) {
        for (String one : values) {
            if (one.equals(value)) return true;
        }
        return false;
    }

    /** The tree's public {@code getString(int)}, found on the first person asked. */
    @Nullable
    private static volatile Method treeString;

    /**
     * One of a person's enum fields (friendship status, gender, follow status) as text, or null when
     * the tree doesn't hold it or can't be read. Facebook reads these with {@code getCachedEnum},
     * which keeps its enum in the model's own cache and casts whatever it finds there, so a String
     * {@code getCachedString} left in that slot crashed Facebook's next read of it (#406). So they're
     * read straight from the tree with {@code getString(int)}, the read {@code getCachedEnum} makes
     * itself, which caches nothing. A person whose tree is gone isn't asked.
     */
    @Nullable
    private static String field(Object person, int key) {
        try {
            Method read = treeString;
            if (read == null) {
                Method found = person.getClass().getMethod("getString", int.class);
                if (found.getReturnType() != String.class) return null;
                treeString = read = found;
            }
            if (FeedFilter.released(person)) return null;
            Object value = read.invoke(person, key);
            return value instanceof String ? (String) value : null;
        } catch (ReflectiveOperationException | RuntimeException unreadable) {
            // A field the tree won't read counts as none, so the post stays.
            return null;
        }
    }

    /** Adds [story]'s author ids and names and its link hosts to [found]. */
    private static Outcome readStory(Object story, StoryFlag.Accessor actors, StoryFlag.Accessor attachments,
            PostText.Members members, Found found) {
        Object authors;
        Object links;
        try {
            authors = actors.model(story);
            links = attachments.model(story);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.POST_WORDS, "author or link accessor", failure);
            return Outcome.READ_FAILED;
        }
        if (authors == StoryFlag.NOT_PATCHED) {
            HookStatus.missingMember(FamilyNames.POST_WORDS, "method", StoryFlag.STORY_CLASS,
                    "the " + ACTORS_FIELD + " accessor");
            return Outcome.NO_ACCESSOR;
        }
        if (links == StoryFlag.NOT_PATCHED) {
            HookStatus.missingMember(FamilyNames.POST_WORDS, "method", StoryFlag.STORY_CLASS,
                    "the " + ATTACHMENTS_FIELD + " accessor");
            return Outcome.NO_ACCESSOR;
        }
        try {
            for (Object author : items(authors)) {
                if (!members.treeModel.isInstance(author)) continue;
                String id = string(members, author, ID_KEY);
                if (id != null && !id.isEmpty()) found.ids.add(id);
                String name = string(members, author, NAME_KEY);
                if (name != null) {
                    String normalized = normalize(name).toLowerCase(Locale.ROOT);
                    if (!normalized.isEmpty()) found.names.add(normalized);
                }
            }
            for (Object link : items(links)) {
                if (!members.treeModel.isInstance(link)) continue;
                String host = host(string(members, link, URL_KEY));
                if (host != null) found.hosts.add(host);
            }
            return Outcome.READ;
        } catch (InvocationTargetException failure) {
            HookStatus.threw(FamilyNames.POST_WORDS, "author or link reader", failure.getCause());
            return Outcome.READ_FAILED;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            HookStatus.threw(FamilyNames.POST_WORDS, "author or link reader", failure);
            return Outcome.READ_FAILED;
        }
    }

    private static Iterable<?> items(@Nullable Object list) {
        return list instanceof Iterable ? (Iterable<?>) list : Collections.emptyList();
    }

    @Nullable
    private static String string(PostText.Members members, Object model, int key) throws ReflectiveOperationException {
        Object value = members.cachedString.invoke(model, key);
        return value instanceof String ? (String) value : null;
    }
}
