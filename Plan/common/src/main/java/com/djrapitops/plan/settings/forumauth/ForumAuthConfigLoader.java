/*
 *  This file is part of Player Analytics (Plan).
 *
 *  Plan is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Lesser General Public License v3 as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  Plan is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with Plan. If not, see <https://www.gnu.org/licenses/>.
 */
package com.djrapitops.plan.settings.forumauth;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.djrapitops.plan.settings.forumauth.ForumAuthConfig.invalid;

/** Strict physical parsing and explicit sequential migrations: 0 -> 1 -> 2. */
final class ForumAuthConfigLoader {

    private static final String RESOURCE = "/assets/plan/forum-auth.yml";
    private static final int MAX_BYTES = 1024 * 1024;
    private static final Set<Tag> SCALAR_TAGS = Set.of(Tag.STR, Tag.BOOL, Tag.INT, Tag.FLOAT);

    @FunctionalInterface
    interface AtomicWriter {
        void write(Path target, byte[] contents, byte[] expected) throws IOException;
    }

    private ForumAuthConfigLoader() { }

    static ForumAuthConfig load(Path file) throws IOException {
        return load(file, ForumAuthFiles::replace);
    }

    static ForumAuthConfig load(Path file, AtomicWriter writer) throws IOException {
        try {
            return prepare(file.toAbsolutePath().normalize(), writer);
        } catch (IOException failure) {
            if (failure.getMessage() != null && failure.getMessage().startsWith("forum-auth.yml blocked")) throw failure;
            throw invalid("could not read or safely install the configuration; previous settings remain active");
        } catch (RuntimeException failure) {
            throw invalid("invalid YAML, unsupported configuration value or unavailable safe filesystem operation");
        }
    }

    private static ForumAuthConfig prepare(Path file, AtomicWriter writer) throws IOException {
        byte[] bundled = bundledBytes();
        MappingNode template = parse(bundled);
        if (version(template, decode(bundled)) != ForumAuthConfig.CURRENT_VERSION) {
            throw invalid("bundled template has an invalid config-version");
        }
        Path parent = file.getParent();
        if (parent == null) throw invalid("configuration must have a parent directory");
        Files.createDirectories(parent);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            settings(template, "created");
            writer.write(file, bundled, null);
            return activate(file, bundled, "created");
        }
        byte[] source = read(file);
        MappingNode installed = parse(source);
        int sourceVersion = version(installed, decode(source));
        if (sourceVersion > ForumAuthConfig.CURRENT_VERSION) {
            throw invalid("installed config-version " + sourceVersion + " is newer than supported");
        }
        if (sourceVersion == ForumAuthConfig.CURRENT_VERSION) {
            ForumAuthConfig result = settings(overlay(template, installed), "current");
            checkSource(file, source);
            return result;
        }

