package bank.tessera.customer.data;

import java.sql.Date;

/**
 * One generated customer, identity included. Test scope on purpose: code that manufactures personal
 * data has no business inside a deployable artefact, and keeping it out of the WAR is a cheaper
 * control than any review of what it produces.
 */
public final class SyntheticCustomer {

    private final String customerRef;
    private final String familyName;
    private final String givenName;
    private final Date dateOfBirth;
    private final String nationalId;
    private final Date onboardedDate;

    SyntheticCustomer(String customerRef, String familyName, String givenName, Date dateOfBirth,
            String nationalId, Date onboardedDate) {
        this.customerRef = customerRef;
        this.familyName = familyName;
        this.givenName = givenName;
        this.dateOfBirth = dateOfBirth;
        this.nationalId = nationalId;
        this.onboardedDate = onboardedDate;
    }

    public String customerRef() {
        return customerRef;
    }

    public String familyName() {
        return familyName;
    }

    public String givenName() {
        return givenName;
    }

    public Date dateOfBirth() {
        return dateOfBirth;
    }

    public String nationalId() {
        return nationalId;
    }

    public Date onboardedDate() {
        return onboardedDate;
    }
}
