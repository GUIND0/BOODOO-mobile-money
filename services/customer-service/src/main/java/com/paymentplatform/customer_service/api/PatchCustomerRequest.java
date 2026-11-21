package com.paymentplatform.customer_service.api;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;
public record PatchCustomerRequest(@Size(min=1,max=100) String firstName, @Size(min=1,max=100) String lastName,
        @Past LocalDate dateOfBirth, String nationality, String residenceCountry,
        @Size(min=1,max=200) String addressLine, @Size(min=1,max=100) String city) {
    private static final Set<String> COUNTRIES = Set.of(Locale.getISOCountries());
    public PatchCustomerRequest {
        firstName=trim(firstName);lastName=trim(lastName);addressLine=trim(addressLine);city=trim(city);
        nationality=country(nationality);residenceCountry=country(residenceCountry);
    }
    @AssertTrue public boolean isNonempty() { return firstName!=null || lastName!=null || dateOfBirth!=null
            || nationality!=null || residenceCountry!=null || addressLine!=null || city!=null; }
    @AssertTrue public boolean isKnownCountry() {return (nationality==null || COUNTRIES.contains(nationality))
            && (residenceCountry==null || COUNTRIES.contains(residenceCountry));}
    private static String trim(String value){return value==null?null:value.strip();}
    private static String country(String value){return value==null?null:value.strip().toUpperCase(Locale.ROOT);}
    @Override public String toString(){return "PatchCustomerRequest[REDACTED]";}
}
