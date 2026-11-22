package com.creditagent.web.subscriptions;

/** A product that cannot be bought yet, or no such product. Maps to 400. */
public class ProductNotOfferedException extends RuntimeException {

    public ProductNotOfferedException() {
        super("That subscription is not offered.");
    }
}
