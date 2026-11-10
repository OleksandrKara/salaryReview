package com.salonreview.landing;

import java.util.List;

/** Structural mapping from public-site slugs to Square catalog ids; prices, durations and team
 * assignments are always read live from Square. */
final class LandingDefinitions {

    private LandingDefinitions() {
    }

    record ServiceOffer(String slug, String name, String offerLabel, String description,
                        String firstTimeItemId, String regularPriceItemId, boolean firstTimeOffer) {
    }

    record FlatAddon(String slug, String name, String description, String itemId, String requiresSlug) {
    }

    record FourHand(String slug, String name, String description, String itemId, String variationId,
                    double displayPrice) {
    }

    static final List<ServiceOffer> SERVICE_OFFERS = List.of(
            new ServiceOffer("manicure", "Russian Hard Gel Manicure", "First-Visit Special",
                    "Cuticle care, precision nail shaping, and a long-wearing hard gel overlay "
                            + "in one flawless color — no length change, no acrylic.",
                    "OPNE2Z2QTZR2USBD7AUJRRYF", "OCMEURM3ONES53H4TCHRY5BS", true),
            new ServiceOffer("pedicure", "Dry Russian Pedicure", "First-Visit Special",
                    "Dry European technique — no water soak — finished with a durable hard gel overlay.",
                    "QSJQCUYORMRDKQH4BTDUERIP", "MS7R7N3RWRJUT3U6MT2GL5W2", true));

    static final FlatAddon DESIGN = new FlatAddon("design", "Nail Art / Design",
            "Price depends on design complexity, starting from $20.", "7RJJPUA5OTN5LL33J2CL3G6M", "manicure");

    static final FourHand FOUR_HAND = new FourHand("four-hand-request", "4-Hand Service",
            "Two nail techs work at once — get mani + pedi together, or finish twice as fast. "
                    + "We'll call you to schedule the date, time & final pricing.",
            "HOQVAY5LVBH65RJHISMHLQVF", "NUEKRALUQ5TSZZGP346BCL4Y", 249.0);

    static final String ANNA = "8Z286R826CDF2";
    static final String ANASTASIIA = "TMYEv4CekHMjdYfO";
    static final List<String> ACTIVE_PROVIDERS = List.of(ANNA, ANASTASIIA);

    record Technique(String slug, String name, String description, String itemId, String variationId,
                     List<String> teamMemberIds, boolean isPublic) {
    }

    record Consultation(String slug, String name, String description, String itemId, String variationId,
                        List<String> teamMemberIds) {
    }

    static final List<Technique> TECHNIQUES = List.of(
            new Technique("nano-hairstrokes-anna", "Realistic Nano Hairstrokes — Anna Kara",
                    "Ultra-fine, hair-like strokes for the most natural, realistic brow look — Anna's signature technique.",
                    "3NXKMI2RHESZHQQQK6JIMR7C", "FKO7JLSXCFPSH7XM47F5GLK7", List.of(ANNA), true),
            new Technique("nano-hairstrokes-anastasiia", "Realistic Nano Hairstrokes — Anastasiia",
                    "The same ultra-fine, hair-like stroke technique, by Anastasiia.",
                    "3NXKMI2RHESZHQQQK6JIMR7C", "J5R6SKNOYPCNFBZOZQOZGSNC", List.of(ANASTASIIA), true),
            new Technique("combo", "Combo — Hairstrokes & Shading",
                    "Hairstrokes at the front, soft shading through the body and tail for extra depth and fullness. "
                            + "By Anna or Anastasiia — whichever slot you pick.",
                    "3EC565HBKF7WZHJ7EMABT2JQ", "DGBRMKMTWOV5C5HF2C7LBQHZ", ACTIVE_PROVIDERS, true),
            new Technique("powder-ombre-anastasiia", "Powder & Ombré — Anastasiia",
                    "A soft, gradient powder-fill look, fuller toward the tail — by Anastasiia.",
                    "XYYARZTVGB7FEDAAD7R3L6FN", "UZ4T47IZTIK4KCPRQMA3UNC4", List.of(ANASTASIIA), true),
            new Technique("touch-up", "Touch-Up",
                    "A required part of the process, not an add-on — locks in your result for the long run.",
                    "P5CCSK4COM4QJH53KDSK4R7U", "E5BZJGW3T2DV7LKXT5KXITRT", ACTIVE_PROVIDERS, false),
            new Technique("color-booster", "Color Booster",
                    "The refresh permanent makeup needs to keep holding — required on schedule, not optional.",
                    "PFWBF5IF2AIJ7YGRHWFNLVYS", "MWRHIS6RVIQHGTLHVMZYHQU5", ACTIVE_PROVIDERS, false));

    static final List<Consultation> CONSULTATIONS = List.of(
            new Consultation("online-consultation", "Online Consultation",
                    "A free video call to talk through your brow goals and pick the right technique — no cost, no commitment.",
                    "52X2D4SLY64DGS637F2JO4CV", "6QH2EUW5RFS5DBRTS6ZFYT3Q", ACTIVE_PROVIDERS),
            new Consultation("in-person-consultation", "In-Person Consultation",
                    "Meet in studio to map your brow shape and confirm your technique together.",
                    "YIJY7CVQWNCDEMRCF5LD36ZE", "QGKYRG66BJ2DS54VRG6YO5ZN", ACTIVE_PROVIDERS));

    static final String DEPOSIT_ITEM_ID = "IHPE6FF7S4HDOWIDMRAV5QXX";
    static final String DEPOSIT_VARIATION_ID = "NVEO2SSBJ6RBHTL5UBA2WVOZ";

    static boolean isTopTierName(String variationName) {
        return variationName != null && LandingText.strip(variationName).toLowerCase().contains("top");
    }
}
