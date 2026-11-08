package com.creditagent.nrg;

import com.creditagent.common.CreditAccount;
import java.time.LocalDate;

/** An account as one bureau reports it, with the date that bureau last updated it. */
record ReportedAccount(AccountKey key, CreditAccount account, LocalDate reportedOn) {}
