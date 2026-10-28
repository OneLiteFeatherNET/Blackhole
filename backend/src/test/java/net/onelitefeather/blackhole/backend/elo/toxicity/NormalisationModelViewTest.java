package net.onelitefeather.blackhole.backend.elo.toxicity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NormalisationModelViewTest {

    private final ChatNormaliser normaliser = new ChatNormaliser(false);

    @Test
    void legacyColourCodesAreRemoved() {
        assertEquals("Hello world", normaliser.modelView("§aHello §lworld"), "§ codes must not reach the model");
    }

    @Test
    void zeroWidthAndSoftHyphenCharactersAreRemoved() {
        assertEquals("abc", normaliser.modelView("a​b­c"), "invisible characters must be removed");
    }

    @Test
    void unicodeCompatibilityFormsAreFoldedWithNfkc() {
        assertEquals("hello", normaliser.modelView("ｈｅｌｌｏ"), "fullwidth letters must become ASCII");
    }

    @Test
    void leetSpellingIsNotDecodedInTheModelView() {
        assertEquals("1d10t", normaliser.modelView("1d10t"), "the model is trained on natural text, so leet stays");
    }

    @Test
    void ampersandCodesAreKeptWhenTheOptionIsOff() {
        assertEquals("&aHi", normaliser.modelView("&aHi"), "ampersand codes are text unless the option is on");
    }

    @Test
    void ampersandCodesAreRemovedWhenTheOptionIsOn() {
        assertEquals("Hi", new ChatNormaliser(true).modelView("&aHi"), "ampersand codes are removed when enabled");
    }
}
