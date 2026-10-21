package com.bank.account.service;

import com.bank.account.dto.LedgerEntryResponse;
import com.bank.account.dto.StatementResponse;
import com.bank.account.entity.Account;
import com.bank.account.entity.AccountLedgerEntry;
import com.bank.account.enums.EntryTypeEnum;
import com.bank.account.repository.AccountLedgerEntryRepository;
import com.bank.common.exception.BadRequestException;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Sao kê tài khoản: số dư đầu kỳ (balanceAfter của bút toán cuối cùng trước kỳ), các bút toán trong kỳ,
 * số dư cuối kỳ. Xuất JSON, CSV hoặc PDF (font Liberation Sans hỗ trợ tiếng Việt).
 * OpenPDF 2.x đánh dấu deprecated package com.lowagie (sẽ đổi tên ở 3.x) nhưng vẫn là API chính thức của 2.x.
 */
@SuppressWarnings("deprecation")
@Service
@RequiredArgsConstructor
public class StatementService {

    private static final int MAX_DAYS = 366;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private static final String FONT_REGULAR = "liberation/LiberationSans-Regular.ttf";
    private static final String FONT_BOLD = "liberation/LiberationSans-Bold.ttf";
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private final AccountService accountService;
    private final AccountLedgerEntryRepository ledgerEntryRepository;

    @Transactional(readOnly = true)
    public StatementResponse getStatement(String accountNumber, LocalDate fromDate, LocalDate toDate) {
        if (fromDate.isAfter(toDate)) {
            throw new BadRequestException("INVALID_PERIOD", "Ngày bắt đầu phải trước hoặc bằng ngày kết thúc");
        }
        if (fromDate.plusDays(MAX_DAYS).isBefore(toDate)) {
            throw new BadRequestException("INVALID_PERIOD", "Kỳ sao kê tối đa " + MAX_DAYS + " ngày");
        }
        Account account = accountService.findAccessibleAccount(accountNumber);
        BigDecimal opening = ledgerEntryRepository
                .findFirstByAccountIdAndCreatedAtBeforeOrderByIdDesc(account.getId(), fromDate.atStartOfDay())
                .map(AccountLedgerEntry::getBalanceAfter)
                .orElse(BigDecimal.ZERO);
        List<AccountLedgerEntry> entries = ledgerEntryRepository.findForStatement(account.getId(),
                fromDate.atStartOfDay(), toDate.plusDays(1).atStartOfDay());
        BigDecimal totalDebit = sum(entries, EntryTypeEnum.DEBIT);
        BigDecimal totalCredit = sum(entries, EntryTypeEnum.CREDIT);
        BigDecimal closing = entries.isEmpty() ? opening : entries.getLast().getBalanceAfter();
        return new StatementResponse(account.getAccountNumber(), account.getCurrency(), fromDate, toDate, opening,
                closing, totalDebit, totalCredit, entries.stream().map(AccountService::toLedgerResponse).toList());
    }

    public byte[] toCsv(StatementResponse statement) {
        StringBuilder csv = new StringBuilder();
        csv.append("Sao kê tài khoản,").append(statement.accountNumber()).append('\n');
        csv.append("Kỳ,").append(DATE.format(statement.fromDate())).append(" - ")
                .append(DATE.format(statement.toDate())).append('\n');
        csv.append("Số dư đầu kỳ,").append(plain(statement.openingBalance())).append('\n');
        csv.append("Thời gian,Mã giao dịch,Loại,Số tiền,Số dư sau,Diễn giải\n");
        for (LedgerEntryResponse entry : statement.entries()) {
            csv.append(DATE_TIME.format(entry.createdAt())).append(',')
                    .append(entry.transactionRef()).append(',')
                    .append(entry.entryType()).append(',')
                    .append(plain(entry.amount())).append(',')
                    .append(plain(entry.balanceAfter())).append(',')
                    .append(escapeCsv(entry.description())).append('\n');
        }
        csv.append("Tổng ghi nợ,").append(plain(statement.totalDebit())).append('\n');
        csv.append("Tổng ghi có,").append(plain(statement.totalCredit())).append('\n');
        csv.append("Số dư cuối kỳ,").append(plain(statement.closingBalance())).append('\n');
        byte[] body = csv.toString().getBytes(StandardCharsets.UTF_8);
        // BOM để Excel nhận đúng UTF-8 (tiếng Việt)
        byte[] withBom = new byte[UTF8_BOM.length + body.length];
        System.arraycopy(UTF8_BOM, 0, withBom, 0, UTF8_BOM.length);
        System.arraycopy(body, 0, withBom, UTF8_BOM.length, body.length);
        return withBom;
    }

