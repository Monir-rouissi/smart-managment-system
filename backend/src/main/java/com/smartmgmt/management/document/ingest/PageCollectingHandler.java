package com.smartmgmt.management.document.ingest;

import java.util.ArrayList;
import java.util.List;

import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Reads Tika's XHTML output and keeps one text buffer per {@code <div class="page">}.
 *
 * <p>Tika's PDF parser emits that div per page; formats that do not (DOCX) simply
 * produce a single unlabelled page. Extraction stops once the character budget is
 * spent, so a pathological file cannot exhaust the heap.
 */
class PageCollectingHandler extends DefaultHandler {

    /** Elements after which a newline keeps words from running together. */
    private static final List<String> BLOCK_ELEMENTS = List.of("p", "div", "li", "tr", "h1", "h2", "h3", "h4", "br");

    record Page(String label, String text) {
    }

    private final int maxCharacters;
    private final List<Page> pages = new ArrayList<>();
    private StringBuilder current = new StringBuilder();
    private int pageNumber = 0;
    private int consumed = 0;

    PageCollectingHandler(int maxCharacters) {
        this.maxCharacters = maxCharacters;
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes) {
        String name = localName == null || localName.isEmpty() ? qName : localName;
        if ("div".equalsIgnoreCase(name) && "page".equals(attributes.getValue("class"))) {
            flush();
            pageNumber++;
        }
    }

    @Override
    public void endElement(String uri, String localName, String qName) {
        String name = localName == null || localName.isEmpty() ? qName : localName;
        if (BLOCK_ELEMENTS.contains(name.toLowerCase())) {
            current.append('\n');
        }
    }

    @Override
    public void characters(char[] ch, int start, int length) {
        int remaining = maxCharacters - consumed;
        if (remaining <= 0) {
            return;
        }
        int take = Math.min(length, remaining);
        current.append(ch, start, take);
        consumed += take;
    }

    @Override
    public void endDocument() {
        flush();
    }

    private void flush() {
        if (!current.toString().isBlank()) {
            pages.add(new Page(pageNumber == 0 ? null : String.valueOf(pageNumber), current.toString()));
        }
        current = new StringBuilder();
    }

    List<Page> pages() {
        return pages;
    }
}
