package com.crisil.apiorch.observability;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What to mask for one step (US-7.3): header names, plain keys that match at any depth, and dotted paths such as
 * {@code customer.dob} or {@code applicants[*].pan} that match from the root. All comparisons ignore case. Built
 * from {@code apiorch.logging.always-mask-*}, the pipeline's and the step's {@code maskKeys} and the endpoint's API
 * key header name (US-2.2).
 */
public final class MaskingPolicy {

    /** An array element in a parsed path. */
    static final String ANY_ELEMENT = "[*]";

    private final Set<String> headers;
    private final Set<String> keys;
    private final List<List<String>> paths;

    private MaskingPolicy(Set<String> headers, Set<String> keys, List<List<String>> paths) {
        this.headers = headers;
        this.keys = keys;
        this.paths = paths;
    }

    /** {@code maskKeys} entries containing {@code .} or {@code [*]} are paths; the rest are plain keys. */
    public static MaskingPolicy of(Collection<String> headerNames, Collection<String> maskKeys) {
        Set<String> headers = new LinkedHashSet<>();
        headerNames.forEach(h -> headers.add(h.toLowerCase(Locale.ROOT)));
        Set<String> keys = new LinkedHashSet<>();
        List<List<String>> paths = new ArrayList<>();
        for (String key : maskKeys) {
            if (key.contains(".") || key.contains(ANY_ELEMENT)) {
                paths.add(parse(key));
            } else {
                keys.add(key.toLowerCase(Locale.ROOT));
            }
        }
        return new MaskingPolicy(Set.copyOf(headers), Set.copyOf(keys), List.copyOf(paths));
    }

    static List<String> parse(String path) {
        List<String> out = new ArrayList<>();
        for (String part : path.split("\\.")) {
            String name = part;
            int arrays = 0;
            while (name.endsWith(ANY_ELEMENT)) {
                name = name.substring(0, name.length() - ANY_ELEMENT.length());
                arrays++;
            }
            out.add(name.toLowerCase(Locale.ROOT));
            for (int i = 0; i < arrays; i++) {
                out.add(ANY_ELEMENT);
            }
        }
        return List.copyOf(out);
    }

    public boolean header(String name) {
        return headers.contains(name.toLowerCase(Locale.ROOT));
    }

    public boolean key(String name) {
        return keys.contains(name.toLowerCase(Locale.ROOT));
    }

    /** Whether the field at {@code path} (lower-case names, {@link #ANY_ELEMENT} for array elements) is masked. */
    boolean path(List<String> path) {
        return paths.contains(path);
    }
}
