package com.creditagent.nrg;

import java.time.LocalDate;

/** A hard inquiry. The same pull may be listed by more than one bureau. */
record ReportedInquiry(String subscriber, LocalDate date) {

    ReportedInquiry {
        subscriber = AccountKey.normalise(subscriber);
    }
}
