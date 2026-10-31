package com.thabendra.telecom.subscriber;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.UncategorizedSQLException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class OracleErrorsTest {

    private static UncategorizedSQLException oracleError(int code, String text) {
        return new UncategorizedSQLException("call", "CALL x",
                new SQLException("ORA-" + code + ": " + text + "\nORA-06512: at \"TELECOM.RECORD_USAGE\"", "72000", code));
    }

    @Test
    void insufficientQuotaIsBusinessRule() {
        assertThat(OracleErrors.translate(oracleError(20005, "Insufficient data and balance")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("Insufficient data and balance");
    }

    @Test
    void unknownPackageIsNotFound() {
        assertThat(OracleErrors.translate(oracleError(20002, "Package GOLD not found")))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Package GOLD not found");
    }

    @Test
    void oracleCodeIsFoundUnderDriverDetail() {
        // the real driver nests a non-SQL "Error : 20004 ..." exception below the SQLException
        SQLException sql = new SQLException("ORA-20004: Insufficient balance: 100, plan fee is 1199", "72000", 20004,
                new RuntimeException("Error : 20004, Position : 0"));
        assertThat(OracleErrors.translate(new UncategorizedSQLException("call", "CALL x", sql)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("Insufficient balance: 100, plan fee is 1199");
    }

    @Test
    void otherOracleErrorsAreLeftAlone() {
        UncategorizedSQLException deadlock = oracleError(60, "deadlock detected");
        assertThat(OracleErrors.translate(deadlock)).isSameAs(deadlock);
    }
}
