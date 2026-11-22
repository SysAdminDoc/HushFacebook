import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.iface.ExceptionHandler;
import com.android.tools.smali.dexlib2.iface.Field;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.iface.MultiDexContainer;
import com.android.tools.smali.dexlib2.iface.TryBlock;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.SwitchElement;
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload;
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.formats.ArrayPayload;
import com.android.tools.smali.dexlib2.iface.reference.FieldReference;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.iface.reference.Reference;
import com.android.tools.smali.dexlib2.iface.reference.StringReference;
import com.android.tools.smali.dexlib2.iface.reference.TypeReference;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Names every method a patched APK does not share with the clean one it was built from, and prints
 * the register evidence for each: how many registers the method declares, and every instruction the
 * two bodies do not have in common.
 *
 * <p>The point of it is the last line of the report. A patch that writes into a register the method
 * never declared assembles happily and only fails when a device verifies the class, so every
 * instruction that was not in the clean body is checked against the register count of the method it
 * landed in. Methods the patch adds outright, which is where a hand-written bridge lives and where
 * the registers are chosen rather than inherited, are checked the same way: every one of them, the
 * helpers a patch adds to one of Facebook's own classes as well as the extension's. Every
 * instruction of every changed and added method is also held to its method's count in the structural
 * pass, the upper half of each wide value it reads or writes included, and one out of range is a
 * finding there rather than a register the later checks step over.
 *
 * <p>Three things this deliberately does not leave to chance:
 *
 * <ul>
 *   <li>A wide instruction names one register and occupies two, so a destination that sets a wide
 *       register counts as reaching one higher than it names. Missing that would let a
 *       {@code const-wide} one short of the ceiling read as safe.
 *   <li>The highest register travels with the rendered line rather than being read back out of the
 *       text, because this app has string constants that look like register names ({@code v4190},
 *       {@code v20200906}) and parsing the text flags five of them.
 *   <li>Absence of evidence is a failure, not a pass. A run that finds no changed methods, or no
 *       added methods of the extension's own, is comparing the wrong pair of files and says so
 *       instead of reporting nothing wrong.
 * </ul>
 *
 * <p>Branch targets and try-block ranges are part of a body's identity here, so a change that only
 * moves a jump or widens an exception range still shows up as a changed method.
 *
 * <p>Every changed and added method is also held to the structural rules Facebook's own crash
 * reports came from (FroggoMorphePatches issues 3, 16 and 21): a branch or switch case that lands
 * inside an instruction ("target dex pc is not at instruction start"), an invoke whose registers
 * don't match what the callee takes, a parameter register read as the wrong kind (the static and
 * wide off-by-one), a register the body wrote at the wrong width (a narrow const left where a
 * const-wide was, which the AMOLED sweep did on 580), a move-result cut off from its invoke, an
 * extension's Object answer returned uncast from a method declared to return a class (Comment
 * sheet options in 0.16.0), and a try range or handler off an instruction boundary. A contract file adds
 * rules about the whole APK: the feed filter's guard is called once, first thing, in the one
 * method doing the funnel's work (addNewEdgeToCollection, or the body 583's wrapper hands its edge
 * to), because two guards stacked on that method is what broke Froggo's builds; and each extension stub a patch fills in with one of Facebook's renamed accessors calls
 * it before it returns, so a patch that stopped filling one fails here instead of shipping a stub
 * that answers its marker forever; and each of the two Stories tray adapter classes and Home's
 * composer row gets one
 * count of its own asking the extension, and the reels hook comes first in the pre-EOF injector;
 * and the settings patch's
 * stand-in for the Facebook logo's touch listener comes right after the logo gets its tap, on the
 * same view; and the reel watch-history hook takes the place of the one call in the batcher's flush
 * that hands its batch to an executor, on that call's registers; and the runnable that swaps an
 * edge into the feed asks the extension about it once. A start-call, next-call, sole-call or
 * once-call rule names its method by the strings it loads, and a shape where strings alone don't
 * tell it apart, and exactly one of Facebook's methods may answer that: five hold
 * "FeedRefreshTriggerController" on 580, so a rule naming that string alone passed a hook in any
 * of them, and the logo rule named one string and counted calls in any method holding it. The
 * device verifier stays the authority; these catch the known shapes without a phone.
 *
 *   java -cp &lt;cli jar&gt; DexDiff.java &lt;cleanApk&gt; &lt;patchedApk&gt; &lt;reportFile&gt;
 *       &lt;removalAllowlist&gt; [&lt;contracts&gt;]
 */
public class DexDiff {

    /** Anything under here is the bundle's own code rather than the host's. */
    private static final String OWN = "Lapp/morphe/";

    private static final String OBJECT = "Ljava/lang/Object;";

    private static final class RemovalAllowlist {
        final Set<String> methods = new TreeSet<>();
        final Set<String> dexEntries = new TreeSet<>();
    }

    private static RemovalAllowlist readRemovalAllowlist(File file) throws Exception {
        if (!file.isFile()) throw new IllegalArgumentException("Removal allowlist not found: " + file);
        RemovalAllowlist allowlist = new RemovalAllowlist();
        int lineNumber = 0;
        for (String raw : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
            lineNumber++;
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            Set<String> target;
            String value;
            if (line.startsWith("method ")) {
                target = allowlist.methods;
                value = line.substring("method ".length()).trim();
            } else if (line.startsWith("dex ")) {
                target = allowlist.dexEntries;
                value = line.substring("dex ".length()).trim();
            } else {
                throw new IllegalArgumentException("Invalid removal allowlist line " + lineNumber
                        + ": expected method or dex");
            }
            if (value.isEmpty() || !target.add(value)) {
                throw new IllegalArgumentException("Invalid removal allowlist line " + lineNumber
                        + ": value is empty or duplicated");
            }
        }
        return allowlist;
    }

    /**
     * One rule about the whole APK.
     *
     * <ul>
     *   <li>"single-call &lt;method reference&gt; in &lt;caller method name&gt;": exactly one call
     *       site, there.
     *   <li>"count-call &lt;method reference&gt; in &lt;caller method name&gt; &lt;n&gt;": exactly n
     *       call sites, each in a method of that name and each in a class of its own. For a hook
     *       the patch adds to several classes as a method of their own.
     *   <li>"first-call &lt;method reference&gt; on &lt;class&gt;": the method calls a method of
     *       &lt;class&gt; that takes no arguments before its first return or throw. A stub the
     *       patch filled does; one still answering its marker doesn't.
     *   <li>"first-call &lt;method reference&gt; on-type-named &lt;GraphQL type&gt;": the same, where
     *       the class is the one whose {@code getTypeName()} answers &lt;GraphQL type&gt; as a
     *       literal. For an accessor on a class Redex renames, which no contract can name, and
     *       exactly one class may answer the type.
     *   <li>"first-call &lt;method reference&gt; outside &lt;class prefix&gt;": the method calls a
     *       method of a class whose type doesn't start with &lt;class prefix&gt;, whatever it takes,
     *       before its first return or throw. For a stub the patch fills with a call to a method it
     *       found by what the method does, whose class is a Redex name and no model answering a type
     *       name. A stub still answering its marker makes no call, and one whose only call stays in
     *       the extension doesn't leave it.
     *   <li>"start-call &lt;method reference&gt; [in [static|instance] &lt;shape&gt;] holding
     *       &lt;string&gt; [&lt;string&gt; ...]": exactly one method outside the bundle's own code
     *       loads every one of the strings and has the shape, a descriptor such as
     *       {@code (Lcom/facebook/auth/usersession/FbUserSession;*)Z} where * stands for any run of
     *       characters. That method calls the method reference, with nothing before the call but
     *       plain instructions (no other call, branch, switch, return or throw), and no other
     *       method loading the strings calls it.
     *   <li>"no-call &lt;method reference&gt; outside &lt;class prefix&gt;": no class but those whose
     *       type starts with &lt;class prefix&gt; calls it. For a call the patch sends to the
     *       extension everywhere, where the extension makes the real one and a call left anywhere
     *       else would undo what the patch is for.
     *   <li>"next-call &lt;method reference&gt; after &lt;method reference&gt; [in [static|instance]
     *       &lt;shape&gt;] holding &lt;string&gt; [&lt;string&gt; ...]": exactly one method outside the
     *       bundle's own code loads every one of the strings and has the shape, as for start-call.
     *       That method calls the first method once, no other host method calls it, and the
     *       instruction just before the call calls the second method, on the same first register.
     *       For a hook sent in place of one call of a known pair, so it can't drift onto another
     *       view, another call or another method holding the same strings.
     *   <li>"sole-call &lt;method reference&gt; replacing &lt;method reference&gt; [in [static|instance]
     *       &lt;shape&gt;] holding &lt;string&gt; [&lt;string&gt; ...]": exactly one method outside the
     *       bundle's own code loads every one of the strings and has the shape. It calls the first
     *       method once and the second not at all, no other host method calls the first, and that
     *       call reads the registers, in their order, that the clean build's one call to the
     *       second method there read. For a hook that takes one call's place: left out or left
     *       beside the call it stands in for, the call it replaces still happens, and on other
     *       registers the hook gets the wrong values.
     *   <li>"once-call &lt;method reference&gt; [in [static|instance] &lt;shape&gt;] holding &lt;string&gt;
     *       [&lt;string&gt; ...]": exactly one method outside the bundle's own code loads every one of
     *       the strings and has the shape, as for start-call. That method calls the method reference
     *       once, anywhere in its body, and no other host method calls it. For a hook that has to
     *       read what the method made first, so it can't come first, and follows no one call.
     * </ul>
     * For those same four, a &lt;string&gt; written as a class descriptor is held by the methods that
     * class defines, for a method of a class Redex keeps the name of whose own strings went to
     * string tables (Facebook 588's FriendsUriMapHelper loads none).
     * For start-call, next-call, sole-call and once-call, a method that loads some of the strings
     * also holds the ones a constructor it calls loads, since a build can move a string into the
     * class the method builds (Facebook 584 builds the seen-state mutation's input, field name and
     * all, in the input class's constructor).
     */
    /** The kind a first-call rule that names its class by GraphQL type is read into. */
    private static final String TYPED_FIRST_CALL = "first-call-typed";
    /** The kind a first-call rule that asks only for a call leaving a class prefix is read into. */
    private static final String OUTSIDE_FIRST_CALL = "first-call-outside";

    private static final class Contract {
        final String kind;
        final String callee;
        final String target;
        /** For a next-call rule, the call that has to come just before; null for the others. */
        final String after;
        /** For a sole-call rule, the call the callee stands in for; null for the others. */
        final String replaced;
        /** start-call, next-call, sole-call and once-call: the strings its method loads, every one of them. */
        final List<String> strings;
        /** The same rules: whether its method is static, or null when the rule doesn't say. */
        final Boolean isStatic;
        /** The same rules: its method's descriptor, "*" for any run of characters, or null. */
        final String shape;
        /** For a count-call rule, how many call sites, each in a class of its own; 1 for the others. */
        final int count;

        Contract(String kind, String callee, String target) {
            this(kind, callee, target, 1);
        }

        Contract(String kind, String callee, String target, int count) {
            this(kind, callee, target, null, null, List.of(), null, null, count);
        }

        Contract(String kind, String callee, String target, String after, String replaced, List<String> strings,
                Boolean isStatic, String shape) {
            this(kind, callee, target, after, replaced, strings, isStatic, shape, 1);
        }

        private Contract(String kind, String callee, String target, String after, String replaced,
                List<String> strings, Boolean isStatic, String shape, int count) {
            this.kind = kind;
            this.callee = callee;
            this.target = target;
            this.after = after;
            this.replaced = replaced;
            this.strings = strings;
            this.isStatic = isStatic;
            this.shape = shape;
            this.count = count;
        }

        /** Whether this rule picks its method by strings and a shape: start-call, next-call, sole-call and once-call. */
        boolean picks() {
            return kind.equals("start-call") || kind.equals("next-call") || kind.equals("sole-call")
                    || kind.equals("once-call");
        }

        /** How a rule that picks its method reads in the contract file, from its kind on. */
        String rule() {
            StringBuilder b = new StringBuilder(kind).append(' ').append(callee);
            if (after != null) b.append(" after ").append(after);
            if (replaced != null) b.append(" replacing ").append(replaced);
            if (shape != null) {
                b.append(" in ");
                if (isStatic != null) b.append(isStatic ? "static " : "instance ");
                b.append(shape);
            }
            return b.append(" holding ").append(String.join(" ", strings)).toString();
        }

        /** Whether [m] has this rule's shape: its static flag and its descriptor. */
        boolean hasShape(Method m) {
            if (isStatic != null && AccessFlags.STATIC.isSet(m.getAccessFlags()) != isStatic) return false;
            if (shape == null) return true;
            StringBuilder descriptor = new StringBuilder("(");
            for (CharSequence p : m.getParameterTypes()) descriptor.append(p);
            descriptor.append(')').append(m.getReturnType());
            String[] pieces = shape.split("\\*", -1);
            StringBuilder pattern = new StringBuilder();
            for (int k = 0; k < pieces.length; k++) {
                if (k > 0) pattern.append(".*");
                pattern.append(java.util.regex.Pattern.quote(pieces[k]));
            }
            return descriptor.toString().matches(pattern.toString());
        }
    }

    /** How each rule that picks its method by strings and a shape is written, for the message a bad line gets. */
    private static final Map<String, String> PICKED_FORMS = Map.of(
            "start-call", "start-call <method reference>",
            "next-call", "next-call <method reference> after <method reference>",
            "sole-call", "sole-call <method reference> replacing <method reference>",
            "once-call", "once-call <method reference>");

    /**
     * A start-call, next-call, sole-call or once-call line: its method reference, the next-call's
     * "after &lt;method reference&gt;" or the sole-call's "replacing &lt;method reference&gt;", then "[in
     * [static|instance] &lt;shape&gt;] holding &lt;string&gt; [&lt;string&gt; ...]". Null when it isn't one.
     */
    private static Contract readPicked(String[] parts) {
        String kind = parts[0];
        if (parts.length < 2 || !parts[1].contains("->")) return null;
        int at = 2;
        String after = null;
        String replaced = null;
        if (kind.equals("next-call") || kind.equals("sole-call")) {
            String word = kind.equals("next-call") ? "after" : "replacing";
            if (parts.length < 4 || !parts[2].equals(word) || !parts[3].contains("->")) return null;
            if (kind.equals("next-call")) after = parts[3];
            else replaced = parts[3];
            at = 4;
        }
        Boolean isStatic = null;
        String shape = null;
        if (at < parts.length && parts[at].equals("in")) {
            at++;
            if (at < parts.length && (parts[at].equals("static") || parts[at].equals("instance"))) {
                isStatic = parts[at].equals("static");
                at++;
            }
            if (at >= parts.length || !parts[at].startsWith("(") || parts[at].indexOf(')') < 1) return null;
            shape = parts[at++];
        }
        if (at >= parts.length || !parts[at].equals("holding")) return null;
        List<String> strings = new ArrayList<>(Arrays.asList(parts).subList(at + 1, parts.length));
        if (strings.isEmpty() || new TreeSet<>(strings).size() != strings.size()) return null;
        return new Contract(kind, parts[1], String.join(" ", strings), after, replaced, strings, isStatic, shape);
    }

    private static List<Contract> readContracts(File file) throws Exception {
        List<Contract> contracts = new ArrayList<>();
        if (file == null) return contracts;
        if (!file.isFile()) throw new IllegalArgumentException("Contract file not found: " + file);
        int lineNumber = 0;
        for (String raw : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
            lineNumber++;
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\\s+");
            String form = PICKED_FORMS.get(parts[0]);
            if (form != null) {
                Contract picked = readPicked(parts);
                if (picked == null) {
                    throw new IllegalArgumentException("Invalid contract line " + lineNumber + ": expected " + form
                            + " [in [static|instance] <(parameters)return>] holding <string> [<string> ...]");
                }
                contracts.add(picked);
                continue;
            }
            boolean singleCall = parts.length == 4 && parts[0].equals("single-call") && parts[2].equals("in");
            boolean countCall = parts.length == 5 && parts[0].equals("count-call") && parts[2].equals("in")
                    && parts[4].matches("[1-9][0-9]*");
            boolean firstCall = parts.length == 4 && parts[0].equals("first-call") && parts[2].equals("on")
                    && parts[3].startsWith("L") && parts[3].endsWith(";");
            boolean firstCallTyped = parts.length == 4 && parts[0].equals("first-call")
                    && parts[2].equals("on-type-named") && parts[3].matches("[A-Za-z][A-Za-z0-9_]*");
            boolean firstCallOutside = parts.length == 4 && parts[0].equals("first-call") && parts[2].equals("outside")
                    && parts[3].startsWith("L") && parts[3].endsWith("/");
            boolean noCall = parts.length == 4 && parts[0].equals("no-call") && parts[2].equals("outside")
                    && parts[3].startsWith("L") && parts[3].endsWith("/");
            if ((!singleCall && !countCall && !firstCall && !firstCallTyped && !firstCallOutside && !noCall)
                    || !parts[1].contains("->")) {
                throw new IllegalArgumentException("Invalid contract line " + lineNumber
                        + ": expected single-call <method reference> in <caller method name>,"
                        + " count-call <method reference> in <caller method name> <n>,"
                        + " first-call <method reference> on <class>,"
                        + " first-call <method reference> on-type-named <GraphQL type>,"
                        + " first-call <method reference> outside <package prefix ending in />,"
                        + " no-call <method reference> outside <package prefix ending in />,"
                        + " or start-call <method reference>, next-call <method reference> after <method reference>,"
                        + " sole-call <method reference> replacing <method reference>"
                        + " or once-call <method reference>, each then"
                        + " [in [static|instance] <(parameters)return>] holding <string> [<string> ...]");
            }
            if (countCall) {
                contracts.add(new Contract(parts[0], parts[1], parts[3], Integer.parseInt(parts[4])));
                continue;
            }
            String kind = firstCallTyped ? TYPED_FIRST_CALL : firstCallOutside ? OUTSIDE_FIRST_CALL : parts[0];
            contracts.add(new Contract(kind, parts[1], parts[3]));
        }
        return contracts;
    }