        for (int migrating = sourceVersion; migrating < ForumAuthConfig.CURRENT_VERSION; migrating++) {
            switch (migrating) {
                case 0 -> { /* 0 -> 1 adopts the independent schema without changing existing choices. */ }
                case 1 -> {
                    // 1 -> 2 raises the permitted lifetime; preserve the old implicit 15-minute
                    // duration as well as explicit administrator values. Only fresh files get 14 days.
                    if (installed.getValue().stream().noneMatch(tuple -> "session-seconds".equals(key(tuple)))) {
                        installed.getValue().add(new NodeTuple(
                                new ScalarNode(Tag.STR, "session-seconds", null, null, DumperOptions.ScalarStyle.PLAIN),
                                new ScalarNode(Tag.INT, "900", null, null, DumperOptions.ScalarStyle.PLAIN)));
                    }
                }
                default -> throw invalid("missing sequential schema migration");
            }
        }
        // The current template replaces the version only after every sequential edge has run.
        installed.getValue().removeIf(tuple -> "config-version".equals(key(tuple)));
        MappingNode candidate = overlay(template, installed);
        settings(candidate, "migrated");
        byte[] replacement = serialize(candidate);
        settings(parse(replacement), "migrated");
        checkSource(file, source);
        ForumAuthFiles.backup(file, source, sourceVersion);
        writer.write(file, replacement, source);
        return activate(file, replacement, "migrated");
    }

    private static ForumAuthConfig activate(Path file, byte[] expected, String state) throws IOException {
        byte[] actual = read(file);
        if (!Arrays.equals(expected, actual)) throw invalid("configuration changed during activation");
        MappingNode physical = parse(actual);
        if (version(physical, decode(actual)) != ForumAuthConfig.CURRENT_VERSION) {
            throw invalid("installed config-version changed during activation");
        }
        ForumAuthConfig settings = settings(physical, state);
        checkSource(file, actual);
        return settings;
    }

    static void checkSource(Path file, byte[] expected) throws IOException {
        if (!Arrays.equals(expected, read(file))) throw invalid("configuration changed while a snapshot was prepared");
    }

    private static byte[] read(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_BYTES) {
            throw invalid("configuration must be a regular file of at most one MiB");
        }
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length > MAX_BYTES) throw invalid("configuration exceeds one MiB");
        return bytes;
    }

    private static byte[] bundledBytes() throws IOException {
        try (InputStream stream = ForumAuthConfig.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) throw invalid("bundled configuration template is missing");
            return stream.readAllBytes();
        }
    }

    private static String decode(byte[] bytes) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static Yaml yaml() {
        LoaderOptions loader = new LoaderOptions();
        loader.setAllowDuplicateKeys(false);
        loader.setMaxAliasesForCollections(0);
        loader.setNestingDepthLimit(40);
        loader.setCodePointLimit(MAX_BYTES);
        loader.setProcessComments(true);
        DumperOptions dumper = new DumperOptions();
        dumper.setProcessComments(true);
        dumper.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        dumper.setIndent(2);
        return new Yaml(new SafeConstructor(loader), new Representer(dumper), dumper, loader);
    }

    private static MappingNode parse(byte[] bytes) throws IOException {
        Iterator<Node> documents = yaml().composeAll(new StringReader(decode(bytes))).iterator();
        if (!documents.hasNext()) throw invalid("configuration must contain one YAML mapping document");
        Node document = documents.next();
        if (documents.hasNext() || !(document instanceof MappingNode mapping)) {
            throw invalid("configuration must contain exactly one YAML mapping document");
        }
        inspect(mapping, Collections.newSetFromMap(new IdentityHashMap<>()));
        return mapping;
    }

    private static void inspect(Node node, Set<Node> seen) throws IOException {
        if (!seen.add(node) || node.getAnchor() != null) throw invalid("YAML anchors and aliases are not supported");
        if (node instanceof MappingNode mapping) {
            if (!Tag.MAP.equals(mapping.getTag())) throw invalid("unsupported YAML mapping tag");
            Set<String> keys = new HashSet<>();
            for (NodeTuple tuple : mapping.getValue()) {
                if (!(tuple.getKeyNode() instanceof ScalarNode key) || !Tag.STR.equals(key.getTag())
                        || key.getValue().isEmpty() || "<<".equals(key.getValue())) {
                    throw invalid("YAML keys must be nonempty strings and must not use merges");
                }
                if (!keys.add(key.getValue())) throw invalid("duplicate YAML mapping key");
                inspect(key, seen);
                inspect(tuple.getValueNode(), seen);
            }
        } else if (node instanceof SequenceNode sequence) {
            if (!Tag.SEQ.equals(sequence.getTag())) throw invalid("unsupported YAML sequence tag");
            for (Node child : sequence.getValue()) inspect(child, seen);
        } else if (!(node instanceof ScalarNode) || !SCALAR_TAGS.contains(node.getTag())) {
            throw invalid("YAML nulls and unsupported tags cannot be preserved safely");
        }
    }

    private static int version(MappingNode mapping, String source) throws IOException {
        for (NodeTuple tuple : mapping.getValue()) {
            if (!"config-version".equals(key(tuple))) continue;
            ScalarNode key = (ScalarNode) tuple.getKeyNode();
            if (!key.isPlain() || !"config-version".equals(physical(source, key))
                    || !(tuple.getValueNode() instanceof ScalarNode value) || !value.isPlain()
                    || !Tag.INT.equals(value.getTag()) || !physical(source, value).matches("0|[1-9][0-9]*")) {
                throw invalid("config-version must be an unquoted, untagged nonnegative decimal integer");
            }
            try {
                return Integer.parseInt(value.getValue());
            } catch (NumberFormatException ignored) {
                throw invalid("installed config-version exceeds the supported integer range");
            }
        }
        return 0;
    }

    private static String physical(String source, ScalarNode node) {
        int[] codePoints = source.codePoints().toArray();
        int start = node.getStartMark().getIndex();
        return new String(codePoints, start, node.getEndMark().getIndex() - start);
    }

    private static String key(NodeTuple tuple) {
        return ((ScalarNode) tuple.getKeyNode()).getValue();
    }

    private static MappingNode overlay(MappingNode template, MappingNode explicit) {
        Map<String, NodeTuple> remaining = new LinkedHashMap<>();
        for (NodeTuple tuple : explicit.getValue()) remaining.put(key(tuple), tuple);
        List<NodeTuple> result = new ArrayList<>();
        for (NodeTuple known : template.getValue()) {
            NodeTuple supplied = remaining.remove(key(known));
            if (supplied == null) {
                result.add(known);
                continue;
            }
            Node value = supplied.getValueNode();
            if (known.getValueNode() instanceof MappingNode defaultMap && value instanceof MappingNode suppliedMap) {
                value = overlay(defaultMap, suppliedMap);
            } else {
                clearComments(value);
            }
            value.setBlockComments(known.getValueNode().getBlockComments());
            value.setInLineComments(known.getValueNode().getInLineComments());
            value.setEndComments(known.getValueNode().getEndComments());
            result.add(new NodeTuple(known.getKeyNode(), value));
        }
        for (NodeTuple unknown : remaining.values()) {
            clearComments(unknown.getKeyNode());
            clearComments(unknown.getValueNode());
            result.add(unknown);
        }
        template.setValue(result);
        return template;
    }

    private static void clearComments(Node node) {
        node.setBlockComments(null);
        node.setInLineComments(null);
        node.setEndComments(null);
        if (node instanceof MappingNode mapping) {
            for (NodeTuple tuple : mapping.getValue()) {
                clearComments(tuple.getKeyNode());
                clearComments(tuple.getValueNode());
            }
        } else if (node instanceof SequenceNode sequence) {
            for (Node child : sequence.getValue()) clearComments(child);
        }
    }

    private static byte[] serialize(MappingNode mapping) {
        StringWriter writer = new StringWriter();
        yaml().serialize(mapping, writer);
        return writer.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static ForumAuthConfig settings(MappingNode mapping, String state) throws IOException {
        Map<String, Tag> types = Map.of("enabled", Tag.BOOL, "forum-url", Tag.STR,
                "client-id", Tag.STR, "client-secret", Tag.STR, "callback-url", Tag.STR,
                "session-seconds", Tag.INT, "recheck-seconds", Tag.INT, "timeout-seconds", Tag.INT);
        Map<String, String> values = new LinkedHashMap<>();
        for (NodeTuple tuple : mapping.getValue()) {
            String key = key(tuple);
            if (!types.containsKey(key)) continue;
            if (!(tuple.getValueNode() instanceof ScalarNode value) || !types.get(key).equals(value.getTag())) {
                throw invalid(key + " has an invalid value type");
            }
            if (Tag.INT.equals(value.getTag()) && !value.getValue().matches("0|[1-9][0-9]*")) {
                throw invalid(key + " must use canonical nonnegative decimal notation");
            }
            values.put(key, value.getValue());
        }
        if (!values.keySet().containsAll(types.keySet())) throw invalid("configuration is missing required settings");
        return new ForumAuthConfig(values, state);
    }
}
