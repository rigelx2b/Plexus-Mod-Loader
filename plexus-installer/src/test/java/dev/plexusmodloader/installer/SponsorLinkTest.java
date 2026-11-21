package dev.plexusmodloader.installer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SponsorLinkTest {

    @Test
    void theBuildPacksAnHttpsLink() {
        Optional<URI> link = SponsorLink.bundled();
        assertTrue(link.isPresent(), "the build writes plexus.sponsorUrl to " + SponsorLink.RESOURCE);
        assertEquals("https", link.get().getScheme());
    }

    @Test
    void onlyAbsoluteHttpsAddressesAreShown() {
        assertEquals(
                Optional.of(URI.create("https://example.org/someone")),
                SponsorLink.parse(" https://example.org/someone "));
        assertEquals(Optional.empty(), SponsorLink.parse(""));
        assertEquals(Optional.empty(), SponsorLink.parse("http://example.org/someone"));
        assertEquals(Optional.empty(), SponsorLink.parse("javascript:alert(1)"));
        assertEquals(Optional.empty(), SponsorLink.parse("example.org/someone"));
        assertEquals(Optional.empty(), SponsorLink.parse("https://exa mple.org"));
    }
}