    /** Every instruction of a body with its code-unit address, and where the body ends. */
    private static final class Layout {
        final List<Instruction> instructions = new ArrayList<>();
        final List<Integer> addresses = new ArrayList<>();
        final Map<Integer, Instruction> byAddress = new HashMap<>();
        int size;

        Layout(MethodImplementation impl) {
            int address = 0;
            for (Instruction i : impl.getInstructions()) {
                instructions.add(i);
                addresses.add(address);
                byAddress.put(address, i);
                address += i.getCodeUnits();
            }
            size = address;
        }

        boolean isStart(int address) {
            return byAddress.containsKey(address);
        }
    }

    /** Registers a parameter of this type takes: two for long and double, one for anything else. */
    private static int slots(CharSequence type) {
        char c = type.charAt(0);
        return c == 'J' || c == 'D' ? 2 : 1;
    }

    /** The kind a value of this type has in a register: L object, W wide (low half), I narrow. */
    private static char kindOf(CharSequence type) {
        char c = type.charAt(0);
        if (c == 'J' || c == 'D') return 'W';
        if (c == 'L' || c == '[') return 'L';
        return 'I';
    }

    private static boolean isStaticInvoke(Opcode opcode) {
        return opcode == Opcode.INVOKE_STATIC || opcode == Opcode.INVOKE_STATIC_RANGE;
    }

    /** The invoke kinds whose registers are the callee's receiver and parameters, in order. */
    private static boolean isPlainInvoke(Opcode opcode) {
        switch (opcode) {
            case INVOKE_VIRTUAL: case INVOKE_SUPER: case INVOKE_DIRECT: case INVOKE_STATIC:
            case INVOKE_INTERFACE: case INVOKE_VIRTUAL_RANGE: case INVOKE_SUPER_RANGE:
            case INVOKE_DIRECT_RANGE: case INVOKE_STATIC_RANGE: case INVOKE_INTERFACE_RANGE:
                return true;
            default:
                return false;
        }
    }

    /** The registers an invoke passes, in argument order. */
    private static int[] invokeRegisters(Instruction i) {
        if (i instanceof RegisterRangeInstruction) {
            RegisterRangeInstruction r = (RegisterRangeInstruction) i;
            int[] regs = new int[r.getRegisterCount()];
            for (int k = 0; k < regs.length; k++) regs[k] = r.getStartRegister() + k;
            return regs;
        }
        FiveRegisterInstruction r = (FiveRegisterInstruction) i;
        int[] all = { r.getRegisterC(), r.getRegisterD(), r.getRegisterE(), r.getRegisterF(), r.getRegisterG() };
        int[] regs = new int[r.getRegisterCount()];
        System.arraycopy(all, 0, regs, 0, regs.length);
        return regs;
    }

    /**
     * For each instruction, the parameter registers some path from the entry may have written
     * before it runs, as bits counted from the first parameter register, or null where no path
     * reaches it. A register no path has written still holds its argument, so its kind is known.
     * An exception edge carries the state after the throwing instruction, which only adds writes,
     * so wherever a path is in doubt the check that reads this stays quiet.
     */
    private static BitSet[] parameterWrites(MethodImplementation impl, Layout layout, int firstParameter) {
        int count = layout.instructions.size();
        BitSet[] before = new BitSet[count];
        if (count == 0) return before;
        Map<Integer, Integer> indexAt = new HashMap<>();
        for (int k = 0; k < count; k++) indexAt.put(layout.addresses.get(k), k);
        Deque<Integer> work = new ArrayDeque<>();
        before[0] = new BitSet();
        work.add(0);
        while (!work.isEmpty()) {
            int k = work.poll();
            Instruction i = layout.instructions.get(k);
            Opcode opcode = i.getOpcode();
            int at = layout.addresses.get(k);
            BitSet after = (BitSet) before[k].clone();
            if (opcode.setsRegister() && i instanceof OneRegisterInstruction) {
                int destination = ((OneRegisterInstruction) i).getRegisterA();
                int last = destination + (opcode.setsWideRegister() ? 1 : 0);
                for (int r = Math.max(destination, firstParameter); r <= last; r++) after.set(r - firstParameter);
            }
            List<Integer> next = new ArrayList<>();
            if (opcode.canContinue()) next.add(at + i.getCodeUnits());
            if (i instanceof OffsetInstruction && opcode != Opcode.FILL_ARRAY_DATA) {
                int target = at + ((OffsetInstruction) i).getCodeOffset();
                if (opcode == Opcode.PACKED_SWITCH || opcode == Opcode.SPARSE_SWITCH) {
                    Instruction payload = layout.byAddress.get(target);
                    if (payload instanceof SwitchPayload) {
                        for (SwitchElement element : ((SwitchPayload) payload).getSwitchElements()) {
                            next.add(at + element.getOffset());
                        }
                    }
                } else {
                    next.add(target);
                }
            }
            if (opcode.canThrow()) {
                for (TryBlock<? extends ExceptionHandler> block : impl.getTryBlocks()) {
                    int start = block.getStartCodeAddress();
                    if (at < start || at >= start + block.getCodeUnitCount()) continue;
                    for (ExceptionHandler handler : block.getExceptionHandlers()) {
                        next.add(handler.getHandlerCodeAddress());
                    }
                }
            }
            for (int address : next) {
                Integer successor = indexAt.get(address);
                if (successor == null) continue;
                if (before[successor] == null) {
                    before[successor] = (BitSet) after.clone();
                    work.add(successor);
                    continue;
                }
                BitSet merged = (BitSet) before[successor].clone();
                merged.or(after);
                if (!merged.equals(before[successor])) {
                    before[successor] = merged;
                    work.add(successor);
                }
            }
        }
        return before;
    }

    private static boolean isMoveResult(Opcode opcode) {
        return opcode == Opcode.MOVE_RESULT || opcode == Opcode.MOVE_RESULT_WIDE
                || opcode == Opcode.MOVE_RESULT_OBJECT;
    }

    /**
     * The kind each register holds before each instruction, along every path from the entry, or
     * null where no path reaches it: L an object, I a narrow value, Z a zero constant (narrow or
     * null), W and w the halves of a wide value, B and b the halves of one whose other half was
     * overwritten, C a register the paths that meet there disagree about, T unknown. The
     * parameters start with their declared kinds; constants, moves, move-results and the plain
     * object producers set theirs; a wide result sets a pair; anything else leaves T, which no
     * check reads. Where paths meet, the kinds join the way ART merges them (see join), so a pair
     * broken or a width changed on one arm of a branch is still a finding after the arms rejoin.
     * An exception edge carries the kinds from before the throwing instruction, as ART's does: the
     * exception leaves before the instruction writes anything.
     */
    private static char[][] registerKinds(MethodImplementation impl, Layout layout,
            Map<Integer, Character> parameterKind) {
        int count = layout.instructions.size();
        int registers = impl.getRegisterCount();
        char[][] before = new char[count][];
        if (count == 0) return before;
        Map<Integer, Integer> indexAt = new HashMap<>();
        for (int k = 0; k < count; k++) indexAt.put(layout.addresses.get(k), k);
        char[] entry = new char[registers];
        java.util.Arrays.fill(entry, 'T');
        for (Map.Entry<Integer, Character> p : parameterKind.entrySet()) {
            if (p.getKey() < registers) entry[p.getKey()] = p.getValue();
        }
        Deque<Integer> work = new ArrayDeque<>();
        before[0] = entry;
        work.add(0);
        while (!work.isEmpty()) {
            int k = work.poll();
            Instruction i = layout.instructions.get(k);
            Opcode opcode = i.getOpcode();
            int at = layout.addresses.get(k);
            char[] after = transfer(i, before[k]);
            for (int address : successors(layout, i, at)) flow(before, indexAt, work, address, after);
            for (int address : handlers(impl, i, at)) flow(before, indexAt, work, address, before[k]);
        }
        return before;
    }

    /**
     * Where flow goes from [i] at [at]: the next instruction first when it can continue, then
     * each branch or case target.
     */
    private static List<Integer> successors(Layout layout, Instruction i, int at) {
        Opcode opcode = i.getOpcode();
        List<Integer> next = new ArrayList<>();
        if (opcode.canContinue()) next.add(at + i.getCodeUnits());
        if (i instanceof OffsetInstruction && opcode != Opcode.FILL_ARRAY_DATA) {
            int target = at + ((OffsetInstruction) i).getCodeOffset();
            if (opcode == Opcode.PACKED_SWITCH || opcode == Opcode.SPARSE_SWITCH) {
                Instruction payload = layout.byAddress.get(target);
                if (payload instanceof SwitchPayload) {
                    for (SwitchElement element : ((SwitchPayload) payload).getSwitchElements()) {
                        next.add(at + element.getOffset());
                    }
                }
            } else {
                next.add(target);
            }
        }
        return next;
    }

    /** The handlers that catch what [i] at [at] may throw. */
    private static List<Integer> handlers(MethodImplementation impl, Instruction i, int at) {
        List<Integer> handlers = new ArrayList<>();
        if (!i.getOpcode().canThrow()) return handlers;
        for (TryBlock<? extends ExceptionHandler> block : impl.getTryBlocks()) {
            int start = block.getStartCodeAddress();
            if (at < start || at >= start + block.getCodeUnitCount()) continue;
            for (ExceptionHandler handler : block.getExceptionHandlers()) {
                handlers.add(handler.getHandlerCodeAddress());
            }
        }
        return handlers;
    }

    private static void flow(char[][] before, Map<Integer, Integer> indexAt, Deque<Integer> work,
            int address, char[] state) {
        Integer successor = indexAt.get(address);
        if (successor == null) return;
        if (before[successor] == null) {
            before[successor] = state.clone();
            work.add(successor);
            return;
        }
        boolean changed = false;
        char[] into = before[successor];
        for (int r = 0; r < into.length; r++) {
            char joined = join(into[r], state[r]);
            if (joined != into[r]) {
                into[r] = joined;
                changed = true;
            }
        }
        if (changed) work.add(successor);
    }

    /**
     * One register's kind where two paths meet, as ART merges it. A zero is a null or a zero, so it
     * takes the other side's object or narrow kind, and a pair broken on one path is broken. Any
     * other two known kinds make C, a conflict: ART lets a move copy one, and fails every other
     * read of it. T stays T, since a register this doesn't know is never judged.
     */
    private static char join(char a, char b) {
        if (a == b) return a;
        if (a == 'T' || b == 'T') return 'T';
        if (a == 'Z' && (b == 'L' || b == 'I')) return b;
        if (b == 'Z' && (a == 'L' || a == 'I')) return a;
        if ((a == 'W' && b == 'B') || (a == 'B' && b == 'W')) return 'B';
        if ((a == 'w' && b == 'b') || (a == 'b' && b == 'w')) return 'b';
        return 'C';
    }

    /** A narrow or object move: ART lets one copy a conflict, which fails only where it's used. */
    private static boolean copiesConflict(Opcode opcode) {
        switch (opcode) {
            case MOVE: case MOVE_FROM16: case MOVE_16:
            case MOVE_OBJECT: case MOVE_OBJECT_FROM16: case MOVE_OBJECT_16:
                return true;
            default:
                return false;
        }
    }

    /** The kinds after one instruction. */
    private static char[] transfer(Instruction i, char[] in) {
        Opcode opcode = i.getOpcode();
        if (!opcode.setsRegister() || !(i instanceof OneRegisterInstruction)) return in;
        char[] out = in.clone();
        int a = ((OneRegisterInstruction) i).getRegisterA();
        // A move copies its source's kind, a conflict included, unless the read is already a
        // finding: then what it wrote is unknown, and the one mistake is reported once rather
        // than at every later use.
        if (opcode.setsWideRegister()) {
            char low = 'W', high = 'w';
            if (opcode == Opcode.MOVE_WIDE || opcode == Opcode.MOVE_WIDE_FROM16 || opcode == Opcode.MOVE_WIDE_16) {
                int b = ((TwoRegisterInstruction) i).getRegisterB();
                boolean wide = b + 1 < in.length && in[b] == 'W' && in[b + 1] == 'w';
                low = wide ? 'W' : 'T';
                high = wide ? 'w' : 'T';
            }
            write(out, a, low);
            write(out, a + 1, high);
            return out;
        }
        char kind;
        switch (opcode) {
            case CONST_4: case CONST_16: case CONST: case CONST_HIGH16:
                kind = ((com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction) i)
                        .getNarrowLiteral() == 0 ? 'Z' : 'I';
                break;
            case MOVE: case MOVE_FROM16: case MOVE_16: {
                int b = ((TwoRegisterInstruction) i).getRegisterB();
                char source = b < in.length ? in[b] : 'T';
                kind = source == 'C' || (source != 'T' && readableAs(source, 'I')) ? source : 'T';
                break;
            }
            case MOVE_OBJECT: case MOVE_OBJECT_FROM16: case MOVE_OBJECT_16: {
                int b = ((TwoRegisterInstruction) i).getRegisterB();
                char source = b < in.length ? in[b] : 'T';
                kind = source == 'C' || (source != 'T' && readableAs(source, 'L')) ? source : 'T';
                break;
            }
            case MOVE_RESULT: case INSTANCE_OF: case ARRAY_LENGTH:
            case IGET: case IGET_BOOLEAN: case IGET_BYTE: case IGET_CHAR: case IGET_SHORT:
            case SGET: case SGET_BOOLEAN: case SGET_BYTE: case SGET_CHAR: case SGET_SHORT:
            case AGET: case AGET_BOOLEAN: case AGET_BYTE: case AGET_CHAR: case AGET_SHORT:
                kind = 'I';
                break;
            case MOVE_RESULT_OBJECT: case MOVE_EXCEPTION: case CONST_STRING: case CONST_STRING_JUMBO:
            case CONST_CLASS: case NEW_INSTANCE: case NEW_ARRAY: case CHECK_CAST:
            case IGET_OBJECT: case SGET_OBJECT: case AGET_OBJECT:
                kind = 'L';
                break;
            default:
                // Arithmetic, compares and conversions that don't set a pair set a narrow value.
                kind = arithmeticOperands(opcode) != null ? 'I' : 'T';
        }
        write(out, a, kind);
        return out;
    }

    /**
     * One register's new kind, and the wide pair it breaks if it was half of one. ART keeps the
     * half that wasn't written as it was and checks the pair where it's read, so the half left
     * behind can't be read as anything: writing over the upper half leaves the lower half as B,
     * and writing over the lower half leaves the upper half as b. A patch that borrows either half
     * of a live long as a free local does this, and so does a wide write that lands one register
     * below a live pair.
     */
    private static void write(char[] out, int register, char kind) {
        // Out of range has no kind to hold; the register check in structuralFindings reports it.
        if (register >= out.length) return;
        if (out[register] == 'W' && register + 1 < out.length && out[register + 1] == 'w' && kind != 'W') {
            out[register + 1] = 'b';
        }
        if (out[register] == 'w' && register > 0 && out[register - 1] == 'W' && kind != 'w') {
            out[register - 1] = 'B';
        }
        out[register] = kind;
    }

    /**
     * Whether a register of this kind can be read as that kind. T is never in doubt here; B, b, w
     * and C can't be read as anything, and W only as the pair it starts. E is what a test against
     * zero or an equality test takes: a narrow value or an object.
     */
    private static boolean readableAs(char have, char want) {
        if (have == 'T') return true;
        if (have == 'B') return false;
        switch (want) {
            case 'W': return have == 'W';
            case 'I': return have == 'I' || have == 'Z';
            case 'L': return have == 'L' || have == 'Z';
            case 'E': return have == 'I' || have == 'Z' || have == 'L';
            default: return true;
        }
    }

    private static final java.util.regex.Pattern WIDE_PAIR = java.util.regex.Pattern.compile(
            "(add|sub|mul|div|rem|and|or|xor)-(long|double)(/2addr)?|cmp-long|cmp[lg]-double");
    private static final java.util.regex.Pattern WIDE_SHIFT = java.util.regex.Pattern.compile(
            "(shl|shr|ushr)-long(/2addr)?");
    private static final java.util.regex.Pattern NARROW_PAIR = java.util.regex.Pattern.compile(
            "(add|sub|mul|div|rem|and|or|xor|shl|shr|ushr)-(int|float)(/2addr)?|cmp[lg]-float");
    private static final java.util.regex.Pattern NARROW_LITERAL = java.util.regex.Pattern.compile(
            "(add|rsub|mul|div|rem|and|or|xor|shl|shr|ushr)-int/lit(8|16)|rsub-int");
    private static final java.util.regex.Pattern WIDE_SINGLE = java.util.regex.Pattern.compile(
            "(neg|not)-(long|double)|(long|double)-to-(int|long|float|double)");
    private static final java.util.regex.Pattern NARROW_SINGLE = java.util.regex.Pattern.compile(
            "(neg|not)-(int|float)|(int|float)-to-(int|long|float|double|byte|char|short)");

