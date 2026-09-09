package com.smartmgmt.management.search;

/**
 * Where a hit came from. Worth returning: in HYBRID mode it is the only way to
 * explain why a result with no visible query terms is ranked highly.
 */
public enum SearchMatch {
    KEYWORD,
    SEMANTIC,
    BOTH
}
