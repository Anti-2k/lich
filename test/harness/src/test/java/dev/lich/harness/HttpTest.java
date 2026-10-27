package dev.lich.harness;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HttpTest {
    /** FTB gives CurseForge URLs with the file name as is. */
    @Test
    void rawUrlsGetEncodedLikeABrowserSendsThem() {
        assertEquals("https://edge.forgecdn.net/files/2423/369/BiblioCraft%5Bv1.11.7%5D%5BMC1.7.10%5D.jar",
                Http.uri("https://edge.forgecdn.net/files/2423/369/BiblioCraft[v1.11.7][MC1.7.10].jar").toString());
        assertEquals("https://edge.forgecdn.net/files/2719/376/Carpenter's%20Blocks%20v3.3.8.2%20-%20MC%201.7.10.jar",
                Http.uri("https://edge.forgecdn.net/files/2719/376/Carpenter's Blocks v3.3.8.2 - MC 1.7.10.jar").toString());
        // Already encoded URLs and queries stay as they are.
        String modrinth = "https://cdn.modrinth.com/data/j9kLO0Ry/versions/fZqZvGcP/Hypixel%20Premium-%20Skyblock%20%26%20PvP%202.3.4.mrpack";
        assertEquals(modrinth, Http.uri(modrinth).toString());
        assertEquals("https://api.modrinth.com/v2/x?a=1&b=%5B%221.8.9%22%5D",
                Http.uri("https://api.modrinth.com/v2/x?a=1&b=[\"1.8.9\"]").toString());
        assertEquals("https://example.com/M%C3%BCnze.jar", Http.uri("https://example.com/Münze.jar").toString());
    }
}
