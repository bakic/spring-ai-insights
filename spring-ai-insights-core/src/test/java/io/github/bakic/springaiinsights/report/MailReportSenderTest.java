package io.github.bakic.springaiinsights.report;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;

import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class MailReportSenderTest {

    private final List<MimeMessage> sent = new ArrayList<>();

    private final JavaMailSenderImpl mail = new JavaMailSenderImpl() {
        @Override
        protected void doSend(MimeMessage[] messages, Object[] originals) {
            sent.addAll(List.of(messages));
        }
    };

    @Test
    void sendsHtmlAndTextAlternatives() throws Exception {
        MailReportSender sender = new MailReportSender(mail, "insights@example.com",
                List.of("ceo@example.com", "cto@example.com"));
        assertThat(sender.name()).isEqualTo("email");
        sender.send(ReportFixtures.REPORT);
        assertThat(sent).hasSize(1);
        MimeMessage message = sent.get(0);
        message.saveChanges();
        assertThat(message.getSubject()).isEqualTo(ReportFormat.subject(ReportFixtures.REPORT));
        assertThat(message.getFrom()).containsExactly(new InternetAddress("insights@example.com"));
        assertThat(message.getRecipients(Message.RecipientType.TO)).extracting(Address::toString)
            .containsExactly("ceo@example.com", "cto@example.com");
        Map<String, String> parts = new HashMap<>();
        collect(message, parts);
        assertThat(parts.get("text/plain")).isEqualTo(ReportFormat.text(ReportFixtures.REPORT));
        assertThat(parts.get("text/html")).isEqualTo(ReportFormat.html(ReportFixtures.REPORT));
    }

    @Test
    void fromIsOptional() throws Exception {
        new MailReportSender(mail, null, List.of("ceo@example.com")).send(ReportFixtures.REPORT);
        assertThat(sent.get(0).getHeader("From")).isNull();
    }

    @Test
    void requiresRecipients() {
        assertThatIllegalArgumentException().isThrownBy(() -> new MailReportSender(mail, null, List.of()));
    }

    private static void collect(Part part, Map<String, String> parts) throws Exception {
        Object content = part.getContent();
        if (content instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart child = multipart.getBodyPart(i);
                collect(child, parts);
            }
        }
        else if (content instanceof String text) {
            parts.put(part.getContentType().split(";")[0].strip().toLowerCase(), text);
        }
    }
}
