package com.creditagent.advisor.guard;

/** Markers the prompt is built with; an answer that repeats one is leaking the prompt. */
public final class PromptMarkers {

    /** Opens the instructions, so a leak of them is recognisable. */
    public static final String SENTINEL = "CREDITAGENT-ADVISOR-INSTRUCTIONS";

    private PromptMarkers() {}
}