    public byte[] toPdf(StatementResponse statement) {
        Font regular = font(FONT_REGULAR, 9);
        Font bold = font(FONT_BOLD, 9);
        Font title = font(FONT_BOLD, 14);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4.rotate(), 30, 30, 30, 30);
        try {
            PdfWriter.getInstance(document, out);
            document.open();
            Paragraph heading = new Paragraph("SAO KÊ TÀI KHOẢN", title);
            heading.setAlignment(Element.ALIGN_CENTER);
            document.add(heading);
            document.add(new Paragraph("Số tài khoản: " + statement.accountNumber() + "    Loại tiền: "
                    + statement.currency(), regular));
            document.add(new Paragraph("Kỳ sao kê: " + DATE.format(statement.fromDate()) + " - "
                    + DATE.format(statement.toDate()), regular));
            document.add(new Paragraph("Số dư đầu kỳ: " + plain(statement.openingBalance()), bold));
            document.add(new Paragraph(" ", regular));

            PdfPTable table = new PdfPTable(new float[]{2.2f, 3f, 1.2f, 2f, 2f, 4f});
            table.setWidthPercentage(100);
            for (String header : List.of("Thời gian", "Mã giao dịch", "Loại", "Số tiền", "Số dư sau", "Diễn giải")) {
                table.addCell(new PdfPCell(new Phrase(header, bold)));
            }
            for (LedgerEntryResponse entry : statement.entries()) {
                table.addCell(new Phrase(DATE_TIME.format(entry.createdAt()), regular));
                table.addCell(new Phrase(entry.transactionRef(), regular));
                table.addCell(new Phrase(entry.entryType() == EntryTypeEnum.DEBIT ? "Ghi nợ" : "Ghi có", regular));
                table.addCell(rightAligned(plain(entry.amount()), regular));
                table.addCell(rightAligned(plain(entry.balanceAfter()), regular));
                table.addCell(new Phrase(entry.description() == null ? "" : entry.description(), regular));
            }
            document.add(table);
            document.add(new Paragraph(" ", regular));
            document.add(new Paragraph("Tổng ghi nợ: " + plain(statement.totalDebit()), regular));
            document.add(new Paragraph("Tổng ghi có: " + plain(statement.totalCredit()), regular));
            document.add(new Paragraph("Số dư cuối kỳ: " + plain(statement.closingBalance()), bold));
        } catch (DocumentException e) {
            throw new IllegalStateException("Không tạo được file PDF sao kê", e);
        } finally {
            document.close();
        }
        return out.toByteArray();
    }

    private static PdfPCell rightAligned(String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        return cell;
    }

    private static Font font(String resource, float size) {
        try (InputStream in = StatementService.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Thiếu font " + resource + " (openpdf-fonts-extra)");
            }
            BaseFont baseFont = BaseFont.createFont(resource, BaseFont.IDENTITY_H, BaseFont.EMBEDDED, true,
                    in.readAllBytes(), null);
            return new Font(baseFont, size);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (DocumentException e) {
            throw new IllegalStateException("Không nạp được font " + resource, e);
        }
    }

    private static BigDecimal sum(List<AccountLedgerEntry> entries, EntryTypeEnum type) {
        return entries.stream()
                .filter(entry -> entry.getEntryType() == type)
                .map(AccountLedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String plain(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private static String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        // Chặn CSV injection (công thức Excel) và escape dấu phẩy / nháy kép
        String safe = value.matches("^[=+\\-@].*") ? "'" + value : value;
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n")) {
            return "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }
}