    /** The kinds an arithmetic, compare or conversion instruction reads its operands as, or null. */
    private static char[] arithmeticOperands(Opcode opcode) {
        String name = opcode.name;
        if (WIDE_PAIR.matcher(name).matches()) return new char[]{'W', 'W'};
        if (WIDE_SHIFT.matcher(name).matches()) return new char[]{'W', 'I'};
        if (NARROW_PAIR.matcher(name).matches()) return new char[]{'I', 'I'};
        if (NARROW_LITERAL.matcher(name).matches() || NARROW_SINGLE.matcher(name).matches()) return new char[]{'I'};
        if (WIDE_SINGLE.matcher(name).matches()) return new char[]{'W'};
        return null;
    }

    private static void read(List<int[]> reads, int register, char kind) {
        reads.add(new int[]{register, kind});
    }

    /** Every register an instruction reads as a value, with the kind it reads it as. */
    private static List<int[]> valueReads(Instruction i) {
        List<int[]> reads = new ArrayList<>();
        Opcode opcode = i.getOpcode();
        switch (opcode) {
            case MOVE: case MOVE_FROM16: case MOVE_16:
                read(reads, ((TwoRegisterInstruction) i).getRegisterB(), 'I');
                return reads;
            case MOVE_WIDE: case MOVE_WIDE_FROM16: case MOVE_WIDE_16:
                read(reads, ((TwoRegisterInstruction) i).getRegisterB(), 'W');
                return reads;
            case MOVE_OBJECT: case MOVE_OBJECT_FROM16: case MOVE_OBJECT_16:
                read(reads, ((TwoRegisterInstruction) i).getRegisterB(), 'L');
                return reads;
            case RETURN:
            case SPUT: case SPUT_BOOLEAN: case SPUT_BYTE: case SPUT_CHAR: case SPUT_SHORT:
                read(reads, ((OneRegisterInstruction) i).getRegisterA(), 'I');
                return reads;
            case RETURN_WIDE: case SPUT_WIDE:
                read(reads, ((OneRegisterInstruction) i).getRegisterA(), 'W');
                return reads;
            case RETURN_OBJECT: case SPUT_OBJECT:
                read(reads, ((OneRegisterInstruction) i).getRegisterA(), 'L');
                return reads;
            case IPUT: case IPUT_BOOLEAN: case IPUT_BYTE: case IPUT_CHAR: case IPUT_SHORT:
                read(reads, ((TwoRegisterInstruction) i).getRegisterA(), 'I');
                read(reads, ((TwoRegisterInstruction) i).getRegisterB(), 'L');
                return reads;
            case IPUT_WIDE:
                read(reads, ((TwoRegisterInstruction) i).getRegisterA(), 'W');
                read(reads, ((TwoRegisterInstruction) i).getRegisterB(), 'L');
                return reads;
            case IPUT_OBJECT:
                read(reads, ((TwoRegisterInstruction) i).getRegisterA(), 'L');
                read(reads, ((TwoRegisterInstruction) i).getRegisterB(), 'L');
                return reads;
            case IGET: case IGET_BOOLEAN: case IGET_BYTE: case IGET_CHAR: case IGET_SHORT:
            case IGET_WIDE: case IGET_OBJECT:
                read(reads, ((TwoRegisterInstruction) i).getRegisterB(), 'L');
                return reads;
            case APUT: case APUT_BOOLEAN: case APUT_BYTE: case APUT_CHAR: case APUT_SHORT:
            case APUT_WIDE: case APUT_OBJECT: {
                ThreeRegisterInstruction t = (ThreeRegisterInstruction) i;
                char value = opcode == Opcode.APUT_WIDE ? 'W' : opcode == Opcode.APUT_OBJECT ? 'L' : 'I';
                read(reads, t.getRegisterA(), value);
                read(reads, t.getRegisterB(), 'L');
                read(reads, t.getRegisterC(), 'I');
                return reads;
            }
            case AGET: case AGET_BOOLEAN: case AGET_BYTE: case AGET_CHAR: case AGET_SHORT:
            case AGET_WIDE: case AGET_OBJECT: {
                ThreeRegisterInstruction t = (ThreeRegisterInstruction) i;
                read(reads, t.getRegisterB(), 'L');
                read(reads, t.getRegisterC(), 'I');
                return reads;
            }
            // Each register as ART's verifier checks it. A test against zero or for equality takes
            // a narrow value or an object, an ordering, a switch or a new array's size takes a
            // narrow value, and a lock, a throw, a cast, a type test, or an array to measure or
            // fill takes an object. An equality test's two registers also have to agree, which
            // structuralFindings checks as a pair.
            case IF_EQZ: case IF_NEZ:
                read(reads, ((OneRegisterInstruction) i).getRegisterA(), 'E');
                return reads;
            case IF_EQ: case IF_NE:
                read(reads, ((TwoRegisterInstruction) i).getRegisterA(), 'E');
                read(reads, ((TwoRegisterInstruction) i).getRegisterB(), 'E');
                return reads;
            case IF_LTZ: case IF_GEZ: case IF_GTZ: case IF_LEZ:
            case PACKED_SWITCH: case SPARSE_SWITCH:
                read(reads, ((OneRegisterInstruction) i).getRegisterA(), 'I');
                return reads;
            case IF_LT: case IF_GE: case IF_GT: case IF_LE:
                read(reads, ((TwoRegisterInstruction) i).getRegisterA(), 'I');
                read(reads, ((TwoRegisterInstruction) i).getRegisterB(), 'I');
                return reads;
            case MONITOR_ENTER: case MONITOR_EXIT: case THROW: case CHECK_CAST: case FILL_ARRAY_DATA:
                read(reads, ((OneRegisterInstruction) i).getRegisterA(), 'L');
                return reads;
            case INSTANCE_OF: case ARRAY_LENGTH:
                read(reads, ((TwoRegisterInstruction) i).getRegisterB(), 'L');
                return reads;
            case NEW_ARRAY:
                read(reads, ((TwoRegisterInstruction) i).getRegisterB(), 'I');
                return reads;
            case FILLED_NEW_ARRAY: case FILLED_NEW_ARRAY_RANGE: {
                // Each element as the array holds it: an object, or a narrow value.
                Reference type = ((ReferenceInstruction) i).getReference();
                if (!(type instanceof TypeReference)) return reads;
                char element = kindOf(((TypeReference) type).getType().substring(1));
                for (int register : invokeRegisters(i)) read(reads, register, element);
                return reads;
            }
            default:
                break;
        }
        char[] operands = arithmeticOperands(opcode);
        if (operands == null) return reads;
        int[] registers;
        if (i instanceof ThreeRegisterInstruction) {
            registers = new int[]{((ThreeRegisterInstruction) i).getRegisterB(), ((ThreeRegisterInstruction) i).getRegisterC()};
        } else if (i instanceof TwoRegisterInstruction && opcode.name.endsWith("/2addr")) {
            registers = new int[]{((TwoRegisterInstruction) i).getRegisterA(), ((TwoRegisterInstruction) i).getRegisterB()};
        } else if (i instanceof TwoRegisterInstruction) {
            registers = new int[]{((TwoRegisterInstruction) i).getRegisterB()};
        } else {
            return reads;
        }
        for (int k = 0; k < Math.min(operands.length, registers.length); k++) read(reads, registers[k], operands[k]);
        return reads;
    }

    /**
     * Each return-object in [m] that hands back, unchanged, what a call declared to return Object
     * answered, while [m] is declared to return some other type: the declared type, and the
     * finding. ART rejects the whole class for that ("returning 'Reference: java.lang.Object', but
     * expected from declaration ..."), so every method in it dies with a VerifyError, which is how
     * Comment sheet options crashed every comment sheet in 0.16.0 (#203, #204). The extension's
     * hooks take and answer Object, so a hook whose answer is returned has to be cast back first.
     * The walk goes back from the return along the one straight run into it and stops where
     * another path joins, so a value only some paths bring is left alone. Whether the declared
     * type is an interface, which ART lets any object through to, is only known once every class
     * has been read, so the caller decides which of these stand.
     */
    private static List<String[]> objectReturns(Method m) {
        List<String[]> out = new ArrayList<>();
        MethodImplementation impl = m.getImplementation();
        String declared = m.getReturnType();
        if (impl == null || declared.charAt(0) != 'L' || declared.equals("Ljava/lang/Object;")) return out;
        Layout layout = new Layout(impl);
        Set<Integer> joins = joins(impl, layout);
        for (int k = 0; k < layout.instructions.size(); k++) {
            Instruction i = layout.instructions.get(k);
            if (i.getOpcode() != Opcode.RETURN_OBJECT) continue;
            int register = ((OneRegisterInstruction) i).getRegisterA();
            int j = k;
            while (j > 0 && !joins.contains(layout.addresses.get(j))
                    && layout.instructions.get(j - 1).getOpcode().canContinue()) {
                j--;
                Instruction w = layout.instructions.get(j);
                if (!writes(w, register)) continue;
                if (w.getOpcode() == Opcode.MOVE_RESULT_OBJECT && j > 0
                        && layout.instructions.get(j - 1) instanceof ReferenceInstruction) {
                    Reference callee = ((ReferenceInstruction) layout.instructions.get(j - 1)).getReference();
                    if (callee instanceof MethodReference
                            && ((MethodReference) callee).getReturnType().equals("Ljava/lang/Object;")) {
                        out.add(new String[]{declared, "type: return-object at " + layout.addresses.get(k)
                                + " returns v" + register + ", the Object " + callee + " answered, uncast, where the method returns "
                                + declared});
                    }
                }
                break;
            }
        }
        return out;
    }

    /** Whether [i] writes [register]: as its destination, the upper half of a wide one, or a cast. */
    private static boolean writes(Instruction i, int register) {
        Opcode opcode = i.getOpcode();
        if (!(i instanceof OneRegisterInstruction)) return false;
        if (opcode != Opcode.CHECK_CAST && !opcode.setsRegister()) return false;
        int destination = ((OneRegisterInstruction) i).getRegisterA();
        return destination == register || (opcode.setsWideRegister() && destination + 1 == register);
    }

    /** Every address a branch, a switch case or an exception handler lands on. */
    private static Set<Integer> joins(MethodImplementation impl, Layout layout) {
        Set<Integer> out = new HashSet<>();
        for (int k = 0; k < layout.instructions.size(); k++) {
            Instruction i = layout.instructions.get(k);
            if (!(i instanceof OffsetInstruction) || i.getOpcode() == Opcode.FILL_ARRAY_DATA) continue;
            int at = layout.addresses.get(k);
            int target = at + ((OffsetInstruction) i).getCodeOffset();
            if (i.getOpcode() == Opcode.PACKED_SWITCH || i.getOpcode() == Opcode.SPARSE_SWITCH) {
                Instruction payload = layout.byAddress.get(target);
                if (!(payload instanceof SwitchPayload)) continue;
                for (SwitchElement element : ((SwitchPayload) payload).getSwitchElements()) out.add(at + element.getOffset());
            } else {
                out.add(target);
            }
        }
        for (TryBlock<? extends ExceptionHandler> block : impl.getTryBlocks()) {
            for (ExceptionHandler handler : block.getExceptionHandlers()) out.add(handler.getHandlerCodeAddress());
        }
        return out;
    }

