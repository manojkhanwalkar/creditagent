package com.creditagent.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * Spring's DefaultHandlerExceptionResolver logs a rejected request at WARN with the field values it
 * rejected: an over-long SSN or password sent to the sign-in forms would land in the log. The web
 * server turns that logger down, as the report generators and the alerts service do.
 */
class LogHygieneConfigTest {

    @Test
    void rejectedFieldValuesAreNotLogged() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();
        assertThat(properties).isNotNull();
        assertThat(properties.getProperty(
                "logging.level.org.springframework.web.servlet.mvc.support.DefaultHandlerExceptionResolver"))
                .isEqualTo("error");
    }
}
