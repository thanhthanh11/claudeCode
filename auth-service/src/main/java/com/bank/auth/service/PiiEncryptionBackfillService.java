package com.bank.auth.service;

import com.bank.common.crypto.BlindIndexer;
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
 * Mã hóa USERS.EMAIL lưu trước migration 14 và tính EMAIL_HASH.
 * Idempotent, an toàn khi nhiều instance cùng khởi động (xem {@link ColumnEncryptionBackfill}).
 */
@Service
public class PiiEncryptionBackfillService implements ApplicationRunner {

    private final ColumnEncryptionBackfill backfill;
    private final EncryptionKeyring keyring;

    public PiiEncryptionBackfillService(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate,
                                        EncryptionKeyring keyring) {
        this.backfill = new ColumnEncryptionBackfill(jdbcTemplate, transactionTemplate, keyring);
        this.keyring = keyring;
    }

    @Override
    public void run(ApplicationArguments args) {
        BlindIndexer indexer = keyring.blindIndexer();
        backfill.run(new ColumnEncryptionBackfill.Spec("USERS",
                "EMAIL_HASH IS NULL OR EMAIL NOT LIKE 'enc:v1:%'", List.of("EMAIL"), List.of(),
                Map.of("EMAIL_HASH", row -> indexer.hashEmail(row.get("EMAIL")))));
    }
}
