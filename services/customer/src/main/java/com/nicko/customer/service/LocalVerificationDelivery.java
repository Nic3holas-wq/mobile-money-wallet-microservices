package com.nicko.customer.service;

import com.nicko.customer.entity.enums.ContactType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;

/** Local development adapter only. Production delivery belongs to Notification Service. */
@Service
public class LocalVerificationDelivery implements VerificationDelivery {
    private final Environment environment;
    private final ObjectProvider<JavaMailSender> mail;
    private final Path inbox;
    public LocalVerificationDelivery(Environment environment, ObjectProvider<JavaMailSender> mail,
            @Value("${app.verification.sms-inbox:.local/customer-sms}") String inbox) {
        this.environment = environment; this.mail = mail; this.inbox = Path.of(inbox);
    }
    @Override
    public void send(UUID challengeId, ContactType type, String destination, String code) {
        if (!environment.matchesProfiles("local")) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Configure a verification delivery adapter or enable the local development profile");
        }
        try {
            if (type == ContactType.EMAIL) {
                var message = new SimpleMailMessage();
                message.setFrom("verification@verapay.test");
                message.setTo(destination);
                message.setSubject("VeraPay contact verification");
                message.setText("Your verification code is " + code + ". It expires in 5 minutes.");
                mail.getObject().send(message);
            } else {
                // No public API and no logging of OTPs. Only a local developer can read this test inbox.
                Files.createDirectories(inbox, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                Files.setPosixFilePermissions(inbox, PosixFilePermissions.fromString("rwx------"));
                Path file = Files.createFile(inbox.resolve(challengeId + ".txt"),
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                Files.writeString(file, code + System.lineSeparator(), StandardOpenOption.WRITE);
            }
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Unable to deliver verification code; retry later");
        }
    }
}
