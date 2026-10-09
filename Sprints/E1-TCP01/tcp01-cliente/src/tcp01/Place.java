package tcp01;

import java.io.*;
import java.net.*;

public class Place implements Serializable {
    private String postalCode;
    private String locality;
    private static final long serialVersionUID = 1L;

    public Place(String locality, String postcode) {
        this.locality = locality;
        this.postalCode = postcode;
    }

    public String getLocality() {
        return locality;
    }

    public String getPostalCode() {
        return postalCode;
    }

}
