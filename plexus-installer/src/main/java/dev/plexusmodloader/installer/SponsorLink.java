package dev.plexusmodloader.installer;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Properties;

/**
 * Provides the support link shown beneath the installer window's buttons and in completion dialogs. The build writes
 * {@code plexus.sponsorUrl} from {@code gradle.properties} to {@value #RESOURCE}; the release notes and the website
 * read the same property.
 */
final class SponsorLink {

    static final String RESOURCE = "META-INF/plexusmodloader/sponsor.properties";

    private SponsorLink() {}

    /**
     * Returns the support link packaged into this installer jar, or empty when unset during the build or not an https
     * address.
     */
    static Optional<URI> bundled() {
        try (InputStream input = SponsorLink.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (input == null) {
                return Optional.empty();
            }
            Properties properties = new Properties();
            properties.load(new InputStreamReader(input, StandardCharsets.UTF_8));
            return parse(properties.getProperty("url", ""));
        } catch (IOException e) {
            // The link is optional; an unreadable resource only hides it.
            return Optional.empty();
        }
    }

    /** Returns {@code value} parsed as an absolute https address with a host, or empty if invalid. */
    static Optional<URI> parse(String value) {
        String trimmed = value.strip();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        try {
            URI uri = new URI(trimmed);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    ? Optional.of(uri)
                    : Optional.empty();
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }
}