    /**
     * Whether ART lets any object through to [type]: an interface the APK defines, or one the
     * desktop runtime knows (java.util.List and the like). A type neither knows, such as one of
     * Android's own, counts as a class, so a hook returning into it casts first.
     */
    private static boolean takesAnyObject(String type, Set<String> interfaces) {
        if (interfaces.contains(type)) return true;
        if (!type.startsWith("Ljava/")) return false;
        try {
            return Class.forName(type.substring(1, type.length() - 1).replace('/', '.'), false, null).isInterface();
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Each object an instruction in [m] hands on to something that names its class: an invoke's
     * receiver and arguments, the object an instance field is read from or written to, and the
     * value an iput-object or sput-object stores. Each comes as the class wanted, the class the
     * register holds, the finding, and for a receiver or a field's object the member (see
     * memberKey), else null. ART refuses the whole class when the held class can't be
     * the wanted one ("argument type mismatch"), which is how the comment faces hook crashed
     * Facebook 585 at launch: the register it passed as the component had been reused for one of
     * the factory's arguments. Whether the held class can be the wanted one is only known once
     * every class has been read, so the caller decides which of these stand (see refuses). An
     * invoke-interface's receiver is left alone, as ART lets any object through to one.
     * [superclasses] and [interfaces] are the whole APK's, read before any method, which the
     * classes where two paths meet are worked out from (see joinTypes).
     */
    private static List<String[]> typeChecks(ClassDef cd, Method m, Map<String, String> superclasses,
            Set<String> interfaces) {
        List<String[]> out = new ArrayList<>();
        MethodImplementation impl = m.getImplementation();
        if (impl == null) return out;
        Layout layout = new Layout(impl);
        boolean isStatic = AccessFlags.STATIC.isSet(m.getAccessFlags());
        int ins = isStatic ? 0 : 1;
        for (CharSequence p : m.getParameterTypes()) ins += slots(p);
        int register = impl.getRegisterCount() - ins;
        // Too few registers for the parameters is already a parameter finding.
        if (register < 0) return out;
        Map<Integer, String> parameterType = new HashMap<>();
        if (!isStatic) parameterType.put(register++, cd.getType());
        for (CharSequence p : m.getParameterTypes()) {
            if (kindOf(p) == 'L') parameterType.put(register, p.toString());
            register += slots(p);
        }
        String[][] types = registerTypes(impl, layout, parameterType, superclasses, interfaces);
        for (int k = 0; k < layout.instructions.size(); k++) {
            Instruction i = layout.instructions.get(k);
            if (types[k] == null || !(i instanceof ReferenceInstruction)) continue;
            Opcode opcode = i.getOpcode();
            Reference r = ((ReferenceInstruction) i).getReference();
            String where = opcode.name + " at " + layout.addresses.get(k);
            if (isPlainInvoke(opcode) && r instanceof MethodReference) {
                MethodReference callee = (MethodReference) r;
                List<String> wanted = new ArrayList<>();
                if (!isStaticInvoke(opcode)) {
                    boolean anyObject = opcode == Opcode.INVOKE_INTERFACE || opcode == Opcode.INVOKE_INTERFACE_RANGE;
                    wanted.add(anyObject ? null : callee.getDefiningClass());
                }
                for (CharSequence p : callee.getParameterTypes()) {
                    wanted.add(kindOf(p) == 'L' ? p.toString() : null);
                    if (slots(p) == 2) wanted.add(null);
                }
                int[] regs = invokeRegisters(i);
                // A count that doesn't match is already an invoke finding.
                if (regs.length != wanted.size()) continue;
                for (int a = 0; a < regs.length; a++) {
                    String have = typeOf(types[k], regs[a]);
                    if (have == null || wanted.get(a) == null || have.equals(wanted.get(a))) continue;
                    out.add(new String[]{wanted.get(a), have, "type: " + where + " passes v" + regs[a] + ", which holds "
                            + have + ", where " + callee + " takes " + wanted.get(a),
                            a == 0 && !isStaticInvoke(opcode) ? memberKey(callee) : null});
                }
            } else if (r instanceof FieldReference) {
                FieldReference field = (FieldReference) r;
                switch (opcode) {
                    case IGET: case IGET_WIDE: case IGET_OBJECT: case IGET_BOOLEAN: case IGET_BYTE:
                    case IGET_CHAR: case IGET_SHORT: case IPUT: case IPUT_WIDE: case IPUT_OBJECT:
                    case IPUT_BOOLEAN: case IPUT_BYTE: case IPUT_CHAR: case IPUT_SHORT: {
                        int object = ((TwoRegisterInstruction) i).getRegisterB();
                        String have = typeOf(types[k], object);
                        if (have != null && !have.equals(field.getDefiningClass())) {
                            out.add(new String[]{field.getDefiningClass(), have, "type: " + where + " uses " + field
                                    + " on v" + object + ", which holds " + have, memberKey(field)});
                        }
                        // An iput-object also stores a value, which falls through to the
                        // sput-object check.
                        if (opcode != Opcode.IPUT_OBJECT) break;
                    }
                    case SPUT_OBJECT: {
                        int value = ((OneRegisterInstruction) i).getRegisterA();
                        String have = typeOf(types[k], value);
                        if (have != null && field.getType().charAt(0) == 'L' && !have.equals(field.getType())) {
                            out.add(new String[]{field.getType(), have, "type: " + where + " stores v" + value
                                    + ", which holds " + have + ", in " + field, null});
                        }
                        break;
                    }
                    default:
                        break;
                }
            }
        }
        return out;
    }

    private static String typeOf(String[] types, int register) {
        return register < types.length ? types[register] : null;
    }

    /**
     * The class each register holds before each instruction, along every path from the entry,
     * as a type descriptor; null where that isn't known (a narrow or wide value, a null, or paths
     * that disagree), and a null row where no path reaches the instruction. The parameters and
     * this start with their declared classes, what makes an object gives its class, a move copies
     * it, and anything else leaves the register unknown. Where two paths meet with different
     * classes, the register holds the class they share, as ART merges them (see joinTypes), and
     * stays unknown wherever that can't be told, so nothing unsure is judged. ART doesn't narrow
     * a register after an if-eqz: a null-checked walk through one register (load the holder,
     * branch past the next load when it's null, load the view from it) meets as the holder's and
     * the view's shared class, Object, and handing that on as a View rejected the scroll-away
     * controller on 588 at launch (2026-11-22). An instance-of tested right below leaves the
     * tested register unknown on the branch where it's an instance (see narrowed). An exception
     * edge carries the classes from before the throwing instruction, as in registerKinds.
     */
    private static String[][] registerTypes(MethodImplementation impl, Layout layout,
            Map<Integer, String> parameterType, Map<String, String> superclasses, Set<String> interfaces) {
        int count = layout.instructions.size();
        String[][] before = new String[count][];
        if (count == 0) return before;
        Map<Integer, Integer> indexAt = new HashMap<>();
        for (int k = 0; k < count; k++) indexAt.put(layout.addresses.get(k), k);
        Set<Integer> joins = joins(impl, layout);
        String[] entry = new String[impl.getRegisterCount()];
        for (Map.Entry<Integer, String> p : parameterType.entrySet()) {
            if (p.getKey() < entry.length) entry[p.getKey()] = p.getValue();
        }
        Deque<Integer> work = new ArrayDeque<>();
        before[0] = entry;
        work.add(0);
        while (!work.isEmpty()) {
            int k = work.poll();
            Instruction i = layout.instructions.get(k);
            int at = layout.addresses.get(k);
            String[] after = typeTransfer(i, k == 0 ? null : layout.instructions.get(k - 1), before[k]);
            String[] instance = narrowed(layout, joins, k, after);
            List<Integer> next = successors(layout, i, at);
            for (int s = 0; s < next.size(); s++) {
                // An if-eqz falls through where its instance-of said yes, and an if-nez branches there.
                boolean instanceEdge = instance != null && (i.getOpcode() == Opcode.IF_EQZ ? s == 0 : s == 1);
                flowTypes(before, indexAt, work, next.get(s), instanceEdge ? instance : after, superclasses, interfaces);
            }
            for (int address : handlers(impl, i, at)) {
                flowTypes(before, indexAt, work, address, before[k], superclasses, interfaces);
            }
        }
        return before;
    }

    private static void flowTypes(String[][] before, Map<Integer, Integer> indexAt, Deque<Integer> work,
            int address, String[] state, Map<String, String> superclasses, Set<String> interfaces) {
        Integer successor = indexAt.get(address);
        if (successor == null) return;
        if (before[successor] == null) {
            before[successor] = state.clone();
            work.add(successor);
            return;
        }
        boolean changed = false;
        String[] into = before[successor];
        for (int r = 0; r < into.length; r++) {
            if (into[r] == null || into[r].equals(state[r])) continue;
            // Only a join that moves the register counts: one that lands where it already was (Base
            // joined with a class under Base) would queue the successor again for nothing, and two
            // such joins in nested loops queue each other forever.
            String joined = joinTypes(into[r], state[r], superclasses, interfaces);
            if (into[r].equals(joined)) continue;
            into[r] = joined;
            changed = true;
        }
        if (changed) work.add(successor);
    }

    /**
     * The class two paths' classes meet as: the nearest class both extend, through the APK's
     * superclasses, as ART merges them. Null, which leaves the register unknown, when either side
     * is unknown, an array or an interface, or when what they share lies among classes whose
     * superclasses aren't known here. A platform class never extends one of the APK's, so when
     * one side's chain reaches Object through the APK's own classes and the other's runs into a
     * platform class without meeting it, they meet at Object. A chain that runs into a class
     * neither the APK nor the platform defines, which Facebook loads later from its own stores,
     * leaves the register unknown, since ART can't merge it before then either. The flow requeues a
     * block only when a join moves a register, and each move goes up its chain or to unknown, so
     * it settles.
     */
    private static String joinTypes(String a, String b, Map<String, String> superclasses, Set<String> interfaces) {
        if (a == null || b == null) return null;
        if (a.equals(b)) return a;
        if (a.startsWith("[") || b.startsWith("[") || takesAnyObject(a, interfaces) || takesAnyObject(b, interfaces)) {
            return null;
        }
        List<String> left = superchain(a, superclasses);
        List<String> right = superchain(b, superclasses);
        Set<String> shared = new HashSet<>(right);
        for (String type : left) if (shared.contains(type)) return type;
        String leftEnd = left.get(left.size() - 1);
        String rightEnd = right.get(right.size() - 1);
        if (leftEnd.equals(OBJECT) && isPlatform(rightEnd)) return OBJECT;
        if (rightEnd.equals(OBJECT) && isPlatform(leftEnd)) return OBJECT;
        return null;
    }

    /** [type] and its superclasses in order, ending at the first class the APK doesn't define. */
    private static List<String> superchain(String type, Map<String, String> superclasses) {
        List<String> chain = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String at = type;
        while (at != null && seen.add(at)) {
            chain.add(at);
            if (!superclasses.containsKey(at)) break;
            at = superclasses.get(at);
        }
        return chain;
    }

    /** The classes after one instruction. [previous] is the one above it, whose answer a move-result takes. */
    private static String[] typeTransfer(Instruction i, Instruction previous, String[] in) {
        Opcode opcode = i.getOpcode();
        if (!(i instanceof OneRegisterInstruction) || (!opcode.setsRegister() && opcode != Opcode.CHECK_CAST)) return in;
        String[] out = in.clone();
        int a = ((OneRegisterInstruction) i).getRegisterA();
        String type = null;
        switch (opcode) {
            case MOVE_OBJECT: case MOVE_OBJECT_FROM16: case MOVE_OBJECT_16:
                type = typeOf(in, ((TwoRegisterInstruction) i).getRegisterB());
                break;
            case MOVE_RESULT_OBJECT:
                if (previous instanceof ReferenceInstruction) {
                    Reference r = ((ReferenceInstruction) previous).getReference();
                    if (isPlainInvoke(previous.getOpcode()) && r instanceof MethodReference) {
                        type = ((MethodReference) r).getReturnType();
                    } else if ((previous.getOpcode() == Opcode.FILLED_NEW_ARRAY
                            || previous.getOpcode() == Opcode.FILLED_NEW_ARRAY_RANGE) && r instanceof TypeReference) {
                        type = ((TypeReference) r).getType();
                    }
                }
                break;
            case CONST_STRING: case CONST_STRING_JUMBO:
                type = "Ljava/lang/String;";
                break;
            case CONST_CLASS:
                type = "Ljava/lang/Class;";
                break;
            case NEW_INSTANCE: case NEW_ARRAY: case CHECK_CAST:
                type = ((TypeReference) ((ReferenceInstruction) i).getReference()).getType();
                break;
            case IGET_OBJECT: case SGET_OBJECT:
                type = ((FieldReference) ((ReferenceInstruction) i).getReference()).getType();
                break;
            case AGET_OBJECT: {
                String array = typeOf(in, ((ThreeRegisterInstruction) i).getRegisterB());
                type = array != null && array.startsWith("[") ? array.substring(1) : null;
                break;
            }
            default:
                break;
        }
        if (a < out.length) out[a] = type;
        if (opcode.setsWideRegister() && a + 1 < out.length) out[a + 1] = null;
        return out;
    }

    /**
     * The classes on the instance branch of an if-eqz or if-nez at [k], or null. ART can narrow
     * the register an instance-of right above the if tested, when nothing else branches to the if
     * and the instance-of didn't write over what it tested, and the register a move-object right
     * above the instance-of copied it from. R8 can drop the check-cast that would follow, so
     * without this a stock method reads as a finding. ART narrows only to a class below the one
     * the register held and keeps that one otherwise (an interface, a superclass), which the
     * flow can't tell apart before every class is read, so both registers go unknown there.
     */
    private static String[] narrowed(Layout layout, Set<Integer> joins, int k, String[] after) {
        Instruction i = layout.instructions.get(k);
        Opcode opcode = i.getOpcode();
        if ((opcode != Opcode.IF_EQZ && opcode != Opcode.IF_NEZ) || k == 0 || joins.contains(layout.addresses.get(k))) {
            return null;
        }
        Instruction test = layout.instructions.get(k - 1);
        if (test.getOpcode() != Opcode.INSTANCE_OF) return null;
        int result = ((TwoRegisterInstruction) test).getRegisterA();
        int tested = ((TwoRegisterInstruction) test).getRegisterB();
        if (result != ((OneRegisterInstruction) i).getRegisterA() || result == tested || tested >= after.length) return null;
        String[] out = after.clone();
        out[tested] = null;
        if (k > 1 && !joins.contains(layout.addresses.get(k - 1))) {
            Instruction move = layout.instructions.get(k - 2);
            Opcode moved = move.getOpcode();
            if ((moved == Opcode.MOVE_OBJECT || moved == Opcode.MOVE_OBJECT_FROM16 || moved == Opcode.MOVE_OBJECT_16)
                    && ((TwoRegisterInstruction) move).getRegisterA() == tested) {
                int source = ((TwoRegisterInstruction) move).getRegisterB();
                if (source < out.length) out[source] = null;
            }
        }
        return out;
    }

    /**
     * Whether ART refuses an object of class [have] where [wanted] is asked for. Only a class the
     * APK defines is judged as [wanted], and only when [have] can be followed through its
     * superclasses to the platform's: an array or a platform class (java.lang.Object answered by
     * a hook, a Drawable, a Context) can never be one of the APK's classes, and an APK class can't
     * when none of its superclasses is. An interface on either side is left alone, and so is a
     * class neither the APK nor the platform defines, which Facebook loads later from its own
     * stores, since what it extends isn't known here.
     *
     * <p>A platform class one of the APK's classes extends, so a class and not an interface (a
     * View, a Drawable), is judged too, against a held class whose superclasses are known all
     * the way to Object: the Object two paths meet as (see joinTypes), or an APK class built
     * straight on Object. Nothing below Object is a View, which is how the scroll-away hook's
     * View parameter rejected its controller on 588 (2026-11-22). A held platform class is left
     * alone there, since its own superclasses aren't known.
     */
    private static boolean refuses(String have, String wanted, Map<String, String> superclasses, Set<String> interfaces,
            Set<String> platformClasses) {
        if (have.equals(wanted) || interfaces.contains(wanted) || interfaces.contains(have)) return false;
        if (!superclasses.containsKey(wanted)) {
            if (!platformClasses.contains(wanted)) return false;
            List<String> chain = superchain(have, superclasses);
            return !chain.contains(wanted) && chain.get(chain.size() - 1).equals(OBJECT);
        }
        if (have.startsWith("[")) return true;
        String type = have;
        while (superclasses.containsKey(type)) {
            if (type.equals(wanted)) return false;
            type = superclasses.get(type);
            if (type == null) return false;
        }
        return isPlatform(type);
    }

    /** A method or field as its name and signature, the same for a reference and a definition. */
    private static String memberKey(Reference r) {
        if (r instanceof MethodReference) {
            MethodReference method = (MethodReference) r;
            return method.getName() + "(" + String.join("", method.getParameterTypes()) + ")" + method.getReturnType();
        }
        FieldReference field = (FieldReference) r;
        return field.getName() + ":" + field.getType();
    }

    /**
     * The classes from [wanted] up its superclasses to [have], [have] left out, or null when
     * [have] isn't one of [wanted]'s superclasses.
     */
    private static List<String> classesBelow(String have, String wanted, Map<String, String> superclasses) {
        List<String> below = new ArrayList<>();
        String type = wanted;
        while (type != null && !type.equals(have)) {
            if (!superclasses.containsKey(type)) return null;
            below.add(type);
            type = superclasses.get(type);
        }
        return type == null ? null : below;
    }

    /**
     * The members each class declares, read only for the classes that decide a refused receiver
     * or field's object (see declaredAbove), from the same classesN.dex entry its superclass came
     * from. Most runs have none, and then the APK isn't read again.
     */
    private static Map<String, Set<String>> declaredMembers(MultiDexContainer<? extends DexFile> container,
            List<String[]> refused, Map<String, String> superclasses, Map<String, Integer> superclassDex) throws Exception {
        Map<String, Set<String>> declared = new HashMap<>();
        for (String[] found : refused) {
            List<String> below = found[4] == null ? null : classesBelow(found[2], found[1], superclasses);
            if (below != null) for (String type : below) declared.put(type, new HashSet<>());
        }
        if (declared.isEmpty()) return declared;
        for (String entry : container.getDexEntryNames()) {
            int dexNumber = appDexNumber(entry);
            if (dexNumber == 0) continue;
            for (ClassDef cd : container.getEntry(entry).getDexFile().getClasses()) {
                Set<String> members = declared.get(cd.getType());
                if (members == null || !Integer.valueOf(dexNumber).equals(superclassDex.get(cd.getType()))) continue;
                for (Method m : cd.getMethods()) members.add(memberKey(m));
                for (Field f : cd.getFields()) members.add(memberKey(f));
            }
        }
        return declared;
    }

    /**
     * Whether a refused receiver or field's object is one ART takes after all: the register holds
     * one of the named class's superclasses, and no class between the two declares the member, so
     * it resolves to one at or above the held class, which ART checks the object against. Javac
     * and R8 name the declaring class, but hand-written smali can name a subclass.
     */
    private static boolean declaredAbove(String[] found, Map<String, String> superclasses,
            Map<String, Set<String>> declared) {
        if (found[4] == null) return false;
        List<String> below = classesBelow(found[2], found[1], superclasses);
        if (below == null) return false;
        for (String type : below) {
            Set<String> members = declared.get(type);
            if (members == null || members.contains(found[4])) return false;
        }
        return true;
    }

    /** classes.dex as 1, classesN.dex as N, and any other dex in the APK as 0. */
    private static int appDexNumber(String entry) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("classes(\\d*)\\.dex").matcher(entry);
        if (!m.matches()) return 0;
        return m.group(1).isEmpty() ? 1 : Integer.parseInt(m.group(1));
    }

    private static boolean isPlatform(String type) {
        if (type.startsWith("Landroid/support/")) return false;
        return type.startsWith("Ljava/") || type.startsWith("Ljavax/") || type.startsWith("Landroid/")
                || type.startsWith("Ldalvik/");
    }

