package com.paymentplatform.ledger_service;
import com.paymentplatform.ledger_service.api.FinancialJsonConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
class FinancialJsonTests {
    public record Input(Long amount,String pin) { }
    @Test void bootMapperRejectsLossyMoneyAndNumericPinCoercions() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .withUserConfiguration(FinancialJsonConfiguration.class).run(context -> {
                    var mapper=context.getBean(ObjectMapper.class);
                    assertThat(mapper.readValue("{\"amount\":100,\"pin\":\"012345\"}",Input.class)).isEqualTo(new Input(100L,"012345"));
                    for(String value:java.util.List.of("100.5","\"100\"","true","9223372036854775808"))
                        assertThatThrownBy(()->mapper.readValue("{\"amount\":"+value+",\"pin\":\"012345\"}",Input.class)).isInstanceOf(RuntimeException.class);
                    assertThatThrownBy(()->mapper.readValue("{\"amount\":100,\"pin\":123456}",Input.class)).isInstanceOf(RuntimeException.class);
                });
    }
}
