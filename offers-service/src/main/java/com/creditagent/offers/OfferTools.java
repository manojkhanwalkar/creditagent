package com.creditagent.offers;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.util.Objects;
import tools.jackson.databind.json.JsonMapper;

/**
 * The MCP tool. It is the one way into the offers logic: the REST API calls it through the MCP server
 * too ({@link OffersRestController}), so both interfaces always answer the same.
 */
public class OfferTools {

    static final String TOOL = "get_credit_card_offers";

    private final OfferEngine engine;
    private final JsonMapper json;

    public OfferTools(OfferEngine engine, JsonMapper json) {
        this.engine = Objects.requireNonNull(engine);
        this.json = Objects.requireNonNull(json);
    }

    /**
     * @return the offers as JSON
     * @throws IllegalArgumentException for a score out of range or a malformed ZIP code; the MCP server
     *     turns it into an error result
     */
    @Tool(name = TOOL, value = "Returns up to 10 credit card offers ranked for a credit score (300-850) and a US ZIP"
            + " code, with each card's Schumer-box terms (APRs, fees), rewards and approval odds. Not firm offers.")
    public String getCreditCardOffers(
            @P("Credit score, 300 to 850") int score,
            @P(value = "5-digit US ZIP code; leave empty for nationwide offers only", required = false) String zip) {
        return json.writeValueAsString(engine.offers(score, zip));
    }
}
