package com.paymentplatform.ledger_service.account;
public enum AccountType {
    ASSET, LIABILITY, EQUITY, INCOME, EXPENSE;
    public String normalBalance() { return this == ASSET || this == EXPENSE ? "DEBIT" : "CREDIT"; }
}
