package com.civicworks.service;

import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Minimal in-process notification template engine.
 *
 * <p>Templates are registered at startup as simple mustache-style strings where
 * placeholders are written as {@code {{variableName}}}.  No external files or
 * database table is required — the registry is populated via
 * {@link #register(String, String, String)} from any {@code @PostConstruct} or
 * Spring {@code ApplicationContext} initialiser.
 *
 * <p>Built-in templates (e.g. "OVERDUE_BILL_REMINDER") are registered in this
 * class's constructor so the service is self-contained for offline/test use.
 */
@Service
public class NotificationTemplateService {

    /** Rendered subject and body for a given template key. */
    public record RenderedTemplate(String subject, String body) {}

    private record Template(String subjectPattern, String bodyPattern) {}

    private final Map<String, Template> registry;

    public NotificationTemplateService() {
        registry = new java.util.concurrent.ConcurrentHashMap<>();

        // Built-in: daily overdue-bill reminder (used by ReminderNotificationJob)
        register("OVERDUE_BILL_REMINDER",
                "Overdue bill reminder: bill {{billId}}",
                "Bill {{billId}} assigned to your organisation is overdue. "
                        + "Outstanding balance: {{balanceCents}} cents. "
                        + "Please review at {{entityRef}}.");
    }

    /**
     * Registers (or replaces) a template.
     *
     * @param key            unique template identifier
     * @param subjectPattern subject line — may contain {@code {{var}}} placeholders
     * @param bodyPattern    body — may contain {@code {{var}}} placeholders
     */
    public void register(String key, String subjectPattern, String bodyPattern) {
        registry.put(key, new Template(subjectPattern, bodyPattern));
    }

    /**
     * Renders the template identified by {@code key}, substituting each
     * {@code {{name}}} placeholder with the corresponding value from
     * {@code variables}.  Unknown placeholders are left as-is.
     *
     * @throws IllegalArgumentException if no template is registered for {@code key}
     */
    public RenderedTemplate render(String key, Map<String, String> variables) {
        Template t = registry.get(key);
        if (t == null) {
            throw new IllegalArgumentException("No notification template registered for key: " + key);
        }
        return new RenderedTemplate(substitute(t.subjectPattern(), variables),
                                    substitute(t.bodyPattern(), variables));
    }

    /** Returns {@code true} if a template with the given key exists. */
    public boolean hasTemplate(String key) {
        return registry.containsKey(key);
    }

    private static String substitute(String pattern, Map<String, String> vars) {
        String result = pattern;
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return result;
    }
}
