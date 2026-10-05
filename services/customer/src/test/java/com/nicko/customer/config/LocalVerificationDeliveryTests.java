package com.nicko.customer.config;

import com.nicko.customer.entity.enums.ContactType;
import com.nicko.customer.service.LocalVerificationDelivery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LocalVerificationDeliveryTests {
    @TempDir Path directory;
    @Test
    @SuppressWarnings("unchecked")
    void localInboxAndEmailDeliveryAreUnavailableOutsideLocalProfile() throws Exception {
        var environment = new MockEnvironment();
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        JavaMailSender mail = mock(JavaMailSender.class);
        when(provider.getObject()).thenReturn(mail);
        var delivery = new LocalVerificationDelivery(environment, provider, directory.toString());
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> delivery.send(id, ContactType.PHONE, "+254712345678", "123456"))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(Files.exists(directory.resolve(id + ".txt"))).isFalse();
        verifyNoInteractions(mail);
        environment.setActiveProfiles("local");
        delivery.send(id, ContactType.PHONE, "+254712345678", "123456");
        Path message = directory.resolve(id + ".txt");
        assertThat(Files.readString(message)).isEqualTo("123456" + System.lineSeparator());
        assertThat(Files.getPosixFilePermissions(message)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        delivery.send(UUID.randomUUID(), ContactType.EMAIL, "local@example.com", "654321");
        var captured = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(captured.capture());
        assertThat(captured.getValue().getTo()).containsExactly("local@example.com");
        assertThat(captured.getValue().getText()).contains("654321");
    }
}