    /**
     * The structural findings for one method, each as "category: detail". The parameter check
     * reads a register only where no path from the entry has written it, where the value can
     * only be the argument the method was called with.
     */
    private static List<String> structuralFindings(ClassDef cd, Method m) {
        List<String> findings = new ArrayList<>();
        MethodImplementation impl = m.getImplementation();
        if (impl == null) return findings;
        Layout layout = new Layout(impl);

        // Registers: every one an instruction names, and the upper half of each wide value it
        // writes or reads, has to be below the count the method declares. ART checks that for each
        // instruction before it follows any path, so one out of range fails the class wherever it
        // sits, reachable or not. The kind checks further down have no kind to give such a
        // register and step over it, which is why it's a finding here, once for each instruction.
        int registerCount = impl.getRegisterCount();
        for (int k = 0; k < layout.instructions.size(); k++) {
            Instruction i = layout.instructions.get(k);
            int reach = reach(i);
            if (reach >= registerCount) {
                findings.add("register: " + i.getOpcode().name + " at " + layout.addresses.get(k) + " reaches v" + reach
                        + ", and the method declares " + registerCount + (registerCount == 1 ? " register" : " registers"));
            }
        }

        // A move-result takes the result of the instruction right before it, an invoke or a
        // filled-new-array, so an instruction injected between the two leaves nothing to take. So
        // does a call that never made it into the dex: Morphe's inline compiler drops a plain
        // invoke naming a register past v15 (a parameter of a method with more than 16 registers)
        // without a word and keeps the move-result after it, which is how Hide tab badges reached
        // 581's Htc badge writer as a lone move-result first in the method.
        for (int k = 0; k < layout.instructions.size(); k++) {
            if (!isMoveResult(layout.instructions.get(k).getOpcode())) continue;
            if (k == 0 || !layout.instructions.get(k - 1).getOpcode().setsResult()) {
                findings.add("result: " + layout.instructions.get(k).getOpcode().name + " at "
                        + layout.addresses.get(k) + " does not follow an invoke or filled-new-array"
                        + (k == 0 ? "; it opens the method" : ", it follows " + layout.instructions.get(k - 1).getOpcode().name));
            }
        }

        // Branches and switch cases.
        for (int k = 0; k < layout.instructions.size(); k++) {
            Instruction i = layout.instructions.get(k);
            if (!(i instanceof OffsetInstruction)) continue;
            int at = layout.addresses.get(k);
            int target = at + ((OffsetInstruction) i).getCodeOffset();
            Opcode opcode = i.getOpcode();
            if (target == at && opcode != Opcode.GOTO_32) {
                findings.add("branch: " + opcode.name + " at " + at + " branches to itself");
                continue;
            }
            if (!layout.isStart(target)) {
                findings.add("branch: " + opcode.name + " at " + at + " targets " + target
                        + ", which is not the start of an instruction");
                continue;
            }
            Instruction payload = layout.byAddress.get(target);
            if (opcode == Opcode.PACKED_SWITCH || opcode == Opcode.SPARSE_SWITCH) {
                if (!(payload instanceof SwitchPayload)) {
                    findings.add("branch: " + opcode.name + " at " + at + " points at " + target
                            + ", which is not a switch payload");
                    continue;
                }
                // Each switch reads its own kind of table, and ART checks the table's signature
                // against the switch ("wrong signature for switch table").
                Opcode wanted = opcode == Opcode.PACKED_SWITCH ? Opcode.PACKED_SWITCH_PAYLOAD : Opcode.SPARSE_SWITCH_PAYLOAD;
                if (payload.getOpcode() != wanted) {
                    findings.add("branch: " + opcode.name + " at " + at + " points at " + target + ", a "
                            + payload.getOpcode().name + " rather than the " + wanted.name + " it reads");
                    continue;
                }
                for (SwitchElement element : ((SwitchPayload) payload).getSwitchElements()) {
                    int caseTarget = at + element.getOffset();
                    if (!layout.isStart(caseTarget)) {
                        findings.add("branch: " + opcode.name + " at " + at + " sends case "
                                + element.getKey() + " to " + caseTarget
                                + ", which is not the start of an instruction");
                        continue;
                    }
                    String bad = badLanding(layout.byAddress.get(caseTarget));
                    if (bad != null) {
                        findings.add("branch: " + opcode.name + " at " + at + " sends case " + element.getKey()
                                + " to " + caseTarget + ", " + bad + ", which a branch may not land on");
                    }
                }
            } else if (opcode == Opcode.FILL_ARRAY_DATA) {
                if (!(payload instanceof ArrayPayload)) {
                    findings.add("branch: fill-array-data at " + at + " points at " + target
                            + ", which is not an array payload");
                }
            } else {
                String bad = badLanding(payload);
                if (bad != null) {
                    findings.add("branch: " + opcode.name + " at " + at + " targets " + target + ", " + bad
                            + ", which a branch may not land on");
                }
            }
        }

        // Try ranges and handlers.
        for (TryBlock<? extends ExceptionHandler> block : impl.getTryBlocks()) {
            int start = block.getStartCodeAddress();
            int end = start + block.getCodeUnitCount();
            if (block.getCodeUnitCount() <= 0) {
                findings.add("try: a try range at " + start + " covers nothing");
            }
            if (!layout.isStart(start)) {
                findings.add("try: a try range starts at " + start + ", which is not the start of an instruction");
            }
            if (end > layout.size || (end != layout.size && !layout.isStart(end))) {
                findings.add("try: a try range ends at " + end + ", which is not an instruction boundary");
            }
            for (ExceptionHandler handler : block.getExceptionHandlers()) {
                int address = handler.getHandlerCodeAddress();
                if (!layout.isStart(address)) {
                    findings.add("try: a handler for " + handler.getExceptionType() + " is at "
                            + address + ", which is not the start of an instruction");
                } else if (isMoveResult(layout.byAddress.get(address).getOpcode())) {
                    findings.add("try: a handler for " + handler.getExceptionType() + " starts with a move-result at "
                            + address);
                } else if (isPayload(layout.byAddress.get(address))) {
                    findings.add("try: a handler for " + handler.getExceptionType() + " starts at a payload at "
                            + address);
                }
            }
        }

        // Invokes: as many registers as the callee takes, and each wide argument in a pair.
        for (int k = 0; k < layout.instructions.size(); k++) {
            Instruction i = layout.instructions.get(k);
            Opcode opcode = i.getOpcode();
            if (!isPlainInvoke(opcode) || !(i instanceof ReferenceInstruction)
                    || !(((ReferenceInstruction) i).getReference() instanceof MethodReference)) continue;
            MethodReference callee = (MethodReference) ((ReferenceInstruction) i).getReference();
            List<Character> expected = expectedArguments(opcode, callee);
            int[] regs = invokeRegisters(i);
            int at = layout.addresses.get(k);
            if (regs.length != expected.size()) {
                findings.add("invoke: " + opcode.name + " at " + at + " passes " + regs.length
                        + (regs.length == 1 ? " register" : " registers") + " to " + callee
                        + ", which takes " + expected.size());
                continue;
            }
            for (int a = 0; a < regs.length; a++) {
                if (expected.get(a) == 'w' && regs[a] != regs[a - 1] + 1) {
                    findings.add("invoke: " + opcode.name + " at " + at + " splits a wide argument of "
                            + callee + " across v" + regs[a - 1] + " and v" + regs[a]);
                }
            }
        }

        // Parameters: registerCount minus the ins, then this, then each parameter in order.
        boolean isStatic = AccessFlags.STATIC.isSet(m.getAccessFlags());
        int ins = isStatic ? 0 : 1;
        for (CharSequence p : m.getParameterTypes()) ins += slots(p);
        int firstParameter = impl.getRegisterCount() - ins;
        if (firstParameter < 0) {
            findings.add("parameter: declares " + impl.getRegisterCount() + " registers but its parameters need " + ins);
            return findings;
        }
        Map<Integer, Character> parameterKind = new HashMap<>();
        int register = firstParameter;
        if (!isStatic) parameterKind.put(register++, 'L');
        for (CharSequence p : m.getParameterTypes()) {
            char kind = kindOf(p);
            parameterKind.put(register, kind);
            if (kind == 'W') parameterKind.put(register + 1, 'w');
            register += slots(p);
        }

        // Kinds: a read of a register that every path gives the wrong kind. Where no path has
        // written the register it still holds its argument, and the finding is the parameter
        // layout's (the static and wide off-by-one); otherwise the body wrote the wrong width,
        // such as a narrow const left where a const-wide was.
        BitSet[] writes = parameterWrites(impl, layout, firstParameter);
        char[][] kinds = registerKinds(impl, layout, parameterKind);
        for (int k = 0; k < layout.instructions.size(); k++) {
            if (kinds[k] == null || writes[k] == null) continue;
            Instruction i = layout.instructions.get(k);
            Opcode opcode = i.getOpcode();
            int at = layout.addresses.get(k);

            for (int[] read : valueReads(i)) {
                // A register out of range is already a register finding for this instruction.
                if (read[0] >= kinds[k].length) continue;
                char have = kinds[k][read[0]];
                if (readableAs(have, (char) read[1]) || (have == 'C' && copiesConflict(opcode))) continue;
                findings.add(origin(read[0], firstParameter, writes[k], have) + opcode.name + " at " + at
                        + " reads v" + read[0] + ", which holds " + describe(have)
                        + ", as " + describe((char) read[1]));
            }

            // An equality test compares two objects or two narrow values, and a zero may stand
            // for either. Each register passes the read above on its own, so an int tested
            // against an object is only caught as a pair; ART refuses it ("args to if-eq/if-ne
            // must both be references or integral").
            if (opcode == Opcode.IF_EQ || opcode == Opcode.IF_NE) {
                int first = ((TwoRegisterInstruction) i).getRegisterA();
                int second = ((TwoRegisterInstruction) i).getRegisterB();
                if (first < kinds[k].length && second < kinds[k].length) {
                    char a = kinds[k][first];
                    char b = kinds[k][second];
                    if ((a == 'I' && b == 'L') || (a == 'L' && b == 'I')) {
                        findings.add("width: " + opcode.name + " at " + at + " compares v" + first + ", which holds "
                                + describe(a) + ", with v" + second + ", which holds " + describe(b));
                    }
                }
            }

            if (!isPlainInvoke(opcode) || !(i instanceof ReferenceInstruction)
                    || !(((ReferenceInstruction) i).getReference() instanceof MethodReference)) continue;
            MethodReference callee = (MethodReference) ((ReferenceInstruction) i).getReference();
            List<Character> expected = expectedArguments(opcode, callee);
            int[] regs = invokeRegisters(i);
            if (regs.length != expected.size()) continue;
            for (int a = 0; a < regs.length; a++) {
                char want = expected.get(a);
                // The upper half of a wide argument is checked with its pair, and a register out
                // of range is already a register finding for this instruction.
                if (want == 'w' || regs[a] >= kinds[k].length) continue;
                char have = kinds[k][regs[a]];
                if (!readableAs(have, want)) {
                    findings.add(origin(regs[a], firstParameter, writes[k], have) + opcode.name + " at " + at
                            + " passes v" + regs[a] + ", which holds " + describe(have) + ", where "
                            + callee + " takes " + describe(want));
                }
            }
        }

        // A move-exception takes the exception a handler caught, so only a throw may reach it.
        // Falling into one from the instruction above is what ART calls flowing through to it,
        // and the method's entry reaches one at the very start the same way.
        if (!layout.instructions.isEmpty() && layout.instructions.get(0).getOpcode() == Opcode.MOVE_EXCEPTION) {
            findings.add("try: move-exception at 0 is reached from the method's entry");
        }
        for (int k = 1; k < layout.instructions.size(); k++) {
            if (layout.instructions.get(k).getOpcode() != Opcode.MOVE_EXCEPTION) continue;
            Instruction above = layout.instructions.get(k - 1);
            if (kinds[k - 1] != null && above.getOpcode().canContinue()) {
                findings.add("try: move-exception at " + layout.addresses.get(k) + " is reached by falling through from "
                        + above.getOpcode().name + " at " + layout.addresses.get(k - 1));
            }
        }

        // A payload is data, and ART fails the class wherever flow reaches one. A branch, a case
        // or a handler sent to one is reported above; falling into one from the instruction above
        // it, which a return or goto removed from before a switch table leaves, and a payload at
        // the very start are the other ways in.
        if (!layout.instructions.isEmpty() && isPayload(layout.instructions.get(0))) {
            findings.add("branch: the payload at 0 is reached from the method's entry");
        }
        for (int k = 1; k < layout.instructions.size(); k++) {
            if (!isPayload(layout.instructions.get(k))) continue;
            Instruction above = layout.instructions.get(k - 1);
            if (kinds[k - 1] != null && above.getOpcode().canContinue()) {
                findings.add("branch: the payload at " + layout.addresses.get(k) + " is reached by falling through from "
                        + above.getOpcode().name + " at " + layout.addresses.get(k - 1));
            }
        }

        // Flow may not run off the end of the code either. A method whose last return was removed
        // or nopped leaves its last instruction able to continue into nothing, and ART refuses the
        // class ("Execution can walk off end of code area").
        if (!layout.instructions.isEmpty()) {
            int last = layout.instructions.size() - 1;
            Instruction end = layout.instructions.get(last);
            if (kinds[last] != null && !isPayload(end) && end.getOpcode().canContinue()) {
                findings.add("branch: " + end.getOpcode().name + " at " + layout.addresses.get(last)
                        + " runs off the end of the code");
            }
        }
        return findings;
    }

    /**
     * The highest register an instruction touches: every register it names, the upper half of a
     * wide value it writes, and the upper half of each wide value it reads (valueReads knows which
     * operands those are, opcode by opcode). -1 for an instruction with no register.
     */
    private static int reach(Instruction i) {
        int high = -1;
        if (i instanceof RegisterRangeInstruction) {
            RegisterRangeInstruction r = (RegisterRangeInstruction) i;
            high = r.getStartRegister() + r.getRegisterCount() - 1;
        } else if (i instanceof FiveRegisterInstruction) {
            for (int register : invokeRegisters(i)) high = Math.max(high, register);
        } else if (i instanceof ThreeRegisterInstruction) {
            ThreeRegisterInstruction r = (ThreeRegisterInstruction) i;
            high = Math.max(r.getRegisterA(), Math.max(r.getRegisterB(), r.getRegisterC()));
        } else if (i instanceof TwoRegisterInstruction) {
            TwoRegisterInstruction r = (TwoRegisterInstruction) i;
            high = Math.max(r.getRegisterA(), r.getRegisterB());
        } else if (i instanceof OneRegisterInstruction) {
            high = ((OneRegisterInstruction) i).getRegisterA();
        }
        if (i.getOpcode().setsWideRegister() && i instanceof OneRegisterInstruction) {
            high = Math.max(high, ((OneRegisterInstruction) i).getRegisterA() + 1);
        }
        for (int[] read : valueReads(i)) {
            if (read[1] == 'W') high = Math.max(high, read[0] + 1);
        }
        return high;
    }

    /**
     * "parameter" for a register that still holds its argument on every path, else "width". A
     * broken pair or a conflict is always the body's doing, even on an argument's register.
     */
    private static String origin(int register, int firstParameter, BitSet written, char have) {
        if (have == 'B' || have == 'b' || have == 'C') return "width: ";
        boolean argument = register >= firstParameter && !written.get(register - firstParameter);
        return argument ? "parameter: " : "width: ";
    }

    /** A switch or array payload: data inside the code, which ART fails the moment flow reaches it. */
    private static boolean isPayload(Instruction i) {
        return i instanceof SwitchPayload || i instanceof ArrayPayload;
    }

    /**
     * What a branch or switch case may not land on, or null: ART refuses a move-result or
     * move-exception there, and a payload is data ("encountered data table in instruction stream").
     */
    private static String badLanding(Instruction landing) {
        if (landing == null) return null;
        if (isPayload(landing)) return "a payload";
        Opcode opcode = landing.getOpcode();
        if (isMoveResult(opcode)) return "a move-result";
        if (opcode == Opcode.MOVE_EXCEPTION) return "a move-exception";
        return null;
    }

    /** The kind of each register an invoke passes: the receiver, then each parameter's slots. */
    private static List<Character> expectedArguments(Opcode opcode, MethodReference callee) {
        List<Character> expected = new ArrayList<>();
        if (!isStaticInvoke(opcode)) expected.add('L');
        for (CharSequence p : callee.getParameterTypes()) {
            expected.add(kindOf(p));
            if (slots(p) == 2) expected.add('w');
        }
        return expected;
    }

    private static String describe(char kind) {
        switch (kind) {
            case 'L': return "an object";
            case 'W': return "a wide value";
            case 'w': return "the upper half of a wide value";
            case 'B': return "the lower half of a wide value whose upper half was overwritten";
            case 'b': return "the upper half of a wide value whose lower half was overwritten";
            case 'C': return "a different kind depending on the path taken";
            case 'Z': return "a zero constant";
            case 'E': return "a narrow value or an object";
            default: return "a narrow value";
        }
    }

