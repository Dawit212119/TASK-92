package com.civicworks.unit;

import com.civicworks.service.NotificationTemplateService;
import com.civicworks.service.NotificationTemplateService.RenderedTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link NotificationTemplateService}.
 *
 * Covers:
 *  - built-in OVERDUE_BILL_REMINDER template renders correctly
 *  - placeholder substitution for subject and body
 *  - unknown template key throws IllegalArgumentException
 *  - custom template registration and rendering
 *  - unresolved placeholders left as-is (graceful)
 *  - ReminderNotificationJob variable set produces non-hardcoded output
 */
class NotificationTemplateTest {

    private NotificationTemplateService templateService;

    @BeforeEach
    void setUp() {
        templateService = new NotificationTemplateService();
    }

    @Test
    void builtIn_overdueBillReminder_templateExists() {
        assertThat(templateService.hasTemplate("OVERDUE_BILL_REMINDER")).isTrue();
    }

    @Test
    void overdueBillReminder_rendersSubjectWithBillId() {
        UUID billId = UUID.randomUUID();
        RenderedTemplate rendered = templateService.render("OVERDUE_BILL_REMINDER", Map.of(
                "billId",       billId.toString(),
                "balanceCents", "5000",
                "entityRef",    "bills/" + billId));

        assertThat(rendered.subject()).contains(billId.toString());
        assertThat(rendered.subject()).doesNotContain("{{billId}}");
    }

    @Test
    void overdueBillReminder_rendersBodyWithBalanceAndEntityRef() {
        UUID billId = UUID.randomUUID();
        String entityRef = "bills/" + billId;
        RenderedTemplate rendered = templateService.render("OVERDUE_BILL_REMINDER", Map.of(
                "billId",       billId.toString(),
                "balanceCents", "12500",
                "entityRef",    entityRef));

        assertThat(rendered.body()).contains("12500");
        assertThat(rendered.body()).contains(entityRef);
        assertThat(rendered.body()).doesNotContain("{{balanceCents}}");
        assertThat(rendered.body()).doesNotContain("{{entityRef}}");
    }

    @Test
    void overdueBillReminder_outputIsNotHardcoded() {
        // The rendered text must differ across different bill IDs —
        // proving the scheduler is NOT emitting a single hardcoded string.
        UUID billA = UUID.randomUUID();
        UUID billB = UUID.randomUUID();

        RenderedTemplate ra = templateService.render("OVERDUE_BILL_REMINDER", Map.of(
                "billId", billA.toString(), "balanceCents", "100", "entityRef", "bills/" + billA));
        RenderedTemplate rb = templateService.render("OVERDUE_BILL_REMINDER", Map.of(
                "billId", billB.toString(), "balanceCents", "200", "entityRef", "bills/" + billB));

        assertThat(ra.subject()).isNotEqualTo(rb.subject());
        assertThat(ra.body()).isNotEqualTo(rb.body());
    }

    @Test
    void unknownTemplateKey_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> templateService.render("NO_SUCH_TEMPLATE", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NO_SUCH_TEMPLATE");
    }

    @Test
    void customTemplate_registeredAndRendered() {
        templateService.register("CUSTOM_KEY",
                "Hello {{name}}",
                "Dear {{name}}, your ref is {{ref}}.");

        RenderedTemplate rendered = templateService.render("CUSTOM_KEY", Map.of(
                "name", "Alice",
                "ref",  "REF-001"));

        assertThat(rendered.subject()).isEqualTo("Hello Alice");
        assertThat(rendered.body()).isEqualTo("Dear Alice, your ref is REF-001.");
    }

    @Test
    void unresolvedPlaceholder_leftAsIs() {
        templateService.register("PARTIAL", "Hi {{name}}", "Body {{missing}}");
        RenderedTemplate rendered = templateService.render("PARTIAL", Map.of("name", "Bob"));

        assertThat(rendered.subject()).isEqualTo("Hi Bob");
        assertThat(rendered.body()).isEqualTo("Body {{missing}}");
    }
}
