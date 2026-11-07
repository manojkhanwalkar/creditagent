package com.creditagent.score;

import com.creditagent.common.CreditReport;
import com.creditagent.common.CreditScore;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1/scores", produces = MediaType.APPLICATION_JSON_VALUE)
class ScoreController {

    private final ScoreCalculator calculator;

    ScoreController(ScoreCalculator calculator) {
        this.calculator = calculator;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    CreditScore score(@RequestBody CreditReport report) {
        return calculator.calculate(report);
    }
}
