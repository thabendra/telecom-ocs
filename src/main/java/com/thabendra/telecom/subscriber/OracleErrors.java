package com.thabendra.telecom.subscriber;

import org.springframework.dao.DataAccessException;

import java.sql.SQLException;

/**
 * Maps the RAISE_APPLICATION_ERROR codes used by the PL/SQL procedures to domain exceptions:
 * ORA-20001/20002/20007 not found, ORA-20003..20006/20008..20010 business rule broken.
 */
public final class OracleErrors {

    private OracleErrors() {
    }

    public static RuntimeException translate(DataAccessException e) {
        SQLException sql = firstSqlException(e);
        if (sql != null) {
            String message = message(sql);
            switch (sql.getErrorCode()) {
                case 20001, 20002, 20007 -> { return new NotFoundException(message); }
                case 20003, 20004, 20005, 20006, 20008, 20009, 20010 -> { return new BusinessRuleException(message); }
                default -> { }
            }
        }
        return e;
    }

    // The Oracle driver puts its own non-SQLException detail under the SQLException,
    // so the deepest cause doesn't carry the ORA code: take the first SQLException instead.
    private static SQLException firstSqlException(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql;
            }
        }
        return null;
    }

    // "ORA-20004: Insufficient balance ...\nORA-06512: at ..." -> "Insufficient balance ..."
    private static String message(SQLException e) {
        String first = e.getMessage().lines().findFirst().orElse("");
        int colon = first.indexOf(": ");
        return colon >= 0 ? first.substring(colon + 2) : first;
    }
}