    /**
     * Holds every changed and added method of the patched APK to the structural rules, and counts
     * each contract's call sites across the whole APK. Returns method signature to findings, with
     * contract results under the pseudo-method "contract". [clean] is the build the patched one
     * came from, which a sole-call rule reads the call its hook replaced out of. [fresh] holds the
     * prints of each wanted method's definitions that the clean build doesn't have; a definition
     * the clean build has body for body, a copy in another dex entry the patch left alone, isn't
     * checked again.
     */
    private static Map<String, List<String>> structuralPass(File apk, File clean, Set<String> wanted,
            Map<String, List<String>> fresh, List<Contract> contracts) throws Exception {
        Map<String, List<String>> out = new TreeMap<>();
        Map<String, List<String>> unchecked = new HashMap<>();
        for (Map.Entry<String, List<String>> e : fresh.entrySet()) unchecked.put(e.getKey(), new ArrayList<>(e.getValue()));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        Map<String, List<String>> callSites = new LinkedHashMap<>();
        // first-call: the method, and the first call it makes on the class before it returns, or
        // an empty string when it makes none there.
        Map<String, String> firstCalls = new HashMap<>();
        Map<String, String> firstCallTargets = new HashMap<>();
        // first-call on-type-named: the method, and the first call it makes on any class, whose
        // class is held afterwards to the classes whose getTypeName() answers the type.
        Map<String, String> typedFirstCallTargets = new HashMap<>();
        Map<String, Set<String>> typeNamedClasses = new HashMap<>();
        // first-call outside: the method, and the class prefix its first call has to leave.
        Map<String, String> outsideFirstCallTargets = new HashMap<>();
        // start-call, next-call, sole-call and once-call: for each rule, every method outside the
        // bundle's own code that loads all of its strings, with whether that method has the rule's shape. The
        // rules' own strings are the only ones collected, so a method is read once.
        List<Contract> pickRules = new ArrayList<>();
        Map<Contract, List<Holder>> holders = new LinkedHashMap<>();
        Set<String> pickStrings = new HashSet<>();
        // What each host constructor loads of those strings, and the methods holding part of a rule's
        // strings that wait on the constructors they call for the rest.
        Map<String, Set<String>> builtWith = new HashMap<>();
        List<Partial> partial = new ArrayList<>();
        // And every method outside the bundle's own code that calls such a rule's method, to say
        // where a hook went when it isn't in the method its rule picks, or went there as well.
        Map<String, List<String>> hookCallers = new HashMap<>();
        // no-call: the package prefix whose classes may call the method, and the methods of every
        // other class that do.
        Map<String, String> noCallInside = new HashMap<>();
        Map<String, List<String>> noCallSites = new LinkedHashMap<>();
        for (Contract contract : contracts) {
            if (contract.kind.equals("single-call") || contract.kind.equals("count-call")) {
                callSites.put(contract.callee, new ArrayList<>());
            }
            else if (contract.kind.equals("first-call")) firstCallTargets.put(contract.callee, contract.target);
            else if (contract.kind.equals(TYPED_FIRST_CALL)) {
                typedFirstCallTargets.put(contract.callee, contract.target);
                typeNamedClasses.put(contract.target, new TreeSet<>());
            } else if (contract.kind.equals(OUTSIDE_FIRST_CALL)) {
                outsideFirstCallTargets.put(contract.callee, contract.target);
            } else if (contract.kind.equals("no-call")) {
                noCallInside.put(contract.callee, contract.target);
                noCallSites.put(contract.callee, new ArrayList<>());
            } else {
                pickRules.add(contract);
                holders.put(contract, new ArrayList<>());
                pickStrings.addAll(contract.strings);
                hookCallers.put(contract.callee, new ArrayList<>());
            }
        }
        // Each uncast Object return in a checked method, as its method, declared type and finding,
        // and every interface the APK defines, which decides which of them stand.
        List<String[]> uncast = new ArrayList<>();
        Set<String> interfaces = new HashSet<>();
        // Each object a checked method hands on whose class isn't the one asked for, as its method,
        // the class wanted, the class held and the finding, and every class's superclass, which
        // decide which of them stand.
        List<String[]> mistyped = new ArrayList<>();
        Map<String, String> superclasses = new HashMap<>();
        Map<String, Integer> superclassDex = new HashMap<>();
        MultiDexContainer<? extends DexFile> container =
                DexFileFactory.loadDexContainer(apk, Opcodes.getDefault());
        // Every class's place is read before any method, since where two paths meet the type
        // flow takes the class they share (see joinTypes).
        for (String entry : container.getDexEntryNames()) {
            int dexNumber = appDexNumber(entry);
            for (ClassDef cd : container.getEntry(entry).getDexFile().getClasses()) {
                if (AccessFlags.INTERFACE.isSet(cd.getAccessFlags())) interfaces.add(cd.getType());
                // Only the app's own classesN.dex files say what a class extends, the lowest
                // numbered first, as ART's path does. Helium's standalone dexes (one in assets,
                // one in lib) carry their own LinearLayoutManager, extending another
                // LayoutManager, for a loader of their own; read last, it made every
                // setLayoutManager call on 585 look refused.
                Integer seen = superclassDex.get(cd.getType());
                if (dexNumber > 0 && (seen == null || dexNumber < seen)) {
                    superclasses.put(cd.getType(), cd.getSuperclass());
                    superclassDex.put(cd.getType(), dexNumber);
                }
            }
        }
        // The platform classes the APK's own classes extend, which are classes and not interfaces.
        Set<String> platformClasses = new HashSet<>();
        for (String parent : superclasses.values()) {
            if (parent != null && !superclasses.containsKey(parent) && isPlatform(parent)) platformClasses.add(parent);
        }
        platformClasses.remove(OBJECT);
        for (String entry : container.getDexEntryNames()) {
            for (ClassDef cd : container.getEntry(entry).getDexFile().getClasses()) {
                for (Method m : cd.getMethods()) {
                    String s = sig(cd, m);
                    List<String> left = wanted.contains(s) ? unchecked.get(s) : null;
                    if (left != null && left.remove(print(m, digest))) {
                        List<String> findings = structuralFindings(cd, m);
                        if (!findings.isEmpty()) out.computeIfAbsent(s, k -> new ArrayList<>()).addAll(findings);
                        for (String[] found : objectReturns(m)) uncast.add(new String[]{s, found[0], found[1]});
                        for (String[] found : typeChecks(cd, m, superclasses, interfaces)) {
                            mistyped.add(new String[]{s, found[0], found[1], found[2], found[3]});
                        }
                    }
                    String firstCallOn = firstCallTargets.get(s);
                    if (firstCallOn != null) firstCalls.put(s, firstCallBeforeReturn(m, firstCallOn));
                    if (typedFirstCallTargets.containsKey(s)) firstCalls.put(s, firstCallBeforeReturn(m, null));
                    String leaving = outsideFirstCallTargets.get(s);
                    if (leaving != null) firstCalls.put(s, firstCallOutside(m, leaving));
                    if (!typeNamedClasses.isEmpty()) recordTypeNamed(cd, m, typeNamedClasses);
                    if (!pickRules.isEmpty() && !cd.getType().startsWith(OWN)) {
                        recordHolders(s, m, pickRules, pickStrings, holders, builtWith, partial);
                    }
                    if ((callSites.isEmpty() && noCallSites.isEmpty() && hookCallers.isEmpty())
                            || m.getImplementation() == null) continue;
                    for (Instruction i : m.getImplementation().getInstructions()) {
                        if (!(i instanceof ReferenceInstruction)) continue;
                        Reference r = ((ReferenceInstruction) i).getReference();
                        if (!(r instanceof MethodReference)) continue;
                        List<String> sites = callSites.get(r.toString());
                        if (sites != null) sites.add(s);
                        String inside = noCallInside.get(r.toString());
                        if (inside != null && !cd.getType().startsWith(inside)) noCallSites.get(r.toString()).add(s);
                        List<String> callers = hookCallers.get(r.toString());
                        if (callers != null && !cd.getType().startsWith(OWN) && !callers.contains(s)) callers.add(s);
                    }
                }
            }
        }
        for (String[] found : uncast) {
            if (!takesAnyObject(found[1], interfaces)) out.computeIfAbsent(found[0], k -> new ArrayList<>()).add(found[2]);
        }
        List<String[]> refused = new ArrayList<>();
        for (String[] found : mistyped) {
            if (refuses(found[2], found[1], superclasses, interfaces, platformClasses)) refused.add(found);
        }
        Map<String, Set<String>> declared = declaredMembers(container, refused, superclasses, superclassDex);
        for (String[] found : refused) {
            if (!declaredAbove(found, superclasses, declared)) {
                out.computeIfAbsent(found[0], k -> new ArrayList<>()).add(found[3]);
            }
        }
        resolvePartial(partial, builtWith, holders);
        List<String> contractFindings = new ArrayList<>();
        for (Contract contract : contracts) {
            if (contract.picks()) {
                checkPicked(contract, holders.get(contract), hookCallers.get(contract.callee), clean, contractFindings);
                continue;
            }
            if (contract.kind.equals("no-call")) {
                List<String> left = noCallSites.get(contract.callee);
                System.out.println("[diff] contract no-call " + contract.callee + " outside " + contract.target + ": "
                        + left.size() + " call site" + (left.size() == 1 ? "" : "s")
                        + (left.isEmpty() ? "" : ", in " + String.join(", ", left)));
                if (!left.isEmpty()) {
                    contractFindings.add("contract: " + contract.callee + " is still called outside "
                            + contract.target + ", in " + String.join(", ", left));
                }
                continue;
            }
            if (contract.kind.equals("first-call")) {
                String call = firstCalls.get(contract.callee);
                String rule = "contract first-call " + contract.callee;
                if (call == null) {
                    System.out.println("[diff] " + rule + ": not in the APK");
                    contractFindings.add("contract: " + contract.callee + " is not in the APK, so nothing calls "
                            + contract.target + " from it");
                } else if (call.isEmpty()) {
                    System.out.println("[diff] " + rule + ": no call on " + contract.target + " before its first return");
                    contractFindings.add("contract: " + contract.callee + " returns before it calls a method of "
                            + contract.target + " that takes no arguments, so the patch didn't fill it");
                } else {
                    System.out.println("[diff] " + rule + ": calls " + call + " before its first return");
                }
                continue;
            }
            if (contract.kind.equals(OUTSIDE_FIRST_CALL)) {
                String call = firstCalls.get(contract.callee);
                String rule = "contract first-call " + contract.callee + " outside " + contract.target;
                if (call == null) {
                    System.out.println("[diff] " + rule + ": not in the APK");
                    contractFindings.add("contract: " + contract.callee + " is not in the APK, so nothing outside "
                            + contract.target + " is called from it");
                } else if (call.isEmpty()) {
                    System.out.println("[diff] " + rule + ": no call outside it before its first return");
                    contractFindings.add("contract: " + contract.callee + " returns before it calls a method outside "
                            + contract.target + ", so the patch didn't fill it");
                } else {
                    System.out.println("[diff] " + rule + ": calls " + call + " before its first return");
                }
                continue;
            }
            if (contract.kind.equals(TYPED_FIRST_CALL)) {
                String call = firstCalls.get(contract.callee);
                Set<String> named = typeNamedClasses.get(contract.target);
                String rule = "contract first-call " + contract.callee + " on-type-named " + contract.target;
                String owner = call == null || call.isEmpty() ? null : call.substring(0, call.indexOf("->"));
                if (named.size() != 1) {
                    System.out.println("[diff] " + rule + ": " + named.size() + " classes answer it");
                    contractFindings.add("contract: " + named.size() + " classes answer getTypeName() with \""
                            + contract.target + "\", and exactly one must" + (named.isEmpty() ? "" : ": " + String.join(", ", named)));
                } else if (call == null) {
                    System.out.println("[diff] " + rule + ": not in the APK");
                    contractFindings.add("contract: " + contract.callee + " is not in the APK, so nothing calls "
                            + named.iterator().next() + " from it");
                } else if (owner == null) {
                    System.out.println("[diff] " + rule + ": no call before its first return");
                    contractFindings.add("contract: " + contract.callee + " returns before it calls a method of "
                            + named.iterator().next() + " that takes no arguments, so the patch didn't fill it");
                } else if (!named.contains(owner)) {
                    System.out.println("[diff] " + rule + ": calls " + call + ", not a method of " + named.iterator().next());
                    contractFindings.add("contract: " + contract.callee + " calls " + call + " first, not a method of "
                            + named.iterator().next() + ", the class answering \"" + contract.target + "\"");
                } else {
                    System.out.println("[diff] " + rule + ": calls " + call + " before its first return");
                }
                continue;
            }
            List<String> sites = callSites.get(contract.callee);
            if (contract.kind.equals("count-call")) {
                checkCounted(contract, sites, contractFindings);
                continue;
            }
            System.out.println("[diff] contract " + contract.callee + ": " + sites.size() + " call site"
                    + (sites.size() == 1 ? "" : "s") + (sites.isEmpty() ? "" : ", in " + String.join(", ", sites)));
            if (sites.size() != 1) {
                contractFindings.add("contract: " + contract.callee + " has " + sites.size()
                        + " call sites, and must have exactly one, in " + contract.target
                        + (sites.isEmpty() ? "" : ": " + String.join(", ", sites)));
            } else if (!sites.get(0).contains("->" + contract.target + "(")) {
                contractFindings.add("contract: " + contract.callee + " is called from " + sites.get(0)
                        + ", not from " + contract.target);
            }
        }
        if (!contractFindings.isEmpty()) out.put("contract", contractFindings);
        return out;
    }

    /**
     * Holds a count-call rule to its call sites: exactly its count of them, every one in a method
     * of the rule's name, and no two in one class (two calls in one method count as two sites).
     */
    private static void checkCounted(Contract contract, List<String> sites, List<String> findings) {
        String rule = "contract count-call " + contract.callee + " in " + contract.target + " " + contract.count;
        System.out.println("[diff] " + rule + ": " + sites.size() + " call site" + (sites.size() == 1 ? "" : "s")
                + (sites.isEmpty() ? "" : ", in " + String.join(", ", sites)));
        List<String> elsewhere = new ArrayList<>();
        Set<String> classes = new TreeSet<>();
        for (String site : sites) {
            if (site.contains("->" + contract.target + "(")) classes.add(site.substring(0, site.indexOf("->")));
            else elsewhere.add(site);
        }
        if (sites.size() != contract.count) {
            findings.add("contract: " + contract.callee + " has " + sites.size() + " call sites, and must have exactly "
                    + contract.count + ", each in " + contract.target + " of a class of its own"
                    + (sites.isEmpty() ? "" : ": " + String.join(", ", sites)));
        } else if (!elsewhere.isEmpty()) {
            findings.add("contract: " + contract.callee + " is called from " + String.join(", ", elsewhere)
                    + ", not from " + contract.target);
        } else if (classes.size() != contract.count) {
            findings.add("contract: " + contract.callee + " is called more than once in one class's "
                    + contract.target + ": " + String.join(", ", sites));
        }
    }

    /** Where a method calls a start-call rule's method: not at all, first thing, or later. */
    private static final int NOT_CALLED = 0;
    private static final int LATER = 1;
    private static final int FIRST = 2;

    /**
     * A method outside the bundle's own code that loads every string of a start-call, next-call,
     * sole-call or once-call rule: its signature, whether it has the rule's shape, and the method
     * itself, which the rule reads again once it knows which one it picked.
     */
    private static final class Holder {
        final String method;
        final boolean shaped;
        final Method m;

        Holder(String method, boolean shaped, Method m) {
            this.method = method;
            this.shaped = shaped;
            this.m = m;
        }
    }

    /**
     * A method holding part of a rule's strings, already in the rule's holders where the scan met it
     * so they stay in dex order, and waiting on the constructors it calls for the rest.
     */
    private static final class Partial {
        final Contract rule;
        final Holder holder;
        final Set<String> held;
        final List<String> builds;

        Partial(Contract rule, Holder holder, Set<String> held, List<String> builds) {
            this.rule = rule;
            this.holder = holder;
            this.held = held;
            this.builds = builds;
        }
    }

    /**
     * Adds [m] to each rule whose strings it loads, every one of them. A method loading only part of
     * them and calling a constructor is added too, and goes to [partial] with the constructors it
     * calls, which [resolvePartial] reads once every constructor's strings are in [builtWith]. Each
     * constructor's strings go there as it's read. A rule's class descriptor counts as loaded by
     * the methods of that class.
     */
    private static void recordHolders(String s, Method m, List<Contract> rules, Set<String> wanted,
            Map<Contract, List<Holder>> holders, Map<String, Set<String>> builtWith, List<Partial> partial) {
        if (m.getImplementation() == null) return;
        Set<String> held = null;
        if (wanted.contains(m.getDefiningClass())) {
            held = new HashSet<>();
            held.add(m.getDefiningClass());
        }
        List<String> builds = null;
        for (Instruction i : m.getImplementation().getInstructions()) {
            if (!(i instanceof ReferenceInstruction)) continue;
            Reference r = ((ReferenceInstruction) i).getReference();
            if (r instanceof MethodReference && ((MethodReference) r).getName().equals("<init>")) {
                if (builds == null) builds = new ArrayList<>();
                builds.add(r.toString());
                continue;
            }
            if (!(r instanceof StringReference) || !wanted.contains(((StringReference) r).getString())) continue;
            if (held == null) held = new HashSet<>();
            held.add(((StringReference) r).getString());
        }
        if (held == null) return;
        if (m.getName().equals("<init>")) builtWith.computeIfAbsent(s, k -> new HashSet<>()).addAll(held);
        for (Contract rule : rules) {
            boolean all = held.containsAll(rule.strings);
            if (!all && (builds == null || Collections.disjoint(held, rule.strings))) continue;
            Holder holder = new Holder(s, rule.hasShape(m), m);
            holders.get(rule).add(holder);
            if (!all) partial.add(new Partial(rule, holder, held, builds));
        }
    }

    /**
     * Keeps each method in [partial] among its rule's [holders] only when what it loads and what
     * the constructors it calls load ([builtWith]) together hold every one of the rule's strings.
     * Facebook 584 moved the seen-state mutation's input field into its input class's constructor.
     */
    private static void resolvePartial(List<Partial> partial, Map<String, Set<String>> builtWith,
            Map<Contract, List<Holder>> holders) {
        for (Partial p : partial) {
            Set<String> held = new HashSet<>(p.held);
            for (String built : p.builds) {
                Set<String> with = builtWith.get(built);
                if (with != null) held.addAll(with);
            }
            if (!held.containsAll(p.rule.strings)) holders.get(p.rule).remove(p.holder);
        }
    }

    /**
     * Holds a start-call, next-call, sole-call or once-call rule to the patched APK. Exactly one of
     * [holders] has the rule's shape, and it calls the rule's method; [callers] are the host methods
     * that call that method anywhere. Then each kind asks its own questions: start-call that no other
     * holder calls it and that the call comes first; once-call that no other host method calls it and
     * that it's the method's one call there; next-call that as well, right after the call it pairs
     * with and on the same register; sole-call that it's the method's one call, that the call it
     * stands in for is gone, and that it reads what that call read in [clean].
     */
    private static void checkPicked(Contract contract, List<Holder> holders, List<String> callers, File clean,
            List<String> findings) throws Exception {
        String rule = "contract " + contract.rule();
        String held = describePicked(contract);
        // A signature defined in more than one dex entry is one method, and the copy calling the
        // rule's method is the patched one the other checks read, so it stands for the rest.
        Map<String, Holder> bySignature = new LinkedHashMap<>();
        for (Holder holder : holders) {
            Holder seen = bySignature.get(holder.method);
            if (seen == null || (callSites(instructions(seen.m), contract.callee).isEmpty()
                    && !callSites(instructions(holder.m), contract.callee).isEmpty())) bySignature.put(holder.method, holder);
        }
        holders = new ArrayList<>(bySignature.values());
        List<String> shaped = new ArrayList<>();
        Holder only = null;
        for (Holder holder : holders) {
            if (!holder.shaped) continue;
            shaped.add(holder.method);
            only = holder;
        }
        if (shaped.size() != 1) {
            // None, and the method moved or lost a string; several, and the rule can't tell the
            // right one from the others, so a hook in any of them would pass.
            System.out.println("[diff] " + rule + ": " + shaped.size() + " methods answer it" + named(shaped));
            findings.add("contract: " + shaped.size() + " methods hold " + held + ", and exactly one"
                    + " must, so the rule can't say which one calls " + contract.callee + named(shaped));
            return;
        }
        List<Instruction> body = instructions(only.m);
        List<Integer> sites = callSites(body, contract.callee);
        // Where else the hook went. A start-call rule looks among the methods holding its strings,
        // since the two Messenger icon rules send the same call to two methods. A next-call, sole-call or
        // once-call hook belongs to one method, so a second call anywhere is one too many.
        List<String> elsewhere = new ArrayList<>();
        if (contract.kind.equals("start-call")) {
            for (Holder holder : holders) {
                if (holder != only && !elsewhere.contains(holder.method)
                        && !callSites(instructions(holder.m), contract.callee).isEmpty()) elsewhere.add(holder.method);
            }
        } else {
            for (String caller : callers) if (!caller.equals(only.method)) elsewhere.add(caller);
        }
        if (sites.isEmpty()) {
            System.out.println("[diff] " + rule + ": not called in " + only.method
                    + (callers.isEmpty() ? "" : "; called in " + String.join(", ", callers)));
            findings.add("contract: " + contract.callee + " is not called in " + only.method
                    + ", the one method holding " + held
                    + (callers.isEmpty() ? "" : "; the host methods that call it: " + String.join(", ", callers)));
        } else if (!elsewhere.isEmpty()) {
            System.out.println("[diff] " + rule + ": called in " + only.method + " and in " + String.join(", ", elsewhere));
            findings.add("contract: " + contract.callee + " is called in " + String.join(", ", elsewhere)
                    + " as well as in " + only.method + ", the one method holding " + held);
        } else if (contract.kind.equals("start-call")) {
            if (callOf(only.m, contract.callee) != FIRST) {
                System.out.println("[diff] " + rule + ": not first in " + only.method);
                findings.add("contract: " + contract.callee + " is called in " + only.method
                        + ", but after a call, branch, switch, return or throw, not first");
            } else {
                System.out.println("[diff] " + rule + ": first in " + only.method);
            }
        } else if (sites.size() > 1) {
            System.out.println("[diff] " + rule + ": " + sites.size() + " call sites in " + only.method);
            findings.add("contract: " + contract.callee + " has " + sites.size() + " call sites in " + only.method
                    + ", and must have exactly one");
        } else if (contract.kind.equals("once-call")) {
            System.out.println("[diff] " + rule + ": once in " + only.method);
        } else if (contract.kind.equals("next-call")) {
            int at = sites.get(0);
            Instruction before = at == 0 ? null : body.get(at - 1);
            String previous = before != null && before.getOpcode().name.startsWith("invoke")
                    && before instanceof ReferenceInstruction ? ((ReferenceInstruction) before).getReference().toString() : null;
            int register = firstRegister(body.get(at));
            int previousRegister = before == null ? -1 : firstRegister(before);
            if (!contract.after.equals(previous)) {
                System.out.println("[diff] " + rule + ": after " + (previous == null ? "no call" : previous) + " in " + only.method);
                findings.add("contract: " + contract.callee + " is called in " + only.method
                        + ", but not right after " + contract.after);
            } else if (previousRegister != register) {
                System.out.println("[diff] " + rule + ": on v" + register + ", after a call on v" + previousRegister
                        + " in " + only.method);
                findings.add("contract: " + contract.callee + " is called in " + only.method + " on v" + register
                        + ", not on v" + previousRegister + ", the register " + contract.after + " is made on");
            } else {
                System.out.println("[diff] " + rule + ": right after it on v" + register + " in " + only.method);
            }
        } else if (!callSites(body, contract.replaced).isEmpty()) {
            System.out.println("[diff] " + rule + ": " + only.method + " still calls " + contract.replaced);
            findings.add("contract: " + only.method + " still calls " + contract.replaced + ", which "
                    + contract.callee + " stands in for");
        } else {
            // What the hook reads, against what the call it took the place of read in the clean
            // build: the one such call there, on the same registers in the same order.
            List<Integer> hook = callRegisters(body.get(sites.get(0)));
            List<List<Integer>> was = cleanCallRegisters(clean, only.method, contract.replaced);
            if (was.size() == 1 && was.get(0).equals(hook)) {
                System.out.println("[diff] " + rule + ": in place of it on " + registerList(hook) + " in " + only.method);
            } else {
                String there = was.size() == 1 ? "on " + registerList(was.get(0)) : was.size() + " times, not once";
                System.out.println("[diff] " + rule + ": on " + registerList(hook) + " in " + only.method
                        + ", where the clean build calls it " + there);
                findings.add("contract: " + contract.callee + " is called in " + only.method + " on "
                        + registerList(hook) + ", but the clean build calls " + contract.replaced + " there " + there);
            }
        }
    }

