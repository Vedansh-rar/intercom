package com.intercom.app.security

/**
 * The 256 words ride passphrases are drawn from.
 *
 * Exactly 256 so a word carries exactly 8 bits and a passphrase's strength is easy to
 * state: 5 words = 40 bits, 6 = 48 bits. The words are short, lower-case, unambiguous to
 * read aloud over an intercom and hard to mistype; none is a prefix of another that would
 * make autocomplete ambiguous in the entry field.
 */
object WordList {
    val WORDS: List<String> = listOf(
        "amber", "anchor", "apple", "arrow", "aspen", "atlas", "autumn", "badge",
        "baker", "banjo", "barn", "basil", "beach", "bell", "birch", "bison",
        "blaze", "bloom", "boat", "bolt", "bonus", "brave", "bread", "brick",
        "bridge", "brook", "brush", "cabin", "cactus", "camel", "candle", "canyon",
        "cargo", "cedar", "chain", "chalk", "charm", "cherry", "chess", "cliff",
        "cloud", "clover", "coast", "cobra", "coral", "cotton", "crane", "creek",
        "crown", "cycle", "daisy", "dance", "delta", "denim", "desert", "diesel",
        "dingo", "dock", "dove", "dragon", "dune", "eagle", "earth", "ember",
        "engine", "fable", "falcon", "fancy", "fern", "ferry", "field", "finch",
        "flame", "flint", "forest", "fox", "frost", "galaxy", "garden", "garlic",
        "gecko", "giant", "ginger", "glacier", "globe", "grain", "grape", "gravel",
        "grove", "guitar", "harbor", "hazel", "heron", "hill", "honey", "horizon",
        "hound", "iceberg", "igloo", "indigo", "iron", "island", "ivory", "jacket",
        "jade", "jaguar", "jelly", "jungle", "kayak", "kettle", "kiwi", "koala",
        "ladder", "lagoon", "lake", "lantern", "laurel", "lemon", "lilac", "linen",
        "lion", "lotus", "lunar", "maple", "marble", "meadow", "melon", "mango",
        "mirror", "mist", "moon", "moss", "motor", "mountain", "mule", "nectar",
        "needle", "nest", "noble", "north", "nutmeg", "oasis", "ocean", "olive",
        "onyx", "orbit", "orchid", "otter", "oyster", "paddle", "panda", "paper",
        "parrot", "peach", "pearl", "pebble", "pepper", "piano", "pine", "pixel",
        "planet", "plum", "pocket", "polar", "pond", "poppy", "prairie", "pumpkin",
        "quail", "quartz", "quilt", "rabbit", "radar", "rain", "raven", "reef",
        "ribbon", "river", "robin", "rocket", "rose", "saddle", "saffron", "sage",
        "sailor", "salmon", "sand", "scarf", "shadow", "shell", "silver", "sketch",
        "sky", "slate", "snow", "solar", "spark", "spice", "spruce", "square",
        "squid", "star", "stone", "storm", "summit", "sunset", "swan", "tango",
        "teal", "temple", "thistle", "thunder", "tiger", "timber", "tulip", "tundra",
        "turtle", "valley", "velvet", "violet", "voyage", "walnut", "wave", "willow",
        "window", "winter", "wolf", "yarn", "zebra", "zephyr", "tractor", "trail",
        "tunnel", "anvil", "beacon", "cobalt", "comet", "copper", "drift", "ebony",
        "echo", "ferret", "fjord", "glow", "harvest", "ibis", "jasmine", "lava",
        "lynx", "magnet", "nimbus", "opal", "pilot", "quest", "ridge", "sierra",
    )

    const val SIZE = 256
    const val BITS_PER_WORD = 8

    private val index: Map<String, Int> = WORDS.withIndex().associate { it.value to it.index }

    init {
        check(WORDS.size == SIZE) { "word list must have $SIZE entries, has ${WORDS.size}" }
        check(index.size == SIZE) { "word list has duplicates" }
    }

    fun contains(word: String): Boolean = word in index
}
