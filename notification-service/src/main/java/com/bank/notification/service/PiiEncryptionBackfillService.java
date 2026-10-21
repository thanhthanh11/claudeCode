package com.bank.notification.service;

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
 * Mã hóa NOTIFICATIONS.RECIPIENT lưu trước migration 15.
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
        backfill.run(new ColumnEncryptionBackfill.Spec("NOTIFICATIONS",
                "RECIPIENT NOT LIKE 'enc:v1:%'", List.of("RECIPIENT"), List.of(), Map.of()));
    }
}
