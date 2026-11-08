package com.creditagent.generator;

import com.creditagent.common.InvalidSsnException;
import com.creditagent.common.Ssn;
import com.creditagent.common.bureau.Bureau;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal API. Not exposed to browsers; see docs/security-review.md. */
@RestController
@RequestMapping(path = "/api/v1/reports", produces = MediaType.APPLICATION_JSON_VALUE)
class ReportController {

    private final CreditReportService service;

    ReportController(CreditReportService service) {
        this.service = service;
    }

    record GenerateReportRequest(@NotBlank String ssn, @Size(max = 100) String fullName) {
        @Override
        public String toString() {
            return "GenerateReportRequest[ssn=<redacted>]";
        }
    }

    /** Deliberately excludes account details: the caller only needs to know which files now exist. */
    record GenerateReportResponse(String reportId, String ssnMasked, List<String> bureaus) {}

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<GenerateReportResponse> generate(@Valid @RequestBody GenerateReportRequest request) {
        Ssn ssn = Ssn.parse(request.ssn());
        CreditReportService.GeneratedReports generated = service.generate(ssn, request.fullName());
        String reportId = generated.report().reportId();
        List<String> bureaus = generated.bureaus().stream().map(Bureau::directoryName).toList();
        return ResponseEntity.created(URI.create("/api/v1/reports/" + reportId))
                .body(new GenerateReportResponse(reportId, generated.report().ssnMasked(), bureaus));
    }

    @ExceptionHandler(InvalidSsnException.class)
    ProblemDetail invalidSsn(InvalidSsnException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}
