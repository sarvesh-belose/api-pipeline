package com.crisil.apiorch.http.auth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.crisil.apiorch.config.ApiorchProperties;

/**
 * Where a secret reference may point (US-2.2 AC1b, AC2), so an admin cannot make apiorch send its own
 * infrastructure secrets to a host they control: {@code env:} names start with {@code apiorch.secrets.env-prefix};
 * {@code file:} paths resolve, after normalisation and symlink resolution, inside {@code apiorch.secrets.file-dir}.
 */
@Component
public class SecretReferencePolicy {

    static final Pattern REFERENCE = Pattern.compile("^(env|file):.+$");
    private static final Pattern ENV_NAME = Pattern.compile("^[A-Z][A-Z0-9_]*$");

    private final String envPrefix;
    private final Path fileDir;

    @Autowired
    public SecretReferencePolicy(ApiorchProperties properties) {
        this(properties.secrets().envPrefix(), properties.secrets().fileDir());
    }

    SecretReferencePolicy(String envPrefix, String fileDir) {
        this.envPrefix = envPrefix;
        this.fileDir = canonical(Path.of(fileDir));
    }

    public static boolean isReference(String value) {
        return value != null && REFERENCE.matcher(value).matches();
    }

    /** Every reason {@code ref} is not allowed; empty when it is. */
    public List<String> violations(String ref) {
        List<String> problems = new ArrayList<>();
        if (!isReference(ref)) {
            problems.add("secrets must be references (env:NAME or file:/path)");
            return problems;
        }
        String target = ref.substring(ref.indexOf(':') + 1);
        if (ref.startsWith("env:")) {
            if (!ENV_NAME.matcher(target).matches() || !target.startsWith(envPrefix)) {
                problems.add("env references must name a variable starting with " + envPrefix);
            }
        } else {
            Path path;
            try {
                path = Path.of(target);
            } catch (InvalidPathException e) {
                problems.add("is not a valid file path");
                return problems;
            }
            if (!path.isAbsolute() || !canonical(path).startsWith(fileDir)) {
                problems.add("file references must point inside " + fileDir);
            }
        }
        return problems;
    }

    /** Absolute and normalised, following symlinks as far as the path exists. */
    static Path canonical(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path existing = absolute;
        Path rest = Path.of("");
        while (existing != null && !Files.exists(existing)) {
            rest = existing.getFileName().resolve(rest);
            existing = existing.getParent();
        }
        if (existing == null) {
            return absolute;
        }
        try {
            return existing.toRealPath().resolve(rest).normalize();
        } catch (IOException e) {
            return absolute;
        }
    }
}
