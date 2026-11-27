package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.TargetKey;

import java.util.AbstractList;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Pure-Java resolution of preset {@code includes} (spec 1.4.0 F-140-6 / N17), shared across
 * all Minecraft versions via {@code common/src/main/java}.
 *
 * <p>The resolver receives the raw (not yet expanded) item lists and the {@code includes}
 * lists of every loaded preset, expands each preset depth-first in include order, appends the
 * preset's own items, and removes duplicates while keeping the first occurrence. A cyclic
 * include branch is cut with a warning (including a self-reference); a reference to an unknown
 * preset ID is skipped with a warning. The caller decides what to do with a preset whose
 * expansion ends up empty (the per-version {@code Presets} drops it, matching the historical
 * "0 valid items" rule).
 *
 * <p>Must remain Java 21 compatible and free of Minecraft/NeoForge dependencies.
 */
public final class PresetIncludesLogic {
    /**
     * Result of one includes resolution.
     *
     * @param expandedItems    expanded items per preset ID, in the input ID order (may be empty)
     * @param warnings         human-readable warnings for cycles and unknown IDs, in discovery order
     * @param problemsByPreset problems encountered while expanding each root preset (missing IDs,
     *                         cycles, blank IDs); F-140-20 uses this to reject the whole preset in
     *                         strict mode.
     */
    public record Result(Map<String, List<TargetKey>> expandedItems, List<String> warnings,
                         Map<String, List<String>> problemsByPreset) {
        public Result {
            Map<String, List<TargetKey>> copy = new LinkedHashMap<>();
            if (expandedItems != null) {
                for (Map.Entry<String, List<TargetKey>> entry : expandedItems.entrySet()) {
                    if (entry.getKey() != null) {
                        copy.put(entry.getKey(), entry.getValue() == null ? List.of() : List.copyOf(entry.getValue()));
                    }
                }
            }
            expandedItems = Collections.unmodifiableMap(copy);
            // LazyMessages renders path-specific cycle warnings only when read. Keeping the
            // instance preserves the immutable-list contract (it is an unmodifiable List).
            warnings = warnings == null ? List.of()
                    : warnings instanceof LazyMessages ? warnings : List.copyOf(warnings);
            Map<String, List<String>> problems = new LinkedHashMap<>();
            if (problemsByPreset != null) {
                for (Map.Entry<String, List<String>> entry : problemsByPreset.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null && !entry.getValue().isEmpty()) {
                        problems.put(entry.getKey(),
                                entry.getValue() instanceof LazyMessages ? entry.getValue() : List.copyOf(entry.getValue()));
                    }
                }
            }
            problemsByPreset = Collections.unmodifiableMap(problems);
        }

        /** Expanded items for {@code id}, or an empty list when {@code id} was not an input. */
        public List<TargetKey> items(String id) {
            List<TargetKey> items = expandedItems.get(id);
            return items != null ? items : List.of();
        }

