package tcp01;

import java.io.*;
import java.net.*;

public class Person implements Serializable {
    private String name;
    private int year;
    private static final long serialVersionUID = 1L;
    private Place place;

    public Person(String name, Place place, int year ) {
        this.name = name;
        this.year = year;
        this.place = place;
    }

    public String getName() {
        return name;
    }

    public int getyear() {
        return year;
    }
    public Place getPlace() {
        return place;
    }
    public String getLocality() {
        return place.getLocality();
    }
    public String getPostalCode() {
        return place.getPostalCode();
    }
}