    /** [m]'s instructions, in order. */
    private static List<Instruction> instructions(Method m) {
        List<Instruction> body = new ArrayList<>();
        if (m.getImplementation() != null) for (Instruction i : m.getImplementation().getInstructions()) body.add(i);
        return body;
    }

    /** The index in [body] of each invoke of [callee]. */
    private static List<Integer> callSites(List<Instruction> body, String callee) {
        List<Integer> sites = new ArrayList<>();
        for (int k = 0; k < body.size(); k++) {
            Instruction i = body.get(k);
            if (i.getOpcode().name.startsWith("invoke") && i instanceof ReferenceInstruction
                    && ((ReferenceInstruction) i).getReference().toString().equals(callee)) sites.add(k);
        }
        return sites;
    }

    /** The registers an invoke passes, in order, as a list. */
    private static List<Integer> callRegisters(Instruction i) {
        List<Integer> registers = new ArrayList<>();
        for (int register : invokeRegisters(i)) registers.add(register);
        return registers;
    }

    /** "v2, v1" for [registers]. */
    private static String registerList(List<Integer> registers) {
        List<String> named = new ArrayList<>();
        for (int register : registers) named.add("v" + register);
        return String.join(", ", named);
    }

    /**
     * The registers of each call to [callee] that [method] makes in [clean], in every dex entry
     * defining it. Only its own class's methods are read.
     */
    private static List<List<Integer>> cleanCallRegisters(File clean, String method, String callee) throws Exception {
        String type = method.substring(0, method.indexOf("->"));
        List<List<Integer>> calls = new ArrayList<>();
        MultiDexContainer<? extends DexFile> container = DexFileFactory.loadDexContainer(clean, Opcodes.getDefault());
        for (String entry : container.getDexEntryNames()) {
            for (ClassDef cd : container.getEntry(entry).getDexFile().getClasses()) {
                if (!cd.getType().equals(type)) continue;
                for (Method m : cd.getMethods()) {
                    if (!sig(cd, m).equals(method)) continue;
                    List<Instruction> body = instructions(m);
                    for (int at : callSites(body, callee)) calls.add(callRegisters(body.get(at)));
                }
            }
        }
        return calls;
    }

    /**
     * FIRST when [m]'s first call to [callee] has only plain instructions before it (no other
     * call, branch, switch, return or throw), LATER when it calls it after one, NOT_CALLED when it
     * doesn't call it at all.
     */
    private static int callOf(Method m, String callee) {
        boolean plainSoFar = true;
        boolean called = false;
        for (Instruction i : m.getImplementation().getInstructions()) {
            String name = i.getOpcode().name;
            if (name.startsWith("invoke") && i instanceof ReferenceInstruction
                    && ((ReferenceInstruction) i).getReference().toString().equals(callee)) {
                if (plainSoFar) return FIRST;
                called = true;
            }
            if (name.startsWith("invoke") || name.startsWith("return") || name.startsWith("goto")
                    || name.startsWith("if-") || name.endsWith("-switch") || name.equals("throw")) {
                plainSoFar = false;
            }
        }
        return called ? LATER : NOT_CALLED;
    }

    /** A picking rule's strings as a finding names them, and its shape when it has one. */
    private static String describePicked(Contract rule) {
        List<String> quoted = new ArrayList<>();
        for (String s : rule.strings) quoted.add("\"" + s + "\"");
        String strings = quoted.size() == 1 ? quoted.get(0)
                : String.join(", ", quoted.subList(0, quoted.size() - 1)) + " and " + quoted.get(quoted.size() - 1);
        if (rule.shape == null) return strings;
        return strings + " with the shape " + (rule.isStatic == null ? "" : rule.isStatic ? "static " : "instance ") + rule.shape;
    }

    /** ": " and the first eight of [methods], or nothing for none. */
    private static String named(List<String> methods) {
        if (methods.isEmpty()) return "";
        List<String> shown = methods.subList(0, Math.min(8, methods.size()));
        return ": " + String.join(", ", shown) + (methods.size() > shown.size() ? " and " + (methods.size() - shown.size()) + " more" : "");
    }

    /** The first register an invoke names, or -1 for an instruction that isn't one. */
    private static int firstRegister(Instruction i) {
        if (i instanceof RegisterRangeInstruction) return ((RegisterRangeInstruction) i).getStartRegister();
        if (i instanceof FiveRegisterInstruction && ((FiveRegisterInstruction) i).getRegisterCount() > 0) {
            return ((FiveRegisterInstruction) i).getRegisterC();
        }
        return -1;
    }

    /**
     * Adds [cd] to the classes of each type name its {@code getTypeName()} loads as a literal, for
     * the type names a contract asks about.
     */
    private static void recordTypeNamed(ClassDef cd, Method m, Map<String, Set<String>> typeNamedClasses) {
        if (!m.getName().equals("getTypeName") || !m.getReturnType().equals("Ljava/lang/String;")
                || !m.getParameterTypes().isEmpty() || m.getImplementation() == null) {
            return;
        }
        for (Instruction i : m.getImplementation().getInstructions()) {
            if (!(i instanceof ReferenceInstruction)) continue;
            Reference r = ((ReferenceInstruction) i).getReference();
            if (!(r instanceof StringReference)) continue;
            Set<String> classes = typeNamedClasses.get(((StringReference) r).getString());
            if (classes != null) classes.add(cd.getType());
        }
    }

    /**
     * The first call [m] makes, before any return or throw in its instruction order, to a method of
     * [owner], or of any class when [owner] is null, that takes no arguments, or an empty string
     * when there is none. A filled stub makes that call first thing; the stub's own body, left after
     * it, only answers a marker.
     */
    private static String firstCallBeforeReturn(Method m, String owner) {
        if (m.getImplementation() == null) return "";
        for (Instruction i : m.getImplementation().getInstructions()) {
            String name = i.getOpcode().name;
            if (name.startsWith("return") || name.equals("throw")) return "";
            if (!(i instanceof ReferenceInstruction) || !name.startsWith("invoke")) continue;
            Reference r = ((ReferenceInstruction) i).getReference();
            if (r instanceof MethodReference
                    && (owner == null || ((MethodReference) r).getDefiningClass().equals(owner))
                    && ((MethodReference) r).getParameterTypes().isEmpty()) {
                return r.toString();
            }
        }
        return "";
    }

    /**
     * The first call [m] makes, before any return or throw in its instruction order, to a method of
     * a class whose type doesn't start with [prefix], whatever it takes, or an empty string when
     * there is none. A call into the extension on the way there doesn't count, and doesn't stop the
     * search.
     */
    private static String firstCallOutside(Method m, String prefix) {
        if (m.getImplementation() == null) return "";
        for (Instruction i : m.getImplementation().getInstructions()) {
            String name = i.getOpcode().name;
            if (name.startsWith("return") || name.equals("throw")) return "";
            if (!(i instanceof ReferenceInstruction) || !name.startsWith("invoke")) continue;
            Reference r = ((ReferenceInstruction) i).getReference();
            if (r instanceof MethodReference && !((MethodReference) r).getDefiningClass().startsWith(prefix)) {
                return r.toString();
            }
        }
        return "";
    }

    private static Set<String> dexEntries(File apk) throws Exception {
        MultiDexContainer<? extends DexFile> container =
                DexFileFactory.loadDexContainer(apk, Opcodes.getDefault());
        return new TreeSet<>(container.getDexEntryNames());
    }

