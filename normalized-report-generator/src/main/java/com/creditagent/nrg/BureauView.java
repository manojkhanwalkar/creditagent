package com.creditagent.nrg;

import com.creditagent.common.bureau.Bureau;
import java.time.LocalDate;
import java.util.List;

/** One bureau's report, read out of its own format into common terms. */
record BureauView(
        Bureau bureau,
        LocalDate reportDate,
        String fullName,
        String ssnLastFour,
        List<ReportedAccount> accounts,
        List<ReportedInquiry> inquiries,
        int collections,
        int publicRecords) {}
