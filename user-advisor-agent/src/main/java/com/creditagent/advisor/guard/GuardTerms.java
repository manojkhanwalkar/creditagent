package com.creditagent.advisor.guard;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The word lists and patterns the guardrails match, all against {@link TextNormalizer#fold} or
 * {@link TextNormalizer#foldLeet} output (lower case, Latin letters, digits and single spaces).
 *
 * <p>Lists are deliberately short and specific to a credit-report assistant. They are a first layer
 * in front of a model, not a moderation service: a real LLM deployment should add a classifier.
 */
final class GuardTerms {

    private GuardTerms() {}

    /** Profanity and abuse: a token that starts with one of these. */
    static final List<String> ABUSIVE_STEMS = List.of(
            "fuck", "shit", "bitch", "cunt", "asshole", "bastard", "motherfuck", "bullshit", "whore", "slut",
            "douche", "retard", "dickhead", "jackass", "dumbass", "idiot", "moron", "fvck", "phuck", "fuk");

    /** Profanity masked with symbols ({@code f*ck}, {@code sh!t}, {@code b@tch}), on the cleaned lower-case text. */
    static final Pattern MASKED_ABUSE = Pattern.compile(
            "\\b(f[^\\sa-z0-9]{1,3}c?k\\w*|f[^\\sa-z0-9]ck\\w*|sh[^\\sa-z0-9]{1,2}t\\w*|b[^\\sa-z0-9]{1,2}tch\\w*"
                    + "|a[^\\sa-z0-9]{1,2}shole\\w*|c[^\\sa-z0-9]nt\\w*)");

    /**
     * Profanity and insults: these exact tokens. "Stupid" and "dumb" are not here: users ask "is it
     * stupid to close my oldest card?". An answer may not use them ({@link #OUTPUT_INSULTS}).
     */
    static final Set<String> ABUSIVE_WORDS = Set.of(
            "dick", "piss", "wtf", "stfu", "loser", "worthless", "pathetic", "deadbeat", "scum", "crap");

    /** Words an answer must never call the user, on top of the abuse lists. */
    static final Set<String> OUTPUT_INSULTS = Set.of("stupid", "stupidest", "dumb", "dumbest", "lazy", "foolish", "ignorant");

    /**
     * Characteristics a creditor may not use (US Equal Credit Opportunity Act) and group labels. A
     * credit report holds none of them, so an answer that brings one up is off course, and a
     * question about one gets a fixed explanation.
     */
    static final Pattern PROTECTED = Pattern.compile("\\b("
            // race, colour, national origin
            + "race|races|racial|racist|racism|ethnic|ethnicity|ethnicities|skin colou?r|national origin|nationality"
            + "|nationalities|immigrants?|immigration|foreign born|foreigners?|citizenship|african americans?"
            + "|asians|hispanics|latinos|latinas|arabs|mexicans|africans|caucasians?"
            // religion
            + "|religion|religions|religious|jews|jewish|muslims?|islam\\w*|christians?|hindus?|catholics?|protestants?"
            + "|sikhs?|buddhists?|mormons?|atheists?|evangelicals?"
            // sex, sexual orientation, gender identity
            + "|sex|sexes|gender|genders|women|woman s|females?|males?|sexual orientation|gay|lesbians?|bisexual\\w*"
            + "|transgender\\w*|queer|lgbt\\w*|non binary"
            // marital status (as a factor: users may still ask about joint accounts or a divorce), age,
            // public assistance, pregnancy, disability
            + "|marital status|(your|my|his|her|their) age|too old|too young|elderly|old people|young people"
            + "|public assistance|welfare|food stamps|pregnan\\w*|disabilit(y|ies)|disabled"
            // a group label in front of a person noun
            + "|(black|white|asian|hispanic|latino|arab|indian|chinese|mexican|african|jewish|muslim|catholic|sikh"
            + "|immigrant|female|male|older|younger|elderly)"
            + " (people|persons?|men|women|folks|guys|neighborhoods?|families|applicants?|borrowers?|couples?|customers?"
            + "|consumers?|households?)"
            + ")\\b");

    /** Attempts to change the assistant's instructions or role, or to read them. */
    static final Pattern INJECTION = Pattern.compile("("
            + "\\b(ignore|disregard|forget|override|bypass|skip) (all |any |the |your |these |those |of )*"
            + "(previous|prior|above|earlier|preceding|initial|original|system|your|safety)? ?"
            + "(instructions?|prompts?|rules|messages|directions|guidelines|guardrails|filters|context)\\b"
            + "|\\b(forget|ignore|disregard) everything\\b"
            + "|\\b(new task|system override|admin override|unrestricted|unfiltered|uncensored)\\b"
            + "|\\b(quote|recite) (verbatim )?(the )?(text|words|instructions|prompt) (you were given|above|before)\\b"
            + "|\\b(system|developer|hidden|initial|original|secret) (prompt|message|instructions?)\\b"
            + "|\\b(you are now|from now on you|act as|pretend (to be|you are|that)|role ?play|jailbreak|dan mode"
            + "|developer mode|do anything now|no (restrictions|limits|filters|rules))\\b"
            + "|\\bwithout (any )?(restrictions|filters|rules|guardrails|limits)\\b"
            + "|\\b(reveal|print|show|repeat|output|leak) (me )?(your|the) (instructions|prompt|rules|system|configuration)\\b"
            + "|\\b(repeat|print) (the )?(words|text) above\\b"
            + "|\\bnew instructions\\b|\\bend of (the )?(prompt|instructions|system)\\b"
            + ")");

    /**
     * Key phrases with every space removed, matched against folded text with its spaces removed: after
     * "i g n o r e p r e v i o u s" is joined, word boundaries are gone, so phrases are found this way.
     */
    static final Pattern SQUASHED_INJECTION = Pattern.compile(
            "(ignore|disregard|forget|override|bypass)(all|any|the|your)*(previous|prior|above|earlier|system)"
                    + "(instructions?|prompts?|rules|messages)|systemprompt|developermode|jailbreak|doanythingnow");

    /** Fraud terms with every space removed, for the same reason ("C.P.N." folds to "c p n"). */
    static final Pattern SQUASHED_FRAUD = Pattern.compile("cpn|creditprivacynumber|syntheticidentit");

    /** Chat-template and delimiter markers, checked on the cleaned (not folded) text. */
    static final Pattern ROLE_MARKERS = Pattern.compile(
            "(?i)(<\\|[a-z_ ]*\\|?>|\\[/?inst]|<<\\s*/?sys\\s*>>|^\\s*#{2,}\\s*(system|instruction)"
                    // a speaker label opening the text or a sentence ("AI: …", "… rules. User: ok"), not "my credit system: ok?"
                    + "|(^|[.!?]\\s+)(system|assistant|ai|bot|user|human)\\s*[:\\-]\\s"
                    + "|<\\s*/?\\s*(credit_report|user_message|history|instructions|system)\\b)",
            Pattern.MULTILINE);

    /** Payloads hidden in an encoding: long base64 or hex runs, Morse code. */
    static final Pattern ENCODED = Pattern.compile(
            "([A-Za-z0-9+/]{40,}={0,2})|(\\b[0-9a-fA-F]{40,}\\b)|((?:[.\\-]{1,6}\\s+){10,})");

    /** Someone else's credit. */
    static final Pattern OTHER_PERSON = Pattern.compile("\\b("
            + "(my|his|her|their|our) (wife|husband|spouse|partner|girlfriend|boyfriend|friend|son|daughter|kid|child"
            + "|mother|mom|father|dad|brother|sister|boss|neighbou?r|roommate|tenant|employee|ex)( s|s)? (credit|report|score"
            + "|ssn|social security|accounts?|loans?|cards?|debt)"
            + "|(credit|report|score|ssn|accounts?|debt)( score| report)? (of|for) (my|his|her|their|our) (wife|husband|spouse"
            + "|partner|girlfriend|boyfriend|friend|son|daughter|kid|child|mother|mom|father|dad|brother|sister|boss"
            + "|neighbou?r|roommate|tenant|employee|ex)"
            + "|someone else s|somebody else s|another person s|other people s|anyone else s"
            + "|(look up|lookup|check|pull|run) (a |the |his |her |their |someone s |another )?(credit|report|ssn) (for|of|on)"
            + ")\\b");

    /** Credit fraud and evasion. */
    static final Pattern FRAUD = Pattern.compile("\\b("
            + "cpn|credit privacy number|synthetic identity|fake (pay ?stubs?|identity|ssn|documents?|ids?|income)"
            + "|forge\\w*|(new|second|different) (ssn|identity|social security number)"
            + "|dispute (accurate|correct|legitimate|valid|true)|remove (accurate|correct|legitimate|valid|true)"
            + "|lie (on|to|about)|launder\\w*|steal\\w*|stolen|commit fraud|piggyback\\w* fraud|bust out"
            + ")\\b");

    /** Words that make a question about credit. A token that starts with one of these. */
    static final List<String> TOPIC_STEMS = List.of(
            "credit", "score", "fico", "report", "account", "card", "loan", "mortgage", "debt", "balance", "limit",
            "utiliz", "utilis", "payment", "pay", "paid", "late", "miss", "overdue", "delinquen", "collection",
            "inquir", "enquir", "bureau", "equifax", "experian", "transunion", "charge", "bankrupt", "lien",
            "interest", "apr", "borrow", "lend", "financ", "budget", "saving", "improv", "raise", "increas", "boost",
            "build", "rebuild", "histor", "tradeline", "revolv", "installment", "instalment", "auto", "student",
            "refinanc", "approv", "apply", "applica", "lock", "freeze", "dispute", "error", "negative", "positive",
            "factor", "rating", "owe", "owing", "rent", "oldest", "newest");

    /** Greetings and thanks, answered politely rather than refused as off topic. */
    static final Set<String> SMALL_TALK = Set.of(
            "hi", "hello", "hey", "thanks", "thank you", "thank you so much", "thanks a lot", "good morning",
            "good afternoon", "good evening", "ok", "okay", "ok thanks", "great", "cool", "bye", "goodbye");

    static boolean anyTokenStartsWith(String folded, List<String> stems) {
        return Arrays.stream(folded.split(" ")).anyMatch(token -> stems.stream().anyMatch(token::startsWith));
    }

    static boolean anyToken(String folded, Set<String> words) {
        return Arrays.stream(folded.split(" ")).anyMatch(words::contains);
    }
}
