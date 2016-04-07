/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions copyright [year] [name of copyright owner]".
 *
 * Copyright 2016 ForgeRock AS.
 */

package com.forgerock.api.markup.asciidoc;

import static com.forgerock.api.markup.asciidoc.AsciiDocSymbols.*;
import static com.forgerock.api.util.ValidationUtil.isEmpty;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.forgerock.util.Reject;

/**
 * AsciiDoc table builder [<a href="http://asciidoctor.org/docs/user-manual/#tables">ref</a>], which defers insertion
 * of the table, at the end of the parent document, until {@link #tableEnd()} is called.
 * <p>
 * This class is not thread-safe.
 * </p>
 */
public class AsciiDocTable {

    private static final Pattern TABLE_CELL_SYMBOL_PATTERN = Pattern.compile("\\|");

    private final AsciiDoc asciiDoc;
    private final StringBuilder builder;
    private final List<String> cells;
    private Integer columnsPerRow;
    private String title;
    private boolean hasHeader;

    AsciiDocTable(final AsciiDoc asciiDoc, final StringBuilder builder) {
        this.asciiDoc = Reject.checkNotNull(asciiDoc);
        this.builder = Reject.checkNotNull(builder);
        cells = new ArrayList<>();
    }

    /**
     * Sets a table-title.
     *
     * @param title Table-title
     * @return Table builder
     */
    public AsciiDocTable title(final String title) {
        if (isEmpty(title)) {
            throw new AsciiDocException("title required");
        }
        if (this.title != null) {
            throw new AsciiDocException("title already defined");
        }
        this.title = title;
        return this;
    }

    /**
     * Sets the column headers, where blank entries can be null/empty, but the length of the headers array must
     * be equal to the number of columns in the table.
     *
     * @param columnHeaders Column headers
     * @return Table builder
     */
    public AsciiDocTable headers(final String... columnHeaders) {
        if (isEmpty(columnHeaders)) {
            throw new AsciiDocException("columnHeaders required");
        }
        if (hasHeader) {
            throw new AsciiDocException("headers already defined");
        }
        if (columnsPerRow == null) {
            columnsPerRow = columnHeaders.length;
        } else if (columnsPerRow != columnHeaders.length) {
            throw new AsciiDocException("columnHeaders.length != columnsPerRow");
        }
        hasHeader = true;

        // add to front of cells
        cells.add(null);
        for (int i = columnHeaders.length - 1; i > -1; --i) {
            cells.add(0, TABLE_CELL + normalizeColumnCell(columnHeaders[i]));
        }
        return this;
    }

    /**
     * Sets number of columns per row, which is implicitly set by {@link #headers(String...)}.
     *
     * @param columnsPerRow Columns per row
     * @return Table builder
     */
    public AsciiDocTable columnsPerRow(final int columnsPerRow) {
        if (this.columnsPerRow != null) {
            throw new AsciiDocException("columnsPerRow already defined");
        }
        if (this.columnsPerRow < 1) {
            throw new AsciiDocException("columnsPerRow < 1");
        }
        this.columnsPerRow = columnsPerRow;
        return this;
    }

    /**
     * Inserts a column-cell.
     *
     * @param columnCell Column-cell or {@code null} for empty cell
     * @return Table builder
     */
    public AsciiDocTable columnCell(final String columnCell) {
        if (this.columnsPerRow == null) {
            throw new AsciiDocException("columnsPerRow not yet defined");
        }
        cells.add(TABLE_CELL + normalizeColumnCell(columnCell));
        return this;
    }

    /**
     * Inserts a column-cell, with a style.
     *
     * @param columnCell Column-cell or {@code null} for empty cell
     * @param style Column-style
     * @return Table builder
     */
    public AsciiDocTable columnCell(final String columnCell, final AsciiDocTableColumnStyles style) {
        if (this.columnsPerRow == null) {
            throw new AsciiDocException("columnsPerRow not yet defined");
        }
        cells.add(style.toString() + TABLE_CELL + normalizeColumnCell(columnCell));
        return this;
    }

    /**
     * Adds an optional space to visually delineate the end of a row in the generated markup. The intention is that
     * this method would be called after adding all columns for a given row.
     *
     * @return table builder
     */
    public AsciiDocTable rowEnd() {
        cells.add(null);
        return this;
    }

    private String normalizeColumnCell(final String columnCell) {
        if (isEmpty(columnCell)) {
            // allow for empty cells
            return "";
        }
        // escape TABLE_CELL symbols
        final Matcher m = TABLE_CELL_SYMBOL_PATTERN.matcher(columnCell);
        return m.find() ? m.replaceAll("\\" + TABLE_CELL) : columnCell;
    }

    /**
     * Completes the table being built, and inserts it at the end of the parent document.
     *
     * @return Doc builder
     */
    public AsciiDoc tableEnd() {
        // TODO table-options builder
        // TODO cell-options builder

        // [cols="2*", caption="", options="header"]
        builder.append("[cols=\"").append(columnsPerRow).append("*\", caption=\"\", options=\"");
        if (hasHeader) {
            builder.append("header");
        }
        builder.append("\"]").append(NEWLINE);

        if (title != null) {
            builder.append(".").append(title).append(NEWLINE);
        }

        builder.append(TABLE).append(NEWLINE);
        if (cells.get(cells.size() - 1) == null) {
            // remove trailing "row spacer"
            cells.remove(cells.size() - 1);
        }
        for (final String item : cells) {
            if (item != null) {
                // null is an optional "row spacer" (see endRow), otherwise cells will be non-null
                builder.append(item);
            }
            builder.append(NEWLINE);
        }
        builder.append(TABLE).append(NEWLINE);

        return asciiDoc;
    }

}
