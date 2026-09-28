package com.nicko.customer.service;

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.NumberParseException;
import com.nicko.customer.customer.enums.ContactType;
import com.nicko.customer.dto.CustomerContactRequest;
import org.springframework.stereotype.Component;
import java.util.Locale;

@Component
public class ContactNormalizer {
    public String normalize(CustomerContactRequest request) {
        String value = request.contactValue().strip();
        if (request.contactType() == ContactType.EMAIL) { return value.toLowerCase(Locale.ROOT); }
        if (!value.matches("[+0-9() .-]+") || (!value.startsWith("+") && request.phoneRegion() == null)) {
            throw new FieldValidationException("contactValue", "use an international phone number or provide phoneRegion");
        }
        var phone = PhoneNumberUtil.getInstance();
        try {
            var parsed = phone.parse(value, request.phoneRegion());
            if (!phone.isValidNumber(parsed) || parsed.hasExtension()) { throw new NumberParseException(NumberParseException.ErrorType.NOT_A_NUMBER, "Invalid"); }
            return phone.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164);
        } catch (NumberParseException exception) {
            throw new FieldValidationException("contactValue", "must be a valid phone number for the supplied region");
        }
    }
}
