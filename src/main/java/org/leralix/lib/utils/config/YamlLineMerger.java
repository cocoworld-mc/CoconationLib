package org.leralix.lib.utils.config;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Completes missing YAML keys by inserting source fragments; existing admin lines are never removed. */
final class YamlLineMerger {
    private YamlLineMerger() { }

    static MappingNode document(String yaml) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Node root = new Yaml(new SafeConstructor(options)).compose(new StringReader(yaml));
        if (root == null) {
            return null;
        }
        if (!(root instanceof MappingNode mapping)) {
            throw new IllegalArgumentException("Configuration root must be a YAML mapping");
        }
        checkKeys(root, Collections.newSetFromMap(new IdentityHashMap<>()),
                Collections.newSetFromMap(new IdentityHashMap<>()));
        return mapping;
    }

    private static void checkKeys(Node node, Set<Node> visited, Set<Node> ancestors) {
        if (ancestors.contains(node)) {
            throw new IllegalArgumentException("Recursive YAML aliases are not configuration values");
        }
        if (!visited.add(node)) {
            return;
        }
        ancestors.add(node);
        if (node instanceof MappingNode map) {
            Set<String> keys = new HashSet<>();
            for (NodeTuple tuple : map.getValue()) {
                String key = key(tuple);
                if (!keys.add(key)) {
                    throw new IllegalArgumentException("Duplicate YAML key: " + key);
                }
                checkKeys(tuple.getValueNode(), visited, ancestors);
            }
        } else if (node instanceof SequenceNode sequence) {
            sequence.getValue().forEach(child -> checkKeys(child, visited, ancestors));
        }
        ancestors.remove(node);
    }

    static Optional<List<String>> merge(List<String> reference, List<String> current,
                                        Collection<String> blacklist) {
        MappingNode base = document(String.join("\n", reference));
        MappingNode actual = document(String.join("\n", current));
        if (base == null || reference.equals(current)) {
            return Optional.empty();
        }
        if (actual == null) {
            List<String> result = new ArrayList<>(current);
            result.addAll(reference);
            return Optional.of(result);
        }
        List<Insertion> additions = new ArrayList<>();
        complete(base, actual, "", reference, current, blacklist, additions,
                Collections.newSetFromMap(new IdentityHashMap<>()));
        // Template headers can be added without rewriting existing administrator comments.
        int firstKey = base.getValue().isEmpty() ? 0 : start(base.getValue().get(0));
        if (firstKey > 0) {
            List<String> header = reference.subList(0, firstKey);
            if (header.stream().allMatch(line -> line.isBlank() || line.stripLeading().startsWith("#"))
                    && Collections.indexOfSubList(current, header) < 0) {
                additions.add(0, new Insertion(0, new ArrayList<>(header)));
            }
        }
        if (additions.isEmpty()) {
            return Optional.empty();
        }
        additions.sort(Comparator.comparingInt(Insertion::index));
        List<String> result = new ArrayList<>();
        int cursor = 0;
        for (Insertion insertion : additions) {
            result.addAll(current.subList(cursor, insertion.index()));
            result.addAll(insertion.lines());
            cursor = insertion.index();
        }
        result.addAll(current.subList(cursor, current.size()));
        SafeYamlFile.validate(String.join("\n", result), "merged configuration");
        return Optional.of(result);
    }

    private static void complete(MappingNode base, MappingNode actual, String parent,
                                 List<String> reference, List<String> current, Collection<String> blacklist,
                                 List<Insertion> additions, Set<Node> visited) {
        // Flow/anchored/inherited mappings are administrator values, not missing defaults.
        if (actual.getFlowStyle() == DumperOptions.FlowStyle.FLOW || actual.getAnchor() != null
                || actual.getValue().stream().anyMatch(tuple -> key(tuple).equals("<<"))
                || !visited.add(actual)) {
            return;
        }
        Map<String, NodeTuple> existing = entries(actual);
        List<NodeTuple> defaults = base.getValue();
        for (int i = 0; i < defaults.size(); i++) {
            NodeTuple template = defaults.get(i);
            String name = key(template);
            String path = parent.isEmpty() ? name : parent + "." + name;
            NodeTuple present = existing.get(name);
            if (present == null) {
                int insertion = end(actual, current.size());
                for (int next = i + 1; next < defaults.size(); next++) {
                    NodeTuple sibling = existing.get(key(defaults.get(next)));
                    if (sibling != null) {
                        insertion = start(sibling);
                        break;
                    }
                }
                int from = start(template);
                int until = end(template.getValueNode(), reference.size());
                int indentation = actual.getValue().isEmpty() ? 0
                        : actual.getValue().get(0).getKeyNode().getStartMark().getColumn();
                int shift = indentation - template.getKeyNode().getStartMark().getColumn();
                List<String> block = new ArrayList<>();
                for (String line : reference.subList(from, until)) {
                    block.add(reindent(line, shift));
                }
                additions.add(new Insertion(insertion, block));
            } else if (!blocked(path, blacklist)
                    && template.getValueNode() instanceof MappingNode defaultMap
                    && present.getValueNode() instanceof MappingNode currentMap) {
                complete(defaultMap, currentMap, path, reference, current, blacklist, additions, visited);
            }
        }
    }

    private static Map<String, NodeTuple> entries(MappingNode node) {
        Map<String, NodeTuple> entries = new LinkedHashMap<>();
        for (NodeTuple tuple : node.getValue()) {
            entries.put(key(tuple), tuple);
        }
        return entries;
    }

    private static boolean blocked(String path, Collection<String> blacklist) {
        return blacklist.stream().anyMatch(section -> path.equals(section) || path.startsWith(section + "."));
    }

    private static String key(NodeTuple tuple) {
        if (!(tuple.getKeyNode() instanceof ScalarNode scalar)) {
            throw new IllegalArgumentException("Configuration keys must be scalar YAML values");
        }
        return scalar.getValue();
    }

    private static int start(NodeTuple tuple) {
        return tuple.getKeyNode().getStartMark().getLine();
    }

    private static int end(Node node, int maximum) {
        int line = node.getEndMark().getLine();
        if (node.getEndMark().getColumn() != 0) {
            line++;
        }
        return Math.min(line, maximum);
    }

    private static String reindent(String line, int shift) {
        if (line.isBlank()) {
            return line;
        }
        return shift >= 0 ? " ".repeat(shift) + line : line.substring(Math.min(-shift, ConfigUtil.getNbIndentation(line)));
    }

    private record Insertion(int index, List<String> lines) { }
}