        /** Problems encountered while expanding {@code id} (empty when its expansion is clean). */
        public List<String> problems(String id) {
            List<String> problems = problemsByPreset.get(id);
            return problems != null ? problems : List.of();
        }
    }

    private PresetIncludesLogic() {
    }

    /**
     * SF-5: diagnostic paths are stack-relative and can be exponential in a strongly connected
     * component, so their generation is bounded separately from item expansion. Item expansion
     * itself is iterative and visits each node at most once, so it needs no bound.
     */
    private static final int MAX_DIAGNOSTIC_STATES = 4_000;
    private static final int MAX_DIAGNOSTIC_DEPTH = 4096;
    /** A bounded diagnostic tree can still describe exponentially many distinct paths. */
    private static final int MAX_RENDERED_MESSAGES = 2_000;
    private static final String TRUNCATION_MESSAGE =
            "preset include diagnostics truncated (state limit)";

    /** Mutable budget for stack-relative diagnostics within one {@link #resolve} call. */
    private static final class DiagnosticBudget {
        int states;
        boolean truncated;
    }

    /**
     * Expands every preset in {@code rawItemsById}.
     *
     * @param rawItemsById  preset ID to the preset's own (not yet expanded) items; iteration order
     *                      determines the result order
     * @param includesById  preset ID to its include IDs; missing entries mean "no includes"
     */
    public static Result resolve(
            Map<String, ? extends List<TargetKey>> rawItemsById,
            Map<String, ? extends List<String>> includesById) {
        Map<String, List<TargetKey>> rawItems = new LinkedHashMap<>();
        if (rawItemsById != null) {
            for (Map.Entry<String, ? extends List<TargetKey>> entry : rawItemsById.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                rawItems.put(entry.getKey(), entry.getValue() == null ? List.of() : entry.getValue());
            }
        }
        Map<String, List<String>> includes = new LinkedHashMap<>();
        if (includesById != null) {
            for (Map.Entry<String, ? extends List<String>> entry : includesById.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    includes.put(entry.getKey(), entry.getValue());
                }
            }
        }

        // #149: a node's diagnostics only depend on the ancestors it can actually reach. Precompute
        // that reachability once so a node whose subtree contains a cycle can still be memoized
        // under the (usually empty) relevant-ancestor key instead of disabling memoization for
        // every ancestor of a diamond with a cycle at the bottom.
        Map<String, Set<String>> reachable = computeReachability(rawItems, includes);

        Map<String, List<TargetKey>> expanded = new LinkedHashMap<>();
        List<DiagnosticRoot> allRoots = new ArrayList<>();
        Map<String, List<String>> problemsByPreset = new LinkedHashMap<>();
        DiagnosticBudget budget = new DiagnosticBudget();
        for (String id : rawItems.keySet()) {
            // SF-5: items are a stack-free post-order walk of the include graph; only diagnostics
            // remain stack-relative, and they are bounded by DiagnosticBudget.
            List<TargetKey> items = expandItems(id, rawItems, includes);
            // The budget is per root: a deep but sparse chain visited once per root must not
            // accumulate toward the dense-graph cap (existing depth-200/256 tests).
            budget.states = 0;
            budget.truncated = false;
            Expansion root = expandDiagnostics(id, rawItems, includes, new LinkedHashSet<>(),
                    new HashMap<>(), reachable, budget);
            if (budget.truncated) {
                List<DiagNode> nodes = new ArrayList<>(root.diagnostics());
                // Put the notice first: rendering is itself bounded, so a late notice might
                // never be reached when a dense graph describes many cycle paths.
                nodes.add(0, new MessageNode(Diagnostic.fixed(TRUNCATION_MESSAGE)));
                root = Expansion.of(List.copyOf(nodes));
            }
            expanded.put(id, items);
            DiagnosticRoot diagnosticRoot = new DiagnosticRoot(root, List.of());
            allRoots.add(diagnosticRoot);
            if (root.hasDiagnostics()) {
                problemsByPreset.put(id, new LazyMessages(List.of(diagnosticRoot)));
            }
        }
        return new Result(expanded, new LazyMessages(allRoots), problemsByPreset);
    }

    /** Transitive include reachability for every ID mentioned in the graph. */
    private static Map<String, Set<String>> computeReachability(
            Map<String, List<TargetKey>> rawItems, Map<String, List<String>> includes) {
        Set<String> allIds = new LinkedHashSet<>(rawItems.keySet());
        allIds.addAll(includes.keySet());
        for (List<String> children : includes.values()) {
            for (String child : children) {
                if (child != null && !child.isBlank()) {
                    allIds.add(child.trim());
                }
            }
        }
        Map<String, Set<String>> result = new HashMap<>();
        for (String id : allIds) {
            Set<String> seen = new HashSet<>();
            Deque<String> work = new ArrayDeque<>();
            addChildren(includes.getOrDefault(id, List.of()), work);
            while (!work.isEmpty()) {
                String current = work.removeFirst();
                if (!seen.add(current)) {
                    continue;
                }
                addChildren(includes.getOrDefault(current, List.of()), work);
            }
            result.put(id, Set.copyOf(seen));
        }
        return result;
    }

    private static void addChildren(List<String> children, Deque<String> out) {
        for (String child : children) {
            if (child != null && !child.isBlank()) {
                out.addLast(child.trim());
            }
        }
    }

    /** Stack-relative diagnostic subtree of one node; items are computed by {@link #expandItems}. */
    private record Expansion(List<DiagNode> diagnostics, boolean hasDiagnostics) {
        private static final Expansion EMPTY = Expansion.of(List.of());

        static Expansion of(List<DiagNode> diagnostics) {
            boolean present = false;
            for (DiagNode node : diagnostics) {
                if (node instanceof MessageNode || ((ChildNode) node).child().hasDiagnostics()) {
                    present = true;
                    break;
                }
            }
            return new Expansion(diagnostics, present);
        }
    }

    /** Memo key: a node plus the ordered ancestors that are reachable from it. */
    private record MemoKey(String id, List<String> relevantAncestors) {
    }

    /**
     * A diagnostic in stack-relative form. Cycle messages include the whole DFS path, so the
     * path prefix is supplied when the diagnostic is finally rendered; that makes an identical
     * subtree reusable under a different ancestor stack without changing any message.
     */
    private record Diagnostic(String fixedText, List<String> relativePath, String repeatedId) {
        static Diagnostic fixed(String text) {
            return new Diagnostic(text, List.of(), null);
        }

        static Diagnostic cycle(String id) {
            return new Diagnostic(null, List.of(), id);
        }

        String render(List<String> callerStack) {
            if (fixedText != null) {
                return fixedText;
            }
            List<String> path = new ArrayList<>(callerStack);
            path.addAll(relativePath);
            path.add(repeatedId);
            return "preset include cycle detected: " + String.join(" -> ", path) + " (branch cut)";
        }

        /** Rebase this diagnostic from a child's stack onto its parent's stack. */
        Diagnostic prepend(String id) {
            if (fixedText != null) {
                return this;
            }
            List<String> path = new ArrayList<>();
            path.add(id);
            path.addAll(relativePath);
            return new Diagnostic(null, List.copyOf(path), repeatedId);
        }
    }

    /** One emitted diagnostic; {@code message} is rendered on demand. */
    private record Pending(List<String> callerStack, Diagnostic diagnostic) {
        String render() {
            return diagnostic.render(callerStack);
        }
    }

    /** Diagnostic tree node: a message or a (shared) child expansion with its stack prefix. */
    private sealed interface DiagNode permits MessageNode, ChildNode {
    }

    private record MessageNode(Diagnostic diagnostic) implements DiagNode {
    }

    private record ChildNode(Expansion child, String prefix) implements DiagNode {
    }

    private record DiagnosticRoot(Expansion expansion, List<String> callerStack) {
    }

    /** An item-DFS frame: node id, its unique include children, and the next child to visit. */
    private static final class ItemFrame {
        final String id;
        final List<String> children;
        int next;

        ItemFrame(String id, List<String> children) {
            this.id = id;
            this.children = children;
        }
    }

    /**
     * SF-5: stack-free item expansion for one root. The old algorithm emitted a node's own items
     * when its DFS call completed, cut nodes already on the stack, and de-duplicated by first
     * occurrence. Marking a node as visited when it is first pushed — whether it is still on the
     * stack or already completed — produces exactly the same first-occurrence order: a re-visit
     * can only re-emit items the first visit already emitted. Each node is pushed at most once, so
     * this is O(V + E), iterative, and completely independent of the DFS stack.
     */
    private static List<TargetKey> expandItems(
            String root, Map<String, List<TargetKey>> rawItems, Map<String, List<String>> includes) {
        LinkedHashSet<TargetKey> resolved = new LinkedHashSet<>();
        Set<String> visited = new HashSet<>();
        Deque<ItemFrame> stack = new ArrayDeque<>();
        pushItemFrame(root, includes, visited, stack);
        while (!stack.isEmpty()) {
            ItemFrame frame = stack.peek();
            if (frame.next < frame.children.size()) {
                String child = frame.children.get(frame.next++);
                if (!visited.contains(child) && rawItems.containsKey(child)) {
                    pushItemFrame(child, includes, visited, stack);
                }
            } else {
                for (TargetKey item : rawItems.get(frame.id)) {
                    if (item != null) {
                        resolved.add(item);
                    }
                }
                stack.pop();
            }
        }
        return List.copyOf(resolved);
    }

    private static void pushItemFrame(String id, Map<String, List<String>> includes,
                                      Set<String> visited, Deque<ItemFrame> stack) {
        visited.add(id);
        stack.push(new ItemFrame(id, uniqueChildren(includes.getOrDefault(id, List.of()))));
    }

    /** Trimmed, non-blank include IDs in declaration order, with repeats removed (#102). */
    private static List<String> uniqueChildren(List<String> includes) {
        Set<String> seen = new LinkedHashSet<>();
        for (String child : includes) {
            if (child != null && !child.isBlank()) {
                seen.add(child.trim());
            }
        }
        return List.copyOf(seen);
    }

    /**
     * Depth-first generation of stack-relative diagnostics only. The memo key still distinguishes
     * the ancestors reachable from a node, so a sparse diamond shares one diagnostic tree across
     * all paths, but the work is bounded by {@link DiagnosticBudget}: in a dense strongly connected
     * component every node reaches every ancestor, so the old key made one expansion per simple
     * path (factorial) even though item expansion is linear.
     */
    private static Expansion expandDiagnostics(
            String id,
            Map<String, List<TargetKey>> rawItems,
            Map<String, List<String>> includes,
            Set<String> stack,
            Map<MemoKey, Expansion> memo,
            Map<String, Set<String>> reachable,
            DiagnosticBudget budget) {
        if (stack.size() >= MAX_DIAGNOSTIC_DEPTH || budget.states >= MAX_DIAGNOSTIC_STATES) {
            budget.truncated = true;
            return Expansion.EMPTY;
        }

        List<String> ancestors = new ArrayList<>(stack);
        List<String> relevant = new ArrayList<>();
        Set<String> fromHere = reachable.getOrDefault(id, Set.of());
        for (String ancestor : ancestors) {
            if (fromHere.contains(ancestor)) {
                relevant.add(ancestor);
            }
        }
        MemoKey key = new MemoKey(id, List.copyOf(relevant));
        Expansion cached = memo.get(key);
        if (cached != null) {
            return cached;
        }
        budget.states++;

        if (!rawItems.containsKey(id)) {
            return Expansion.of(List.of(new MessageNode(
                    Diagnostic.fixed("unknown preset include '" + id + "' (skipped)"))));
        }
        if (stack.contains(id)) {
            return Expansion.of(List.of(new MessageNode(Diagnostic.cycle(id))));
        }

        List<String> path = new ArrayList<>(ancestors);
        path.add(id);
        List<DiagNode> diagnostics = new ArrayList<>();
        Set<String> seenIncludes = new LinkedHashSet<>();
        for (String rawIncludeId : includes.getOrDefault(id, List.of())) {
            if (rawIncludeId == null || rawIncludeId.isBlank()) {
                diagnostics.add(new MessageNode(
                        Diagnostic.fixed("blank preset include id in '" + id + "' (skipped)")));
                continue;
            }
            String includeId = rawIncludeId.trim();
            if (!seenIncludes.add(includeId)) {
                continue;
            }
            Expansion child = expandDiagnostics(includeId, rawItems, includes, stackAsSet(path),
                    memo, reachable, budget);
            diagnostics.add(new ChildNode(child, id));
        }
        Expansion result = Expansion.of(List.copyOf(diagnostics));
        memo.put(key, result);
        return result;
    }

    /** The DFS path is kept as a LinkedHashSet for O(1) cycle membership checks. */
    private static Set<String> stackAsSet(List<String> path) {
        return new LinkedHashSet<>(path);
    }

    /** Lazily walks a diagnostic tree, yielding messages in discovery order. */
    private static final class DiagnosticIterator implements Iterator<String> {
        private record Frame(Expansion expansion, List<String> callerStack, int index) {
        }

        private final Deque<Frame> frames = new ArrayDeque<>();
        private String next;

        DiagnosticIterator(DiagnosticRoot root) {
            frames.push(new Frame(root.expansion(), root.callerStack(), 0));
        }

        @Override
        public boolean hasNext() {
            if (next != null) {
                return true;
            }
            while (!frames.isEmpty()) {
                Frame frame = frames.pop();
                List<DiagNode> nodes = frame.expansion().diagnostics();
                if (frame.index() >= nodes.size()) {
                    continue;
                }
                frames.push(new Frame(frame.expansion(), frame.callerStack(), frame.index() + 1));
                DiagNode node = nodes.get(frame.index());
                if (node instanceof MessageNode message) {
                    next = message.diagnostic().render(frame.callerStack());
                    return true;
                }
                ChildNode child = (ChildNode) node;
                List<String> childStack = new ArrayList<>(frame.callerStack());
                childStack.add(child.prefix());
                frames.push(new Frame(child.child(), List.copyOf(childStack), 0));
            }
            return false;
        }

        @Override
        public String next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            String message = next;
            next = null;
            return message;
        }
    }

    /**
     * An immutable view of the diagnostics, deduplicated per root by message text in discovery
     * order exactly as {@code report(...)} did before. Rendering is deferred so the exponential
     * number of path-specific cycle messages in a deep diamond does not have to be materialized
     * just to expand items.
     */
    private static final class LazyMessages extends AbstractList<String> {
        private final List<DiagnosticRoot> roots;
        private final boolean empty;
        private final List<String> rendered = new ArrayList<>();
        private final Iterator<String> materializer;

        LazyMessages(List<DiagnosticRoot> roots) {
            this.roots = List.copyOf(roots);
            boolean any = false;
            for (DiagnosticRoot root : roots) {
                any |= root.expansion().hasDiagnostics();
            }
            this.empty = !any;
            this.materializer = newIterator();
        }

        private Iterator<String> newIterator() {
            if (empty) {
                return Collections.emptyIterator();
            }
            return new Iterator<>() {
                private int rootIndex;
                private Iterator<String> current = Collections.emptyIterator();
                private Set<String> seen = new HashSet<>();
                private String next;
                private boolean done;
                private int emitted;

                private void advance() {
                    while (next == null && !done) {
                        if (emitted >= MAX_RENDERED_MESSAGES) {
                            // SF-5: a bounded tree can still encode exponentially many paths;
                            // callers (e.g. Presets.reloadFrom) iterate every warning.
                            done = true;
                        } else if (current.hasNext()) {
                            String message = current.next();
                            if (seen.add(message)) {
                                emitted++;
                                next = message;
                            }
                        } else if (rootIndex < roots.size()) {
                            seen = new HashSet<>();
                            current = new DiagnosticIterator(roots.get(rootIndex++));
                        } else {
                            done = true;
                        }
                    }
                }

                @Override
                public boolean hasNext() {
                    advance();
                    return next != null;
                }

                @Override
                public String next() {
                    advance();
                    if (next == null) {
                        throw new NoSuchElementException();
                    }
                    String message = next;
                    next = null;
                    return message;
                }
            };
        }

        @Override
        public String get(int index) {
            while (rendered.size() <= index) {
                if (!materializer.hasNext()) {
                    throw new IndexOutOfBoundsException(index);
                }
                rendered.add(materializer.next());
            }
            return rendered.get(index);
        }

        @Override
        public int size() {
            if (empty) {
                return 0;
            }
            while (materializer.hasNext()) {
                rendered.add(materializer.next());
            }
            return rendered.size();
        }

        @Override
        public boolean isEmpty() {
            return empty;
        }

        @Override
        public Iterator<String> iterator() {
            return newIterator();
        }
    }
}
