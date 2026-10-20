package io.github.bakic.springaiinsights.report;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import jakarta.mail.internet.MimeMessage;
import org.jspecify.annotations.Nullable;

import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

/**
 * Emails the weekly report as a plain-text and HTML alternative.
 */
public final class MailReportSender implements ReportSender {

    private final JavaMailSender mail;

    private final @Nullable String from;

    private final List<String> to;

    public MailReportSender(JavaMailSender mail, @Nullable String from, List<String> to) {
        this.mail = Objects.requireNonNull(mail, "mail");
        this.from = (from == null || from.isBlank()) ? null : from;
        this.to = List.copyOf(to);
        if (this.to.isEmpty()) {
            throw new IllegalArgumentException("The weekly report needs at least one email recipient");
        }
    }

    @Override
    public String name() {
        return "email";
    }

    @Override
    public void send(WeeklyReport report) throws Exception {
        MimeMessage message = this.mail.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED,
                StandardCharsets.UTF_8.name());
        if (this.from != null) {
            helper.setFrom(this.from);
        }
        helper.setTo(this.to.toArray(String[]::new));
        helper.setSubject(ReportFormat.subject(report));
        helper.setText(ReportFormat.text(report), ReportFormat.html(report));
        this.mail.send(message);
    }

}
