package org.lflang.generator;

import java.nio.file.Path;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.util.LineAndColumn;

/**
 * Encapsulates data about the correspondence between
 * ranges of generated code and ranges of a Lingua Franca
 * file.
 */
public class CodeMap {

    public static class Correspondence {
        // This pattern has the markers "/* */", which some languages use as line comments. This does not
        //  represent any serious effort to embed the string representation of this object in generated code
        //  without introducing a syntax error. Instead, it is done simply because it is easy.
        private static final Pattern PATTERN = Pattern.compile(String.format(
            "/\\*Correspondence: (?<lfRange>%s) \\-> (?<generatedRange>%s) \\(src=(?<path>%s)\\)\\*/",
            Position.removeNamedCapturingGroups(Range.PATTERN),
            Position.removeNamedCapturingGroups(Range.PATTERN),
            ".*"
        ));

        // TODO(peter): Add "private final boolean verbatim;" and make corresponding enhancements
        private final Path path;
        private final Range lfRange;
        private final Range generatedRange;

        /**
         * Instantiates a Correspondence between
         * {@code lfRange} at {@code path} and
         * {@code generatedRange} in the generated file
         * associated with this Correspondence.
         * @param path a path to an LF source file
         * @param lfRange a range in the given LF file
         * @param generatedRange the range of generated code
         *                       associated with
         *                       {@code lfRange}
         */
        public Correspondence(Path path, Range lfRange, Range generatedRange) {
            this.path = path;
            this.lfRange = lfRange;
            this.generatedRange = generatedRange;
        }

        /**
         * Returns a path to the LF source file described by
         * this Correspondence.
         * @return a path to the LF source file described by
         * this Correspondence
         */
        public Path getPath() {
            return path;
        }

        /**
         * Returns a range in an LF source file.
         * @return a range in an LF source file
         */
        public Range getLfRange() {
            return lfRange;
        }

        /**
         * Returns a range in a generated file that
         * corresponds to a range in an LF file.
         * @return a range in a generated file that
         * corresponds to a range in an LF file
         */
        public Range getGeneratedRange() {
            return generatedRange;
        }

        @Override
        public String toString() {
            return String.format(
                "/*Correspondence: %s -> %s (src=%s)*/",
                lfRange.toString(), generatedRange.toString(), path.toString()
            );
        }

        /**
         * Returns the Correspondence represented by
         * {@code s}.
         * @param s a String that represents a
         *          Correspondence, formatted like the
         *          output of
         *          {@code Correspondence::toString}
         * @return the Correspondence represented by
         * {@code s}
         */
        public static Correspondence fromString(String s) {
            return fromString(s, Position.ORIGIN);
        }

        /**
         * Returns the Correspondence represented by
         * {@code s}.
         * @param s a String that represents a
         *          Correspondence, formatted like the
         *          output of Correspondence::toString
         * @param relativeTo the offset relative to which
         *                   the offsets given are given
         * @return the Correspondence represented by
         * {@code s}
         */
        public static Correspondence fromString(String s, Position relativeTo) {
            Matcher matcher = PATTERN.matcher(s);
            if (matcher.matches()) {
                Range lfRange = Range.fromString(matcher.group("lfRange"));
                Range generatedRange = Range.fromString(matcher.group("generatedRange"), relativeTo);
                return new Correspondence(
                    Path.of(matcher.group("path")),
                    lfRange, generatedRange
                );
            }
            throw new IllegalArgumentException(String.format("Could not parse %s as a Correspondence.", s));
        }

        /**
         * Returns {@code representation}, tagged with
         * a Correspondence to the source code associated
         * with {@code astNode}.
         * @param astNode an arbitrary AST node
         * @param representation the code generated from
         *                       that AST node
         * @param verbatim whether {@code representation}
         *                 is copied verbatim from the
         *                 part of the source code
         *                 associated with {@code astNode}
         * @return {@code representation}, tagged with
         * a Correspondence to the source code associated
         * with {@code astNode}
         */
        public static String tag(EObject astNode, String representation, boolean verbatim) {
            final INode node = NodeModelUtils.getNode(astNode);
            // If the EObject originates from an AST transformation, then it does not correspond directly
            // to any LF code, and it need not be tagged at all.
            if (node == null) return representation;
            final LineAndColumn oneBasedLfLineAndColumn = NodeModelUtils.getLineAndColumn(node, node.getTotalOffset());
            Position lfStart = Position.fromOneBased(
                oneBasedLfLineAndColumn.getLine(), oneBasedLfLineAndColumn.getColumn()
            );
            Position lfDisplacement;
            final Position generatedCodeDisplacement = Position.displacementOf(representation);
            final Path lfPath = Path.of(astNode.eResource().getURI().path());
            if (verbatim) {
                lfStart = lfStart.plus(Position.displacementOf(
                    node.getText().substring(0, indexOf(node.getText(), representation))
                ));
                lfDisplacement = generatedCodeDisplacement;
            } else {
                lfDisplacement = Position.displacementOf(node.getText());
            }
            return new Correspondence(
                lfPath,
                new Range(lfStart, lfStart.plus(lfDisplacement)),
                new Range(Position.ORIGIN, generatedCodeDisplacement)
            ) + representation;
        }

