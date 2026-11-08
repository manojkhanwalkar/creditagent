package com.creditagent.nrg;

import com.creditagent.common.CreditReport;
import com.creditagent.common.bureau.BureauReports;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal API, called by the Credit Report MCP server: the three bureau reports in, one normalized
 * {@link CreditReport} out.
 */
@RestController
@RequestMapping(path = "/api/v1/normalized-reports", produces = MediaType.APPLICATION_JSON_VALUE)
class NormalizeController {

    private final ReportNormalizer normalizer;

    NormalizeController(ReportNormalizer normalizer) {
        this.normalizer = normalizer;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    CreditReport normalize(@RequestBody BureauReports reports) {
        return normalizer.normalize(reports);
    }

    @ExceptionHandler(InvalidBureauReportException.class)
    ProblemDetail invalidReport(InvalidBureauReportException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
    }
}
