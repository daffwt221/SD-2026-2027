package tcp01;

import java.io.*;
import java.net.*;

public class Person implements Serializable {
    private String name;
    private int year;
    private static final long serialVersionUID = 1L;

    public Person(String name, int year) {
        this.name = name;
        this.year = year;
    }

    public String getName() {
        return name;
    }

    public int getyear() {
        return year;
    }
}