        /**
         * Make a best-effort attempt to find the index of
         * a near substring whose first line is expected to
         * be an exact substring of {@code s}. Return 0
         * upon failure.
         * @param s an arbitrary string
         * @param imperfectSubstring an approximate
         *                           substring of {@code s}
         * @return the index of {@code imperfectSubstring}
         * within {@code s}
         */
        private static int indexOf(String s, String imperfectSubstring) {
            String firstLine = imperfectSubstring.lines().findFirst().orElse("");
            return Math.max(0, s.indexOf(firstLine));
        }
    }

    /**
     * The content of the generated file represented by
     * this.
     */
    private final String generatedCode;
    /**
     * A mapping from Lingua Franca source paths to mappings
     * from ranges in the generated file represented by this
     * to ranges in Lingua Franca files.
     */
    private final Map<Path, NavigableMap<Range, Range>> map;

    /* ------------------------- PUBLIC METHODS -------------------------- */

    /**
     * Instantiates a {@code CodeMap} from
     * {@code internalGeneratedCode}.
     * {@code internalGeneratedCode} may be invalid
     * code that is different from the final generated code
     * because it should contain deserializable
     * representations of {@code Correspondences}.
     * @param internalGeneratedCode code from a code
     *                              generator that contains
     *                              serialized
     *                              Correspondences
     * @return a CodeMap documenting the provided code
     */
    public static CodeMap fromGeneratedCode(String internalGeneratedCode) {
        Map<Path, NavigableMap<Range, Range>> map = new HashMap<>();
        StringBuilder generatedCode = new StringBuilder();
        String[] lines = internalGeneratedCode.lines().toArray(String[]::new);
        for (int zeroBasedLine = 0; zeroBasedLine < lines.length; zeroBasedLine++) {
            generatedCode.append(processGeneratedLine(lines[zeroBasedLine], zeroBasedLine, map)).append('\n');
        }
        return new CodeMap(generatedCode.toString(), map);
    }

    /**
     * Returns the generated code (without Correspondences).
     * @return the generated code (without Correspondences)
     */
    public String getGeneratedCode() {
        return generatedCode;
    }

    /**
     * Returns the set of all paths to Lingua Franca files
     * that are known to contain code that corresponds to
     * code in the generated file represented by this.
     * @return the set of all paths to Lingua Franca files
     * that are known to contain code that corresponds to
     * code in the generated file represented by this
     */
    public Set<Path> lfSourcePaths() {
        return map.keySet();
    }

    /**
     * Returns the position in {@code lfFile}
     * corresponding to {@code generatedFilePosition}
     * if such a position is known, or the zero Position
     * otherwise.
     * @param lfFile the path to an arbitrary Lingua Franca
     *               source file
     * @param generatedFilePosition a position in a
     *                              generated file
     * @return the position in {@code lfFile}
     * corresponding to {@code generatedFilePosition}
     */
    public Position adjusted(Path lfFile, Position generatedFilePosition) {
        NavigableMap<Range, Range> mapOfInterest = map.get(lfFile);
        Map.Entry<Range, Range> nearestEntry = mapOfInterest.floorEntry(Range.degenerateRange(generatedFilePosition));
        if (nearestEntry == null) return Position.ORIGIN;
        if (nearestEntry.getKey().contains(generatedFilePosition)) {
            return nearestEntry.getValue().getStartInclusive().plus(
                generatedFilePosition.minus(nearestEntry.getKey().getStartInclusive())
            );
        }
        return Position.ORIGIN;
    }

    /**
     * Returns the range in {@code lfFile}
     * corresponding to {@code generatedFileRange}
     * if such a range is known, or a degenerate Range
     * otherwise.
     * @param lfFile the path to an arbitrary Lingua Franca
     *               source file
     * @param generatedFileRange a position in a
     *                              generated file
     * @return the range in {@code lfFile}
     * corresponding to {@code generatedFileRange}
     */
    public Range adjusted(Path lfFile, Range generatedFileRange) {
        final Position start = adjusted(lfFile, generatedFileRange.getStartInclusive());
        final Position end = adjusted(lfFile, generatedFileRange.getEndExclusive());
        return start.compareTo(end) <= 0 ? new Range(start, end) : new Range(start, start);
    }

    /* ------------------------- PRIVATE METHODS ------------------------- */

    private CodeMap(String generatedCode, Map<Path, NavigableMap<Range, Range>> map) {
        this.generatedCode = generatedCode;
        this.map = map;
    }

    /**
     * Removes serialized Correspondences from {@code line}
     * and updates {@code map} according to those
     * Correspondences.
     * @param line a line of generated code
     * @param zeroBasedLineIndex the index of the given line
     * @param map a map that stores Correspondences
     * @return the line of generated code with all
     * Correspondences removed
     */
    private static String processGeneratedLine(
        String line,
        int zeroBasedLineIndex,
        Map<Path, NavigableMap<Range, Range>> map
    ) {
        Matcher matcher = Correspondence.PATTERN.matcher(line);
        StringBuilder cleanedLine = new StringBuilder();
        int lastEnd = 0;
        while (matcher.find()) {
            cleanedLine.append(line, lastEnd, matcher.start());
            Correspondence c = Correspondence.fromString(
                matcher.group(),
                Position.fromZeroBased(zeroBasedLineIndex, cleanedLine.length())
            );
            if (!map.containsKey(c.path)) map.put(c.path, new TreeMap<>());
            map.get(c.path).put(c.generatedRange, c.lfRange);
            lastEnd = matcher.end();
        }
        cleanedLine.append(line.substring(lastEnd));
        return cleanedLine.toString();
    }
}
