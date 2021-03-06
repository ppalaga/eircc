/*
 * Copyright (c) 2014 Peter Palaga.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */

package org.l2x6.eircc.ui.misc;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.TextViewer;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.swt.custom.StyleRange;

/**
 * @author <a href="mailto:ppalaga@redhat.com">Peter Palaga</a>
 */
public interface StyledWrapper {
    public static class StyledStringWrapper implements StyledWrapper {
        private final StyledString target;

        /**
         * @param target
         */
        public StyledStringWrapper(StyledString target) {
            super();
            this.target = target;
        }

        /**
         * @see org.l2x6.eircc.ui.misc.StyledWrapper#append(java.lang.String)
         */
        @Override
        public int append(String token) {
            int offset = target.length();
            target.append(token);
            return offset;
        }

        /**
         * @see org.l2x6.eircc.ui.misc.StyledWrapper#append(java.lang.String,
         *      org.l2x6.eircc.ui.misc.ExtendedTextStyle)
         */
        @Override
        public void append(String token, ExtendedTextStyle style) {
            if (token != null && token.length() > 0) {
                int offset = append(token);
                if (style != null) {
                    target.setStyle(offset, token.length(), style.getStyler());
                }
            }
        }

        /**
         * @see org.l2x6.eircc.ui.misc.StyledWrapper#getLength()
         */
        @Override
        public int getLength() {
            return target.length();
        }
    }

    public static class StylesCollector implements StyledWrapper {
        private final TextViewer target;
        private final int intialLength;
        private final StringBuilder buffer;
        private final List<StyleRange> ranges;
        private long timer = System.currentTimeMillis();

        /**
         * @param target
         */
        public StylesCollector(TextViewer target) {
            super();
            this.target = target;
            IDocument doc = target.getDocument();
            this.intialLength = doc != null ? doc.getLength() : 0;
            this.buffer = new StringBuilder();
            this.ranges = new ArrayList<StyleRange>();
        }

        /**
         * @see org.l2x6.eircc.ui.misc.StyledWrapper#append(java.lang.String)
         */
        @Override
        public int append(String token) {
            int offset = getLength();
            buffer.append(token);
            return offset;
        }

        /**
         * @see org.l2x6.eircc.ui.misc.StyledWrapper#append(java.lang.String,
         *      org.l2x6.eircc.ui.misc.ExtendedTextStyle)
         */
        @Override
        public void append(String token, ExtendedTextStyle style) {
            if (token != null && token.length() > 0) {
                int offset = append(token);
                StyleRange range = style.createRange(offset, token.length());
                ranges.add(range);
            }
        }

        /**
         * @see org.l2x6.eircc.ui.misc.StyledWrapper#getLength()
         */
        @Override
        public int getLength() {
            return intialLength + buffer.length();
        }

        public void apply() {
            IDocument doc = target.getDocument();
            int offset = doc.getLength();
            System.out.println("reading "+ (System.currentTimeMillis() - timer));
            timer = System.currentTimeMillis();
            try {
                doc.replace(offset, 0, buffer.toString());
            } catch (BadLocationException e) {
                throw new RuntimeException(e);
            }
            System.out.println("text "+ (System.currentTimeMillis() - timer));
            timer = System.currentTimeMillis();
            StyleRange[] styles = ranges.toArray(new StyleRange[ranges.size()]);
            target.getTextWidget().replaceStyleRanges(offset, buffer.length(), styles);
            System.out.println("ranges "+ (System.currentTimeMillis() - timer));
            timer = System.currentTimeMillis();
        }
    }
    public static class TextViewerWrapper implements StyledWrapper {
        private final TextViewer target;

        /**
         * @param target
         */
        public TextViewerWrapper(TextViewer target) {
            super();
            this.target = target;
        }

        /**
         * @see org.l2x6.eircc.ui.misc.StyledWrapper#append(java.lang.String)
         */
        @Override
        public int append(String token) {
            IDocument doc = target.getDocument();
            int offset = doc.getLength();
            try {
                doc.replace(offset, 0, token);
            } catch (BadLocationException e) {
                throw new RuntimeException(e);
            }
            return offset;
        }

        /**
         * @see org.l2x6.eircc.ui.misc.StyledWrapper#append(java.lang.String,
         *      org.l2x6.eircc.ui.misc.ExtendedTextStyle)
         */
        @Override
        public void append(String token, ExtendedTextStyle style) {
            if (token != null && token.length() > 0) {
                int offset = append(token);
                StyleRange range = style.createRange(offset, token.length());
                target.getTextWidget().setStyleRange(range);
            }
        }

        /**
         * @see org.l2x6.eircc.ui.misc.StyledWrapper#getLength()
         */
        @Override
        public int getLength() {
            IDocument doc = target.getDocument();
            return doc != null ? doc.getLength() : 0;
        }
    }

    public int append(String token);

    public void append(String token, ExtendedTextStyle style);

    public int getLength();
}