    /**
     * Signature -> the print of each of its definitions ("registerCount:bodyHash"), sorted and
     * joined with a space, for every method of an APK.
     *
     * <p>A signature can be defined in more than one dex entry. Facebook 580's split bundle, merged,
     * carries the in-app browser's standalone dex twice (lib/arm64-v8a/libhelium_standalone.dex.so
     * and assets/heliumcore/helium_standalone.dex.force-store), and 480 of its methods are in
     * classes*.dex too, 212 of them with other bodies. dexlib2 reads the entries in name order, and
     * keyed one body to a signature, the last copy stood for every one: the browser's copy in lib/
     * hid whatever a patch did to the classes*.dex one, and read against base.apk, which has no
     * such copy, those 212 came out changed. So every definition counts, and a signature is changed
     * when the multiset of its definitions' prints is.
     */
    private static Map<String, String> fingerprintAll(File apk) throws Exception {
        Map<String, String> out = new HashMap<>(1 << 20);
        MultiDexContainer<? extends DexFile> container =
                DexFileFactory.loadDexContainer(apk, Opcodes.getDefault());
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String entry : container.getDexEntryNames()) {
            for (ClassDef cd : container.getEntry(entry).getDexFile().getClasses()) {
                for (Method m : cd.getMethods()) out.merge(sig(cd, m), print(m, digest), DexDiff::joinPrints);
            }
        }
        return out;
    }

    /**
     * What keeps [merged] from carrying every classes*.dex at [base]'s root byte for byte, and no
     * other classes*.dex there: "lacks", "changes" and "adds" with the entry names, or nothing.
     *
     * <p>The comparison reads a bundle's merge, which carries no signature, while Meta's signer is
     * checked on base.apk and the device half runs base.apk. The CLI's merger copies base.apk's
     * classes*.dex as they are; other dex a merge carries (580's in-app browser's, from
     * split_heliumcore.apk, twice) came from the splits under other names.
     */
    private static List<String> rootDexMismatch(File base, File merged) throws Exception {
        Map<String, String> baseDex = rootDexDigests(base);
        Map<String, String> mergedDex = rootDexDigests(merged);
        if (baseDex.isEmpty()) return List.of("was handed a " + base.getName() + " with no classes*.dex to hold it to");
        List<String> lacks = new ArrayList<>(), changes = new ArrayList<>(), adds = new ArrayList<>();
        for (Map.Entry<String, String> e : baseDex.entrySet()) {
            String other = mergedDex.get(e.getKey());
            if (other == null) lacks.add(e.getKey());
            else if (!other.equals(e.getValue())) changes.add(e.getKey());
        }
        for (String name : mergedDex.keySet()) if (!baseDex.containsKey(name)) adds.add(name);
        List<String> said = new ArrayList<>();
        if (!lacks.isEmpty()) said.add("lacks " + String.join(", ", lacks));
        if (!changes.isEmpty()) said.add("changes " + String.join(", ", changes));
        if (!adds.isEmpty()) said.add("adds " + String.join(", ", adds));
        return said;
    }

    /** classes*.dex entry name at an APK's root -> the SHA-256 of its bytes. */
    private static Map<String, String> rootDexDigests(File apk) throws Exception {
        Map<String, String> out = new TreeMap<>();
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(apk)) {
            for (java.util.zip.ZipEntry entry : java.util.Collections.list(zip.entries())) {
                if (!entry.getName().matches("classes\\d*\\.dex")) continue;
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (java.io.InputStream in = zip.getInputStream(entry)) {
                    byte[] buffer = new byte[1 << 16];
                    for (int n; (n = in.read(buffer)) > 0; ) digest.update(buffer, 0, n);
                }
                out.put(entry.getName(), java.util.HexFormat.of().formatHex(digest.digest()));
            }
        }
        return out;
    }

    /** One definition's "registerCount:bodyHash": its register count and its rendered body's hash. */
    private static String print(Method m, MessageDigest digest) throws Exception {
        MethodImplementation impl = m.getImplementation();
        StringBuilder body = new StringBuilder();
        int registers = 0;
        if (impl != null) {
            registers = impl.getRegisterCount();
            for (Instruction i : impl.getInstructions()) body.append(render(i)).append('\n');
            for (String t : tryBlocks(impl)) body.append(t).append('\n');
        }
        digest.reset();
        byte[] hash = digest.digest(body.toString().getBytes("UTF-8"));
        StringBuilder hex = new StringBuilder();
        for (int k = 0; k < 8; k++) hex.append(String.format("%02x", hash[k]));
        return registers + ":" + hex;
    }

    /** Two joined print lists as one, sorted, so the same definitions compare equal in any order. */
    private static String joinPrints(String a, String b) {
        List<String> all = new ArrayList<>(prints(a));
        all.addAll(prints(b));
        java.util.Collections.sort(all);
        return String.join(" ", all);
    }

    /** The prints fingerprintAll joined for one signature, one per definition; none for null. */
    private static List<String> prints(String joined) {
        return joined == null ? List.of() : Arrays.asList(joined.split(" "));
    }

    /** How many of fingerprintAll's signatures have more than one definition. */
    private static int multiplyDefined(Map<String, String> prints) {
        int count = 0;
        for (String joined : prints.values()) if (joined.indexOf(' ') >= 0) count++;
        return count;
    }

    /** The items of [a] that [b] doesn't hold, counting duplicates. */
    private static <T> List<T> without(List<T> a, List<T> b) {
        List<T> left = new ArrayList<>(b);
        List<T> out = new ArrayList<>();
        for (T item : a) if (!left.remove(item)) out.add(item);
        return out;
    }

    /** Signature -> the rendered body of each of its definitions, in dex entry order, for the named methods only. */
    private static Map<String, List<List<String>>> bodiesOf(File apk, Set<String> wanted) throws Exception {
        Map<String, List<List<String>>> out = new LinkedHashMap<>();
        MultiDexContainer<? extends DexFile> container =
                DexFileFactory.loadDexContainer(apk, Opcodes.getDefault());
        for (String entry : container.getDexEntryNames()) {
            for (ClassDef cd : container.getEntry(entry).getDexFile().getClasses()) {
                for (Method m : cd.getMethods()) {
                    String s = sig(cd, m);
                    if (!wanted.contains(s)) continue;
                    List<String> body = new ArrayList<>();
                    MethodImplementation impl = m.getImplementation();
                    if (impl != null) {
                        body.add("# registers=" + impl.getRegisterCount());
                        for (Instruction i : impl.getInstructions()) body.add(render(i));
                        body.addAll(tryBlocks(impl));
                    }
                    out.computeIfAbsent(s, k -> new ArrayList<>()).add(body);
                }
            }
        }
        return out;
    }

    /** Each try block as a line, so an exception range that moved is a body that changed. */
    private static List<String> tryBlocks(MethodImplementation impl) {
        List<String> out = new ArrayList<>();
        for (TryBlock<? extends ExceptionHandler> block : impl.getTryBlocks()) {
            StringBuilder b = new StringBuilder("# try start=").append(block.getStartCodeAddress())
                    .append(" units=").append(block.getCodeUnitCount());
            for (ExceptionHandler handler : block.getExceptionHandlers()) {
                b.append(" catch(").append(handler.getExceptionType())
                        .append(")->").append(handler.getHandlerCodeAddress());
            }
            out.add(b.toString());
        }
        return out;
    }

    private static String sig(ClassDef cd, Method m) {
        StringBuilder b = new StringBuilder(cd.getType()).append("->").append(m.getName()).append('(');
        for (CharSequence p : m.getParameterTypes()) b.append(p);
        return b.append(')').append(m.getReturnType()).toString();
    }

    /** One instruction as text: opcode, registers, branch offset, literal, reference, max register. */
    private static String render(Instruction i) {
        StringBuilder b = new StringBuilder(i.getOpcode().name);
        List<String> regs = new ArrayList<>();
        int maxReg = -1;
        if (i instanceof RegisterRangeInstruction) {
            RegisterRangeInstruction r = (RegisterRangeInstruction) i;
            regs.add("v" + r.getStartRegister() + "..v" + (r.getStartRegister() + r.getRegisterCount() - 1));
            maxReg = r.getStartRegister() + r.getRegisterCount() - 1;
        } else if (i instanceof FiveRegisterInstruction) {
            FiveRegisterInstruction r = (FiveRegisterInstruction) i;
            int n = r.getRegisterCount();
            int[] all = { r.getRegisterC(), r.getRegisterD(), r.getRegisterE(), r.getRegisterF(), r.getRegisterG() };
            for (int k = 0; k < n; k++) { regs.add("v" + all[k]); maxReg = Math.max(maxReg, all[k]); }
        } else if (i instanceof ThreeRegisterInstruction) {
            ThreeRegisterInstruction r = (ThreeRegisterInstruction) i;
            regs.add("v" + r.getRegisterA());
            regs.add("v" + r.getRegisterB());
            regs.add("v" + r.getRegisterC());
            maxReg = Math.max(r.getRegisterA(), Math.max(r.getRegisterB(), r.getRegisterC()));
        } else if (i instanceof TwoRegisterInstruction) {
            TwoRegisterInstruction r = (TwoRegisterInstruction) i;
            regs.add("v" + r.getRegisterA());
            regs.add("v" + r.getRegisterB());
            maxReg = Math.max(r.getRegisterA(), r.getRegisterB());
        } else if (i instanceof OneRegisterInstruction) {
            regs.add("v" + ((OneRegisterInstruction) i).getRegisterA());
            maxReg = ((OneRegisterInstruction) i).getRegisterA();
        }
        // A wide destination names its low half and occupies the pair, so it reaches one higher
        // than it says. Only the destination: dexlib2 does not describe which sources are wide,
        // and guessing there would fail a valid narrow destination sitting above a wide source.
        if (i.getOpcode().setsWideRegister() && i instanceof OneRegisterInstruction) {
            maxReg = Math.max(maxReg, ((OneRegisterInstruction) i).getRegisterA() + 1);
        }
        if (!regs.isEmpty()) b.append(' ').append(String.join(", ", regs));
        if (i instanceof OffsetInstruction) {
            b.append(", ").append(String.format("%+d", ((OffsetInstruction) i).getCodeOffset()));
        }
        if (i instanceof WideLiteralInstruction) {
            b.append(", #").append(((WideLiteralInstruction) i).getWideLiteral());
        }
        if (i instanceof ReferenceInstruction) {
            Reference r = ((ReferenceInstruction) i).getReference();
            if (r != null) b.append(", ").append(r);
        }
        // A payload's cases and values are part of the body: a case moved to another target
        // changed nothing else, and left out of the text, the method read as untouched.
        if (i instanceof SwitchPayload) {
            for (SwitchElement e : ((SwitchPayload) i).getSwitchElements()) {
                b.append(' ').append(e.getKey()).append("->").append(String.format("%+d", e.getOffset()));
            }
        }
        if (i instanceof ArrayPayload) {
            b.append(" width=").append(((ArrayPayload) i).getElementWidth());
            for (Number n : ((ArrayPayload) i).getArrayElements()) b.append(' ').append(n);
        }
        return b.append(" |maxreg=").append(maxReg).toString();
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 4 || args.length > 6) {
            System.err.println("usage: DexDiff <cleanApk> <patchedApk> <reportFile> <removalAllowlist> [<contracts> [<signedBase>]]");
            System.exit(2);
        }
        File clean = new File(args[0]);
        File patched = new File(args[1]);
        File allowlistFile = new File(args[3]);
        RemovalAllowlist allowlist = readRemovalAllowlist(allowlistFile);
        File contractFile = args.length > 4 ? new File(args[4]) : null;
        List<Contract> contracts = readContracts(contractFile);
        // The base.apk whose signer was checked, when the clean side is the bundle's merge.
        File signedBase = args.length > 5 ? new File(args[5]) : null;
        List<String> baseMismatch = signedBase == null ? List.of() : rootDexMismatch(signedBase, clean);

        System.out.println("[diff] fingerprinting clean " + clean.getName());
        Map<String, String> before = fingerprintAll(clean);
        Set<String> beforeDexEntries = dexEntries(clean);
        System.out.println("[diff] " + before.size() + " methods");
        System.out.println("[diff] fingerprinting patched " + patched.getName());
        Map<String, String> after = fingerprintAll(patched);
        Set<String> afterDexEntries = dexEntries(patched);
        System.out.println("[diff] " + after.size() + " methods");
        System.out.println("[diff] signatures defined in more than one dex entry: clean " + multiplyDefined(before)
                + ", patched " + multiplyDefined(after));

        Set<String> changed = new TreeSet<>();
        Set<String> added = new TreeSet<>();
        for (Map.Entry<String, String> e : after.entrySet()) {
            String was = before.get(e.getKey());
            if (was == null) added.add(e.getKey());
            else if (!was.equals(e.getValue())) changed.add(e.getKey());
        }
        // A method gone from the patched APK, or one of its definitions gone while a copy in another
        // dex entry stays: a definition the clean build has more of than the patched one.
        Set<String> removed = new TreeSet<>();
        for (Map.Entry<String, String> e : before.entrySet()) {
            if (prints(after.get(e.getKey())).size() < prints(e.getValue()).size()) removed.add(e.getKey());
        }
        // The clean side is Meta's build, which carries none of the bundle's code. A merged bundle
        // carries no signature to prove that the way base.apk does, and a patched build on the clean
        // side would hide everything the bundle added.
        Set<String> cleanOwn = new TreeSet<>();
        for (String s : before.keySet()) if (s.startsWith(OWN)) cleanOwn.add(s);
        Set<String> removedDexEntries = new TreeSet<>(beforeDexEntries);
        removedDexEntries.removeAll(afterDexEntries);

        Set<String> rejectedRemoved = new TreeSet<>(removed);
        rejectedRemoved.removeAll(allowlist.methods);
        Set<String> rejectedDexEntries = new TreeSet<>(removedDexEntries);
        rejectedDexEntries.removeAll(allowlist.dexEntries);
        Set<String> staleAllowedMethods = new TreeSet<>(allowlist.methods);
        staleAllowedMethods.removeAll(removed);
        Set<String> staleAllowedDexEntries = new TreeSet<>(allowlist.dexEntries);
        staleAllowedDexEntries.removeAll(removedDexEntries);

        Set<String> ownAdded = new TreeSet<>();
        for (String s : added) if (s.startsWith(OWN)) ownAdded.add(s);

        System.out.println("[diff] host methods changed: " + changed.size());
        System.out.println("[diff] methods added: " + added.size()
                + " (" + ownAdded.size() + " under " + OWN + ")");
        System.out.println("[diff] methods removed: " + removed.size()
                + " (rejected " + rejectedRemoved.size() + ")");
        System.out.println("[diff] DEX entries removed: " + removedDexEntries.size()
                + " (rejected " + rejectedDexEntries.size() + ")");

        // A pair of files with nothing between them is not a clean bill of health, it is the wrong
        // pair of files. Both of these were reachable by pointing the run at one APK twice.
        int problems = 0;
        if (!baseMismatch.isEmpty()) {
            System.out.println("[diff] FAIL: the clean APK " + clean.getName() + " doesn't carry " + signedBase.getName()
                    + "'s code as it is: it " + String.join("; ", baseMismatch) + ". Meta's signer was checked on "
                    + signedBase.getName() + ", so this would compare against code nobody checked.");
            problems++;
        } else if (signedBase != null) {
            System.out.println("[diff] the clean APK carries " + signedBase.getName() + "'s classes*.dex byte for byte");
        }
        if (!cleanOwn.isEmpty()) {
            System.out.println("[diff] FAIL: the clean APK carries " + cleanOwn.size() + " method"
                    + (cleanOwn.size() == 1 ? "" : "s") + " under " + OWN + " (" + cleanOwn.iterator().next()
                    + (cleanOwn.size() == 1 ? "" : " first") + "), so it is a patched build, not the one the patch started from.");
            problems++;
        }
        if (changed.isEmpty()) {
            System.out.println("[diff] FAIL: no host method differs, so these two APKs are not a "
                    + "clean build and a patched build of it.");
            problems++;
        }
        if (ownAdded.isEmpty()) {
            System.out.println("[diff] FAIL: the patched APK carries no method under " + OWN
                    + ", so no extension code was added to it.");
            problems++;
        }
        if (!rejectedRemoved.isEmpty()) {
            System.out.println("[diff] FAIL: removed host methods are not allowed:");
            for (String method : rejectedRemoved) System.out.println("[diff]   " + method);
            problems += rejectedRemoved.size();
        }
        if (!rejectedDexEntries.isEmpty()) {
            System.out.println("[diff] FAIL: removed DEX entries are not allowed:");
            for (String entry : rejectedDexEntries) System.out.println("[diff]   " + entry);
            problems += rejectedDexEntries.size();
        }
        if (!staleAllowedMethods.isEmpty() || !staleAllowedDexEntries.isEmpty()) {
            System.out.println("[diff] FAIL: the removal allowlist contains entries this pair did not remove:");
            for (String method : staleAllowedMethods) System.out.println("[diff]   method " + method);
            for (String entry : staleAllowedDexEntries) System.out.println("[diff]   dex " + entry);
            problems += staleAllowedMethods.size() + staleAllowedDexEntries.size();
        }

        System.out.println("[diff] reading both bodies for the changed and added methods");
        Set<String> wanted = new TreeSet<>(changed);
        wanted.addAll(added);
        Map<String, List<List<String>>> beforeBodies = bodiesOf(clean, changed);
        Map<String, List<List<String>>> afterBodies = bodiesOf(patched, wanted);

        // Every method the patch wrote or touched, the host's and the bundle's alike: each
        // definition of it the clean build doesn't have.
        System.out.println("[diff] checking branches, invokes, parameters and try ranges of "
                + (changed.size() + added.size()) + " methods");
        Set<String> structuralWanted = new TreeSet<>(changed);
        structuralWanted.addAll(added);
        Map<String, List<String>> fresh = new HashMap<>();
        for (String s : structuralWanted) fresh.put(s, without(prints(after.get(s)), prints(before.get(s))));
        Map<String, List<String>> structural = structuralPass(patched, clean, structuralWanted, fresh, contracts);
        int structuralCount = 0;
        for (Map.Entry<String, List<String>> e : structural.entrySet()) {
            for (String finding : e.getValue()) {
                structuralCount++;
                System.out.println("[diff] FAIL: " + finding
                        + (e.getKey().equals("contract") ? "" : "  in " + e.getKey()));
            }
        }
        System.out.println("[diff] structural findings: " + structuralCount);
        problems += structuralCount;

        PrintWriter report = new PrintWriter(args[2], "UTF-8");
        try {
            report.println("Methods a patched APK does not share with the clean build it came from.");
            report.println("clean:   " + clean.getAbsolutePath());
            report.println("patched: " + patched.getAbsolutePath());
            report.println("removal allowlist: " + allowlistFile.getAbsolutePath());
            report.println("contracts: " + (contractFile == null ? "none" : contractFile.getAbsolutePath()));
            report.println("changed=" + changed.size() + " added=" + added.size()
                    + " (own=" + ownAdded.size() + ") removed=" + removed.size()
                    + " removedDex=" + removedDexEntries.size());
            report.println();

            report.println("Structural findings: " + structuralCount);
            for (Map.Entry<String, List<String>> e : structural.entrySet()) {
                report.println("  " + e.getKey());
                for (String finding : e.getValue()) report.println("    FAIL  " + finding);
            }
            report.println();

            report.println("Removed methods:");
            for (String method : removed) {
                report.println((allowlist.methods.contains(method) ? "  allowed " : "  FAIL    ") + method);
            }
            report.println("Removed DEX entries:");
            for (String entry : removedDexEntries) {
                report.println((allowlist.dexEntries.contains(entry) ? "  allowed " : "  FAIL    ") + entry);
            }
            report.println("Stale removal allowlist entries:");
            for (String method : staleAllowedMethods) report.println("  FAIL    method " + method);
            for (String entry : staleAllowedDexEntries) report.println("  FAIL    dex " + entry);
            report.println();

            int overRegister = 0;
            int unreadable = 0;

            for (String s : changed) {
                // The definitions both builds have, body for body, are copies in other dex entries
                // the patch left alone. What's left on each side is paired in dex entry order.
                List<List<String>> cleanBodies = beforeBodies.getOrDefault(s, List.of());
                List<List<String>> patchedBodies = afterBodies.getOrDefault(s, List.of());
                List<List<String>> befores = without(cleanBodies, patchedBodies);
                List<List<String>> afters = without(patchedBodies, cleanBodies);
                int pairs = Math.max(befores.size(), afters.size());
                for (int k = 0; k < pairs; k++) {
                    List<String> b = k < befores.size() ? befores.get(k) : List.of();
                    List<String> a = k < afters.size() ? afters.get(k) : List.of();
                    int regsBefore = registersOf(b), regsAfter = registersOf(a);
                    report.println("==== " + s + (pairs == 1 ? "" : "  (definition " + (k + 1) + " of " + pairs + " that differ)"));
                    report.println("     registers " + regsBefore + " -> " + regsAfter
                            + ", instructions " + Math.max(0, b.size() - 1) + " -> " + Math.max(0, a.size() - 1));
                    if (regsAfter < 0 && !a.isEmpty()) {
                        // Nothing to hold the injected lines to. Silently skipping this was a hole:
                        // any method the second pass failed to render passed the check by default.
                        report.println("  !  no register count could be read for this method");
                        unreadable++;
                    }
                    List<String> onlyAfter = minus(a, b);
                    for (String line : minus(b, a)) report.println("  -  " + line);
                    for (String line : onlyAfter) {
                        int high = highestRegister(line);
                        boolean bad = high >= 0 && regsAfter >= 0 && high >= regsAfter;
                        if (bad) overRegister++;
                        report.println("  +  " + line + (bad ? "   <<< REGISTER >= registerCount" : ""));
                    }
                    report.println();
                }
            }

            // Added methods are where registers are chosen by hand rather than reused from the
            // host, which is exactly where an out-of-range one would come from: the extension's,
            // and the helpers a patch adds to one of Facebook's own classes (the story, reel and
            // video downloads each add one). Only the extension's were read here until 2026-09-26.
            // Each definition of one is read, in whichever dex entry it landed.
            for (String s : added) {
                for (List<String> a : afterBodies.getOrDefault(s, List.of())) {
                    int regsAfter = registersOf(a);
                    if (a.isEmpty()) continue;
                    List<String> offending = new ArrayList<>();
                    for (String line : a) {
                        if (line.startsWith("#")) continue;
                        int high = highestRegister(line);
                        if (high >= 0 && regsAfter >= 0 && high >= regsAfter) offending.add(line);
                    }
                    if (regsAfter < 0) {
                        report.println("==== added " + s);
                        report.println("  !  no register count could be read for this method");
                        unreadable++;
                    }
                    if (offending.isEmpty()) continue;
                    overRegister += offending.size();
                    report.println("==== added " + s);
                    report.println("     registers " + regsAfter);
                    for (String line : offending) {
                        report.println("  +  " + line + "   <<< REGISTER >= registerCount");
                    }
                    report.println();
                }
            }

            report.println("Lines naming a register at or above the method's register count: " + overRegister);
            report.println("Methods whose register count could not be read: " + unreadable);
            System.out.println("[diff] methods whose register count could not be read: " + unreadable);
            System.out.println("[diff] injected lines naming an out-of-range register: " + overRegister);
            if (unreadable != 0) problems++;
            if (overRegister != 0) problems++;
        } finally {
            report.close();
        }
        System.out.println("[diff] report written to " + args[2]);
        System.exit(problems == 0 ? 0 : 1);
    }

    /**
     * The register count the body declares, or -1 when the body was not read at all.
     *
     * <p>Zero is a real answer: a static method with no arguments and no locals declares no
     * registers, and R8's synthetic lambda bridges are full of them. Treating zero as "unknown"
     * flagged twenty-one of the bundle's own methods as unreadable when nothing was wrong.
     */
    private static int registersOf(List<String> body) {
        if (body.isEmpty() || !body.get(0).startsWith("# registers=")) return -1;
        return Integer.parseInt(body.get(0).substring("# registers=".length()));
    }

    /** Lines of a that are not in b, counting duplicates. */
    private static List<String> minus(List<String> a, List<String> b) {
        Map<String, Integer> pool = new HashMap<>();
        for (String s : b) pool.merge(s, 1, Integer::sum);
        List<String> out = new ArrayList<>();
        for (String s : a) {
            Integer left = pool.get(s);
            if (left != null && left > 0) pool.put(s, left - 1);
            else if (!s.startsWith("# registers=")) out.add(s);
        }
        return out;
    }

    /** The highest register the instruction touched, read back from the marker render() wrote. */
    private static int highestRegister(String line) {
        int at = line.lastIndexOf("|maxreg=");
        if (at < 0) return -1;
        return Integer.parseInt(line.substring(at + "|maxreg=".length()).trim());
    }
}
