package com.bank.transaction.service;

import com.bank.common.crypto.ColumnEncryptionBackfill;
import com.bank.common.crypto.EncryptionKeyring;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

/**
 * Mã hóa PAYLOAD (envelope chứa số tài khoản, số tiền) của OUTBOX_EVENTS và DEAD_LETTER_EVENTS lưu trước khi bật mã hóa cột.
 * Idempotent, an toàn khi nhiều instance cùng khởi động (xem {@link ColumnEncryptionBackfill}).
 */
@Service
public class PiiEncryptionBackfillService implements ApplicationRunner {

    private final ColumnEncryptionBackfill backfill;

    public PiiEncryptionBackfillService(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate,
                                        EncryptionKeyring keyring) {
        this.backfill = new ColumnEncryptionBackfill(jdbcTemplate, transactionTemplate, keyring);
    }

    @Override
    public void run(ApplicationArguments args) {
        backfill.run(new ColumnEncryptionBackfill.Spec("OUTBOX_EVENTS",
                "DBMS_LOB.SUBSTR(PAYLOAD, 7, 1) <> 'enc:v1:'", List.of("PAYLOAD"), List.of(), Map.of()));
        backfill.run(new ColumnEncryptionBackfill.Spec("DEAD_LETTER_EVENTS",
                "PAYLOAD IS NOT NULL AND DBMS_LOB.SUBSTR(PAYLOAD, 7, 1) <> 'enc:v1:'", List.of("PAYLOAD"),
                List.of(), Map.of()));
    }
}
